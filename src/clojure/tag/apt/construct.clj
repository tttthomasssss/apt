(ns tag.apt.construct
  "Main task for constructing Distributional Lexicons"
  (:gen-class)
  (:import (uk.ac.susx.tag.apt ArrayAPT RGraph Indexer AccumulativeAPTStore AccumulativeLazyAPT)
           (java.io File BufferedReader FileReader FileOutputStream FileInputStream ByteArrayOutputStream)
           (java.util.zip GZIPOutputStream GZIPInputStream))
  (:require [tag.apt.util :as util]
            [tag.apt.conll :as conll]
            [tag.apt.backend :as b]
            [tag.apt.core :refer [count-paths!]]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [tag.apt.backend.leveldb :as leveldb]))

(set! *warn-on-reflection* true)

(defn graph->apts
  "Converts a RGraph instance to a vector of [int ArrayAPT] tuples representing
  a mapping from entity indices (in the original sentence, as transferred by
  graph.entityIDs) to nodes in the collapsed APT version of the input graph."
  [^RGraph graph]
  (let [eids (.entityIds graph)
        len  (count eids)
        apts (.fromGraph ArrayAPT/factory graph)]
    (loop [acc (transient []) i (int 0)]
      (if (< i len)
        (if-let [apt (aget apts i)]
          (recur (conj! acc [(aget eids i) apt]) (inc i))
          (recur acc (inc i)))
        (persistent! acc)))))


(defn raw-sentence->graph
  "Converts a still-stringified sentence in the form of a seq of seqs, conll
  style, into an RGRaph instance."
  [^Indexer entity-indexer ^Indexer relation-indexer sentence]
  (let [graph (RGraph. (count sentence))
        ids (.entityIds graph)]
    (doseq [[^String i entity ^String gov-offset dep] sentence]
      (let [i (dec (Integer. i))]
        (aset ids i (.getIndex entity-indexer entity))
        (when (not-empty gov-offset)
          (.addRelation graph
                        i
                        (dec (Integer. gov-offset))
                        (.getIndex relation-indexer dep)))))
    graph))



(defn open-file [^File file]
  (let [file (io/as-file file)]
    (if (.endsWith ^String (.getName file) ".gz")
      (util/gz-reader file (* 1024 1024))
      (BufferedReader. (FileReader. file) (* 1024 1024)))))

(defn sent-extractor [file-channel sent-channel]
  (util/godochan [file file-channel]
    (with-open [in (open-file file)]
      (doseq [sent (conll/parse in)]
        (async/>! sent-channel sent)))))

(defn- get-input-files [dir-or-file]
  (let [f (io/as-file dir-or-file)]
    (if (.isDirectory f)
      (seq (.listFiles f))
      [f])))

(defn- b->gb [n]
  (double (/ n 1024 1024 1024)))

(defn- dp [n dp]
  (let [shift (Math/pow 10 dp)]
    (/ (long (* shift n)) shift)))

(defn- memory-report []
  (let [rt (Runtime/getRuntime)]
    { :max (dp (b->gb (.maxMemory rt)) 1)
      :used (dp (b->gb (- (.totalMemory rt) (.freeMemory rt))) 1)
     }))

(def sents-per-report 10000)

(defn -main [output-dir depth & input-files]
  (let [lexicon-descriptor (b/lexicon-descriptor (io/as-file output-dir))
        output-byte-store (leveldb/from-descriptor lexicon-descriptor)
        accumulative-apt-store (AccumulativeAPTStore. output-byte-store (Integer. ^String depth))
        entity-indexer (b/get-entity-index lexicon-descriptor)
        relation-indexer (b/get-relation-index lexicon-descriptor)
        sum (atom (b/get-sum lexicon-descriptor))
        number-of-sentences-processed (atom 0)
        last-report-time (atom (System/currentTimeMillis))
        ;path-counter (b/get-path-counter lexicon-descriptor)
        everything-counter (AccumulativeLazyAPT.)
        consume-sentence! (fn [sent]
                            (when-let [apt-seq (try (->> sent
                                                         (raw-sentence->graph entity-indexer relation-indexer)
                                                         graph->apts)
                                                    (catch NumberFormatException e
                                                      (locking *out*
                                                        (println "couldn't do sent:\n" sent))))]

                              (doseq [[entity-id apt] apt-seq]
                                (.include accumulative-apt-store entity-id apt)
                                (swap! sum inc)
                                ;(count-paths! path-counter apt depth)
                                (.merge everything-counter apt (Integer. ^String depth) 0)
                                )
                              (swap! number-of-sentences-processed inc)))
        file-channel (async/chan)
        sent-channel (async/chan 1000)
        sent-extractors (take 5 (repeatedly #(sent-extractor file-channel sent-channel)))
        num-sent-consumers (* 2 (.availableProcessors (Runtime/getRuntime)))
        sent-consumers (take num-sent-consumers (repeatedly #(util/godochan [sent sent-channel] (consume-sentence! sent))))]
    (try

      ; populate file channel
      (async/go
        (doseq [file (mapcat get-input-files input-files)]
          (async/>! file-channel file))
        (async/close! file-channel))

      ; setup reporter
      (add-watch number-of-sentences-processed
                 :reporter
                 (fn [_ _ _ new-val]
                   (let [current-time (System/currentTimeMillis)
                         mem (memory-report)]
                     (when (= 0 (mod new-val sents-per-report))
                       (println "done" new-val "sentences." (float (/ sents-per-report (/ (- current-time @last-report-time) 1000))) "sents/s."
                                "Used" (:used mem) "of" (:max mem))
                       (reset! last-report-time current-time)))))
      ; start producers
      (dorun sent-extractors)
      ; start consumers
      (dorun sent-consumers)
      ; wait for producers to finish
      (dorun (map async/<!! sent-extractors))
      ; close channel so consumer guys finish
      (async/close! sent-channel)
      ; wait for consumer guys to finish
      (dorun (map async/<!! sent-consumers))

      (finally

        ; clean up resources
        (.close accumulative-apt-store)
        (b/store-entity-index lexicon-descriptor entity-indexer)
        (b/store-relation-index lexicon-descriptor relation-indexer)
        (b/put-sum lexicon-descriptor @sum)
        (b/store-everything-counts lexicon-descriptor everything-counter)
        ;(b/store-path-counter lexicon-descriptor path-counter)

        ; goodbye world
        (shutdown-agents)))
    ))
