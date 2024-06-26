(ns ragtacts.legacy.collection
  (:refer-clojure :exclude [sync])
  (:require [clj-ulid :refer [ulid]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [overtone.at-at :as at]
            [ragtacts.legacy.connector.base :as connector]
            [ragtacts.legacy.embedder.base :as embedder]
            [ragtacts.logging :as log]
            [ragtacts.legacy.splitter.base :as splitter :refer [make-chunk]]
            [ragtacts.legacy.vector-store.base :as vector-store]))

(defn- apply-change-log [{:keys [splitter embedder vector-store]} {:keys [type doc]}]
  (log/debug "Apply change log:" type doc)
  (case type
    (:create :update) (let [chunks (splitter/split splitter [doc])
                            _ (binding [*print-length* 5]
                                (log/debug (str "Chunks count:" (count chunks)) chunks))
                            vectors (embedder/embed embedder chunks)
                            _ (binding [*print-length* 5]
                                (log/debug (str "Vectors count:" (count vectors)) vectors))]
                        (when (= :update type)
                          (vector-store/delete-by-id vector-store (:id doc)))
                        (vector-store/insert vector-store vectors))
    :delete (vector-store/delete-by-id vector-store (:id doc))
    (throw (ex-info "Unknown change log type" {:type type}))))

(defprotocol Collection
  (sync [this callback])
  (stop [this])
  (search [this text metadata]))

(defn- last-change-file-name [connector]
  (let [connector-name (.getName (type connector))]
    (str "last-change-" connector-name ".edn")))

(defn- load-last-change [connector]
  (try
    (with-open [r (io/reader (last-change-file-name connector))]
      (edn/read (java.io.PushbackReader. r)))
    (catch Exception _)))

(defn- save-last-change [connector last-change]
  (spit (last-change-file-name connector) last-change))

(defn- wait [{:keys [connectors]}]
  (let [latches (into {} (map (fn [connector]
                                [connector (promise)])
                              connectors))
        pool (at/mk-pool)]
    (at/interspaced 1000
                    (fn []
                      (doseq [connector connectors]
                        (when (connector/closed? connector)
                          (deliver (get latches connector) :complete))))
                    pool)
    (doseq [latch (vals latches)]
      @latch)
    (at/stop-and-reset-pool! pool)))

(defrecord CollectionImpl [id name connectors splitter embedder vector-store]
  Collection
  (sync [this callback]
    (log/debug "Starting Sync" connectors)
    (doseq [connector connectors]
      (connector/connect
       connector
       (fn [{:keys [change-log-result]}]
         (log/debug "Change log result" change-log-result)
         (let [{:keys [change-logs]} change-log-result]
           (doseq [change-log change-logs]
             (apply-change-log {:splitter splitter
                                :embedder embedder
                                :vector-store vector-store} change-log))
           (when (seq change-logs)
             (callback {:type :complete :connector connector}))))
       {:last-change (load-last-change connector)}))
    this)

  (search [_ text metadata]
    (let [prompt-vectors (embedder/embed embedder [(make-chunk text)])]
      (vector-store/search vector-store prompt-vectors nil)))

  (stop [this]
    (log/debug "Stopping Sync")
    (doseq [connector connectors]
      (save-last-change connector (connector/close connector)))
    (wait this)
    this))

(defn make-collection [{:keys [id] :as opts}]
  (let [id (or id (ulid))]
    (map->CollectionImpl (merge opts {:id id}))))

(comment

  ;;
  )