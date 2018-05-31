(ns onyx.tasks.pubsub
  (:require
   [cheshire.core :as json]
   [onyx.schema :as os]
   [schema.core :as s])
  (:import [com.fasterxml.jackson.core JsonGenerationException]))

;;;; Input task
(defn deserialize-message-json [s]
  (try
    (json/parse-string s true)
    (catch Exception e
      {:error e})))

(defn deserialize-message-edn [s]
  (try
    (read-string s)
    (catch Exception e
      {:error e})))

(def PubSubInputTaskMap
  {(s/optional-key :pubsub/project) s/Str
   (s/optional-key :pubsub/subscription) s/Str
   (s/optional-key :pubsub/max-inflight-receive-batches) s/Int
   (s/optional-key :pubsub/google-application-credentials) s/Str
   :pubsub/deserializer-fn os/NamespacedKeyword
   :onyx/batch-size s/Int
   (os/restricted-ns :pubsub) s/Any})

(s/defn ^:always-validate pubsub-input
  ([task-name task-opts]
   (assert (and (:pubsub/project task-opts)
                (:pubsub/subscription task-opts))
           "Must specify both :pubsub/project and :pubsub/subscription to taskbundle opts")

   (println "got task opts: " (pr-str task-opts))
   {:task {:task-map (merge {:onyx/name task-name
                             :onyx/plugin :onyx.plugin.pubsub-input/input
                             :onyx/type :input
                             :onyx/medium :pubsub
                             :onyx/batch-size 10
                             :onyx/batch-timeout 1000
                             :onyx/doc "Reads segments from a Google Cloud Pub/Sub topic"}
                            task-opts)
           :lifecycles [{:lifecycle/task task-name
                         :lifecycle/calls :onyx.plugin.pubsub-input/input-calls}]}
    :schema {:task-map PubSubInputTaskMap}}))

;; Output task
(defn serialize-message-json [segment]
  (try
    (json/generate-string segment)
    (catch JsonGenerationException e
      (throw (ex-info (format "Could not serialize segment: %s" segment)
                      {:recoverable? false
                       :segment segment
                       :cause e})))))

(defn serialize-message-edn [segment]
  (pr-str segment))

(def PubSubOutputTaskMap
  {(s/optional-key :pubsub/project) s/Str
   (s/optional-key :pubsub/topic) s/Str
   (s/optional-key :pubsub/google-application-credentials) s/Str
   :pubsub/serializer-fn os/NamespacedKeyword
   :onyx/batch-size s/Int
   (os/restricted-ns :pubsub) s/Any})


(s/defn ^:always-validate pubsub-output
  [task-name :- s/Keyword task-opts :- {s/Any s/Any}]
  (assert (and (:pubsub/project task-opts)
               (:pubsub/topic task-opts))
          "Must specify both :pubsub/project and :pubsub/topic to taskbundle opts")
  (println "got task opts: " (pr-str task-opts))
  {:task {:task-map (merge {:onyx/name task-name
                            :onyx/plugin :onyx.plugin.pubsub-output/output
                            :onyx/type :output
                            :onyx/medium :pubsub
                            :onyx/batch-size 10
                            :onyx/doc "Writes segments to a Google Cloud Pub/Sub topic"}
                           task-opts)
          :lifecycles [{:lifecycle/task task-name
                        :lifecycle/calls :onyx.plugin.pubsub-output/output-calls}]}
   :schema {:task-map PubSubOutputTaskMap}})
