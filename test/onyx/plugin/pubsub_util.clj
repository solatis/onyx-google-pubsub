(ns onyx.plugin.pubsub-util
  "Defines utility functions for tests, mostly boilerplate that wraps
  Google's PubSub SDK."

  (:require
   [onyx.plugin.pubsub :as pubsub]
   [taoensso.timbre :as timbre :refer [debug info warn]]
   )

  (:import
   [com.google.protobuf ByteString]

   [com.google.api.gax.core FixedCredentialsProvider]
   [com.google.auth.oauth2 GoogleCredentials]
   [com.google.pubsub.v1 PushConfig ProjectTopicName ProjectSubscriptionName PubsubMessage]

   [com.google.cloud.pubsub.v1 TopicAdminClient TopicAdminSettings]
   [com.google.cloud.pubsub.v1 SubscriptionAdminClient SubscriptionAdminSettings ]
   [com.google.cloud.pubsub.v1 Subscriber MessageReceiver]
   [com.google.cloud.pubsub.v1 Publisher]))

(def google-application-credentials "/home/lmergen/git/mondrian/application_default_credentials.json")

(defn ->pubsub-ingestable
  [value]
  (let [data (-> value
                 (pr-str)
                 (ByteString/copyFromUtf8))]

    (-> (PubsubMessage/newBuilder)
        (.setData data)
        (.build))))

(defn create-topic!
  "Ensures that topic is created if it does not yet exist."
  [p t]
  (let [credentials (pubsub/credentials-from-file google-application-credentials)
        settings (-> (TopicAdminSettings/newBuilder)
                     (.setCredentialsProvider credentials)
                     (.build))
        admin (TopicAdminClient/create settings)
        name (ProjectTopicName/of p t)]

    (.createTopic admin name)))

(defn create-subscription!
  "Creates new subscription to a project's topic."
  [p t s]

  (let [credentials (pubsub/credentials-from-file google-application-credentials)
        settings (-> (SubscriptionAdminSettings/newBuilder)
                     (.setCredentialsProvider credentials)
                     (.build))
        admin (SubscriptionAdminClient/create settings)
        topic (ProjectTopicName/of p t)
        subscription (ProjectSubscriptionName/of p s)
        config (PushConfig/getDefaultInstance)]

    (.createSubscription admin subscription topic config 600)))

(defn publish-batch!
  "Publishes a batch of messages to a PubSub topic. Currently fairly slow because
  it re-establishes a new Publisher session every invocation, but it allows us not
  to have to keep track of publisher state."
  [p t xs]
  (let [credentials (pubsub/credentials-from-file google-application-credentials)
        publisher (-> (Publisher/newBuilder
                       (ProjectTopicName/of p t))
                      (.setCredentialsProvider credentials)
                      (.build))
        publish-fn (fn [x]
                     (.publish publisher (->pubsub-ingestable x)))]
    (run! publish-fn xs)

    (.shutdown publisher)))

(defn subscribe!
  [p s ^MessageReceiver r]

  (let [credentials (pubsub/credentials-from-file google-application-credentials)
        subscriber (-> (Subscriber/newBuilder
                        (ProjectSubscriptionName/of p s) r)
                       (.setCredentialsProvider credentials)
                       (.build))]

    (-> subscriber
        .startAsync
        .awaitRunning)

    subscriber))

(defn pull-subscription
  "Pulls in all messages from subscription. Assumes at least `n` messages are available."

  [p s n]

  ;; Not very clean code below, but we're wrapping an async interface
  ;; here and making it synchronous; this is always a bit dirty and this
  ;; is just a test helper function.

  (let [res (promise)

        ;; We're storing internal state in xs, which keeps track of the messages
        ;; we have currently accumulated but have not reached the quantity `n` which
        ;; we expect.
        xs (atom [])

        ;; Note that this starts async code that delivers all messages in promise `res`
        ;; when done.
        subscriber (subscribe! p s
                               (reify
                                 MessageReceiver
                                 (receiveMessage [this msg acker]
                                   (swap! xs (fn [xs]
                                               (let [data (-> msg
                                                              .getData
                                                              .toStringUtf8
                                                              clojure.edn/read-string)
                                                     xs (conj xs data)]
                                                 (.ack acker)

                                                 (timbre/info ">---- (pull-subscription) msg = " (pr-str data) ", subscription = " s ", count = " (count xs))
                                                 (when (<= n (count xs))
                                                   (deliver res xs))
                                                 xs))))))

        ;; And here we block
        res (deref res)]

    (assert (<= n (count res)))

    (-> ^Subscriber subscriber
        .stopAsync
        .awaitTerminated)
    res))
