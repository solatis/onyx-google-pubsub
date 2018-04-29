(ns onyx.plugin.pubsub-input-test
  (:require
   [clojure.java.io :as io]
   [clojure.core.async :refer [chan timeout poll!]]
   [clojure.test :refer [deftest is]]
   [taoensso.timbre :as timbre :refer [info warn]]
   [onyx api
    [job :refer [add-task]]
    [test-helper :refer [with-test-env]]]
   [onyx.plugin pubsub-input
    [pubsub :as p]]
   [onyx.tasks.pubsub :as task]

   [onyx.plugin.pubsub-util :as util])

  (:import
   com.google.api.gax.rpc.NotFoundException
   com.google.protobuf.ByteString

   [com.google.api.gax.core FixedCredentialsProvider]
   [com.google.auth.oauth2 GoogleCredentials]
   [com.google.pubsub.v1 PushConfig ProjectTopicName ProjectSubscriptionName Subscription PubsubMessage PullRequest PullResponse ReceivedMessage]

   [com.google.cloud.pubsub.v1 TopicAdminClient TopicAdminSettings]
   [com.google.cloud.pubsub.v1 Subscriber SubscriptionAdminClient SubscriptionAdminSettings MessageReceiver]
   [com.google.cloud.pubsub.v1.stub  GrpcSubscriberStub SubscriberStub SubscriberStubSettings]
   [com.google.cloud.pubsub.v1 Publisher]))

(def out-chan (atom nil))

(defn inject-out-ch [event lifecycle]
  (warn "injecting core.async output channel!")
  {:core.async/chan @out-chan})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(deftest pubsub-input-test
  (let [id (java.util.UUID/randomUUID)
        env-config {:onyx/tenancy-id id
                    :zookeeper/address "127.0.0.1:2188"
                    :zookeeper/server? true
                    :zookeeper.server/port 2188}
        peer-config {:onyx/tenancy-id id
                     :zookeeper/address "127.0.0.1:2188"
                     :onyx.peer/job-scheduler :onyx.job-scheduler/greedy
                     :onyx.messaging.aeron/embedded-driver? true
                     :onyx.messaging/allow-short-circuit? false
                     :onyx.peer/coordinator-barrier-period-ms 1000
                     :onyx.messaging/impl :aeron
                     :onyx.messaging/peer-port 40200
                     :onyx.messaging/bind-addr "localhost"}

        topic (str "onyx-test-" (System/currentTimeMillis))
        subscription (str "onyx-test-subscription-" (System/currentTimeMillis))]

    (util/create-topic! util/project topic)
    (util/create-subscription! util/project topic subscription)

    (with-test-env [test-env [3 env-config peer-config]]
      (let [batch-size 10
            job (-> {:workflow [[:in :identity] [:identity :out]]
                     :task-scheduler :onyx.task-scheduler/balanced
                     :catalog [;; Add :in task later
                               {:onyx/name :identity
                                :onyx/fn :clojure.core/identity
                                :onyx/type :function
                                :onyx/max-peers 1
                                :onyx/batch-size batch-size}

                               {:onyx/name :out
                                :onyx/plugin :onyx.plugin.core-async/output
                                :onyx/type :output
                                :onyx/medium :core.async
                                :onyx/batch-size batch-size
                                :onyx/max-peers 1
                                :onyx/doc "Writes segments to a core.async channel"}]
                     :lifecycles [{:lifecycle/task :out
                                   :lifecycle/calls ::out-calls}]}

                    (add-task (task/pubsub-input :in
                                                 {:pubsub/project util/project
                                                  :pubsub/subscription subscription
                                                  :pubsub/google-application-credentials util/google-application-credentials
                                                  :pubsub/deserializer-fn ::clojure.edn/read-string})))

            n-messages 200
            input-messages (map (fn [v] {:n v}) (range n-messages))
            send-result (time (run! (partial util/publish-batch! util/project topic)
                                    (partition-all 10 input-messages)))]

        (reset! out-chan (chan 1000000))
        (let [job-id (:job-id (onyx.api/submit-job peer-config job))
              end-time (+ (System/currentTimeMillis) 2000000)
              results (loop [vs []]
                        (if (or (> (System/currentTimeMillis) end-time)
                                (= n-messages (count vs)))
                          vs
                          (if-let [v (poll! @out-chan)]
                            (recur (conj vs v))
                            (do
                              (Thread/sleep 1000)
                              (recur vs)))))
              get-epoch #(-> (onyx.api/job-snapshot-coordinates peer-config (-> peer-config :onyx/tenancy-id) job-id) :epoch)
              epoch (get-epoch)]
          (Thread/sleep 10000)

          ;; Wait for epoch change before shutting the job down
          (while (= epoch (get-epoch))
            (Thread/sleep 1000))

          (is (= (sort (map :n input-messages))
                 (sort (map :n results))))
          (onyx.api/kill-job peer-config job-id))))))
