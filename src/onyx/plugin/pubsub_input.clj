(ns onyx.plugin.pubsub-input
  (:require
   [clojure.core.async :refer [<!!] :as async]
   [onyx.schema :as os]
   [onyx.plugin.pubsub :as pubsub]
   [onyx.static.default-vals :refer [arg-or-default]]
   [onyx.static.util :refer [kw->fn]]
   [onyx.tasks.pubsub :refer [PubSubInputTaskMap]]
   [onyx.plugin.protocols :as p]


   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debug info warn]])

  (:import
   [com.google.pubsub.v1 ProjectSubscriptionName Subscription]
   [com.google.cloud.pubsub.v1 Subscriber SubscriptionAdminClient MessageReceiver]))

(defrecord PubSubInput
    [google-application-credentials project subscription
     deserializer-fn

     epoch processing]

  p/Plugin
  (start [this event]
    (let [read-chan (async/chan)
          subscriber (pubsub/start-subscriber google-application-credentials
                                              project
                                              subscription
                                              read-chan)]
      (assoc this
             :read-chan read-chan
             :subscriber subscriber)))

  (stop [{:keys [read-chan subscriber] :as this} event]
    (warn "stopping plugin, epoch = " (pr-str epoch))
    (when subscriber
      (-> ^Subscriber subscriber
          .stopAsync
          .awaitTerminated))

    (async/close! read-chan)

    (dissoc this :subscriber :read-chan))

  p/Checkpointed
  (checkpoint [this]
    (info "checkpoint"))

  (checkpointed! [this epoch]
    (info "checkpointed, epoch = " epoch)
    (run! (fn [epoch]
            (info "checkpointing epoch " epoch ", ackers = " (get @processing epoch))
            (run! #(.ack %) (get @processing epoch))
            (vswap! processing dissoc epoch))

          ;; defensively account for case where Onyx skips notification
          ;; of a successful checkpoint.
          (filter (fn [e] (<= e (inc epoch)))
                  (keys @processing))))

  (recover! [this replica-version checkpoint]
    (info "recovering")
    (vreset! epoch 1)
    (vreset! processing {})
    this)

  p/BarrierSynchronization
  (synced? [this ep]
    (info "synced?, ep = " (pr-str ep))
    (vreset! epoch (inc ep))
    this)

  ;; As with SQS, it's also difficult to figure out whether or not we are 'done'
  ;; because there might be messages in flight which can be invisible to us.
  ;;
  ;; As such, we only support streaming operations.
  (completed? [this]
    (info "completed? never complete!")
    false)

  p/Input
  (poll! [{:keys [read-chan] :as this} event timeout-ms]
    (let [timeout-chan (async/timeout timeout-ms)
          [segment res-chan] (async/alts!! [timeout-chan read-chan])]
      (when (= read-chan res-chan)
        (info "has segment: " (pr-str segment))
        (vswap! processing update @epoch conj (:acker segment))
        (timbre/spy :info (deserializer-fn (.toStringUtf8 (:msg segment))))))))


(defn read-handle-exception [event lifecycle lf-kw exception]
  :restart)

(def input-calls
  {:lifecycle/handle-exception read-handle-exception})

(defn input [event]
  (let [task-map (:onyx.core/task-map event)
        _ (s/validate (os/UniqueTaskMap PubSubInputTaskMap) task-map)
        {:keys [pubsub/deserializer-fn
                pubsub/project
                pubsub/subscription
                pubsub/google-application-credentials]} task-map
        deserializer-fn (kw->fn deserializer-fn)]
    (info "Task" (:onyx/name task-map) "opened Google Cloud Pub/Sub subscriber: " project "/" subscription)
    (->PubSubInput google-application-credentials project subscription
                   deserializer-fn

                   (volatile! nil)
                   (volatile! {}))))
