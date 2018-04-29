(ns onyx.plugin.pubsub-output-test
  (:require
   [clojure.java.io :as io]
   [clojure.core.async :refer [chan timeout poll! >!!]]
   [clojure.test :refer [deftest is]]
   [taoensso.timbre :as timbre :refer [info warn]]
   [onyx api
    [job :refer [add-task]]
    [test-helper :refer [with-test-env]]]
   [onyx.plugin pubsub-output
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

(def in-chan (atom nil))
(def in-buffer (atom nil))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer in-buffer
   :core.async/chan @in-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(defn serializer-fn [x]
  (pr-str x))

(def project "mondrian-158913")

(deftest sqs-output-test
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
                     :onyx.messaging/impl :aeron
                     :onyx.messaging/peer-port 40200
                     :onyx.messaging/bind-addr "localhost"}

        topic (str "onyx-test-" (System/currentTimeMillis))
        subscription (str "onyx-test-subscription-" (System/currentTimeMillis))]

    (util/create-topic! project topic)
    (util/create-subscription! project topic subscription)

    (with-test-env [test-env [3 env-config peer-config]]
      (let [batch-size 10
            job (-> {:workflow [[:in :identity] [:identity :out]]
                     :task-scheduler :onyx.task-scheduler/balanced
                     :catalog [{:onyx/name :in
                                :onyx/plugin :onyx.plugin.core-async/input
                                :onyx/type :input
                                :onyx/medium :core.async
                                :onyx/batch-size batch-size
                                :onyx/max-peers 1
                                :onyx/doc "Reads segments from a core.async channel"}

                               {:onyx/name :identity
                                :onyx/fn :clojure.core/identity
                                :onyx/type :function
                                :onyx/batch-size batch-size}
                               ;; Add :out task later
                               ]
                     :lifecycles [{:lifecycle/task :in
                                   :lifecycle/calls ::in-calls}]}
                    (add-task (task/pubsub-output :out
                                                  {:pubsub/project project
                                                   :pubsub/topic topic
                                                   :pubsub/google-application-credentials util/google-application-credentials
                                                   :pubsub/serializer-fn ::serializer-fn})))

            n-messages 400
            _ (reset! in-chan (chan (inc n-messages)))
            _ (reset! in-buffer {})
            input-messages (map (fn [v] {:n v}) (range n-messages))]

        (run! #(>!! @in-chan %) input-messages)

        (let [job-id (:job-id (onyx.api/submit-job peer-config job))
              results (util/pull-subscription project subscription n-messages)]

          (is (= (sort (map :n input-messages))
                 (sort (map :n results))))
          (onyx.api/kill-job peer-config job-id))))))
