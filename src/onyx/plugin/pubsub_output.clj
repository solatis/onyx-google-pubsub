(ns onyx.plugin.pubsub-output
  (:require
   [clojure.core.async :refer [<!!] :as async]
   [onyx.schema :as os]
   [onyx.plugin.pubsub :as pubsub]
   [onyx.static.default-vals :refer [arg-or-default]]
   [onyx.static.util :refer [kw->fn]]
   [onyx.tasks.pubsub :refer [PubSubOutputTaskMap]]
   [onyx.plugin.protocols :as p]

   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [info warn]])

  (:import
   [com.google.pubsub.v1 ProjectSubscriptionName Subscription]
   [com.google.cloud.pubsub.v1 Publisher]))

(defn- remove-completed-futures! [futures]

  (let [future-done? (fn [f]
                       (.isDone f))
        throw-if-failed-fn (fn [f]
                             ;; Should always (filter future-done?) before calling
                             ;; this function.
                             (assert (.isDone f))

                             ;; Our code does not cancel futures at this point. The
                             ;; point where we could do it is probably the `stop`
                             ;; function of PubSubOutput, but due to possible race
                             ;; conditions we just flush the buffer at that point.
                             (assert (not (.isCancelled f)))

                             ;; Google's Guava futures rethrow the async exception
                             ;; on .get, we do not do anything with the return value.
                             ;;
                             ;; Because f is guaranteed to be completed, this does
                             ;; not block.
                             (.get f))]

    (vswap! futures (fn [fs]
                      (->> fs
                           (filter future-done?)
                           (run! throw-if-failed-fn))

                      (remove future-done? fs)))))

(defrecord PubSubOutput
    [google-application-credentials project topic
     serializer-fn

     processing]

  p/Plugin
  (start [this event]
    (let [^Publisher publisher (pubsub/start-publisher google-application-credentials
                                                       project
                                                       topic)]
      (assoc this
             :publisher publisher)))

  (stop [{:keys [publisher] :as this} event]
    (when publisher
      ;; This is a blocking function that flushes all messages in-flight.
      (.shutdown publisher))

    (dissoc this :publisher))

  p/Checkpointed
  (checkpoint [this])
  (checkpointed! [this epoch]
    true)
  (recover! [this replica-version checkpoint]
    (vreset! processing [])
    this)

  p/BarrierSynchronization
  (synced? [this epoch]
    (empty? (remove-completed-futures! processing)))
  (completed? [this]
    (empty? (remove-completed-futures! processing)))

  p/Output
  (prepare-batch [this event replica _]
    true)

  (write-batch [{:keys [publisher] :as this} {:keys [onyx.core/write-batch] :as event} replica _]
    (remove-completed-futures! processing)

    (let [transform-fn (partial pubsub/->pubsub-ingestable serializer-fn)
          futures (->> write-batch
                       (map transform-fn)
                       (pubsub/publish-batch! publisher))]
      (vswap! processing concat futures)
      true)))


(defn write-handle-exception [event lifecycle lf-kw exception]
  :restart)

(def output-calls
  {:lifecycle/handle-exception write-handle-exception})

(defn output [event]
  (let [task-map (:onyx.core/task-map event)
        {:keys [pubsub/serializer-fn
                pubsub/project
                pubsub/topic
                pubsub/google-application-credentials]} task-map
        serializer-fn (kw->fn serializer-fn)]
    (s/validate (os/UniqueTaskMap PubSubOutputTaskMap) task-map)

    (info "Task" (:onyx/name task-map) "opened Google Cloud Pub/Sub publisher: " project "/" topic)
    (->PubSubOutput google-application-credentials
                    project topic
                    serializer-fn
                    (volatile! []))))
