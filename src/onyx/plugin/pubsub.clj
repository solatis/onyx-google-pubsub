(ns onyx.plugin.pubsub
  (:require
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [taoensso.timbre :as timbre :refer [info]])
  (:import
   [com.google.protobuf ByteString]
   [com.google.auth.oauth2 GoogleCredentials]
   [com.google.api.gax.core CredentialsProvider FixedCredentialsProvider GoogleCredentialsProvider]

   [com.google.pubsub.v1 ProjectTopicName ProjectSubscriptionName PubsubMessage ReceivedMessage]
   [com.google.cloud.pubsub.v1 Subscriber MessageReceiver]
   [com.google.cloud.pubsub.v1 Publisher]
   [com.google.cloud.pubsub.v1.stub  GrpcSubscriberStub SubscriberStub SubscriberStubSettings]))

(defn credentials-from-file
  "Based on a file's location, parses the file and coerces into a
  FixedCredentialsProvider.

  This allows specifying the credentials file manually, rather than
  relying upon the GOOGLE_APPLICATION_CREDENTIALS environment variable."
  [f]
  (info "using credentials from file: " f)
  (-> (GoogleCredentials/fromStream (io/input-stream f))
      (.createScoped ["https://www.googleapis.com/auth/cloud-platform"])
      (FixedCredentialsProvider/create)))

(defn credentials-from-env
  "Returns google default credentials provider, whose behavior is documented
  here: https://developers.google.com/identity/protocols/application-default-credentials"
  []
  (info "using credentials from env")
  (.build (GoogleCredentialsProvider/newBuilder)))

(defn- credentials-provider
  "Attempts to coerce c into a credentials provider. If c is nil, returns
  a default credentials provider."
  [c]
  (if c
    (credentials-from-file c)
    (credentials-from-env)))

(defn start-subscriber
  "Starts new subscriber background process. Blocks until service is fully running.
  Returns Subscriber object, and puts results into the provided core.async channel.

  You can stop the subscriber by calling .stopAsync on the returned
  Subscriber object. This will also automatically close the provided core.async
  channel.

  :c
  : The location of the GOOGLE_APPLICATION_CREDENTIALS file. Leave nil for using
  : the default behavior as documented at
  : https://developers.google.com/identity/protocols/application-default-credentials

  :p
  : The project id.

  :s
  : Subscription id."

  [c p s r]
  (let [receiver (reify
                   MessageReceiver
                   (receiveMessage [this msg acker]
                     (async/put! r {:msg (.getData msg)
                                    :acker acker})))

        ^Subscriber subscriber (-> (ProjectSubscriptionName/of p s)
                                   (Subscriber/newBuilder receiver)
                                   (.setCredentialsProvider (credentials-provider c))
                                   (.build))]

    (-> subscriber
        .startAsync
        .awaitRunning)

    subscriber))

(defn start-publisher
  "Starts new publisher background process. Blocks until service is fully running.
  Returns Publisher object that can be used to put messages on the topic.

  You can stop the subscriber by calling .stopAsync on the returned
  Subscriber object. This will also automatically close the provided core.async
  channel.

  :c
  : The location of the GOOGLE_APPLICATION_CREDENTIALS file. Leave nil for using
  : the default behavior as documented at
  : https://developers.google.com/identity/protocols/application-default-credentials

  :p
  : The project id.

  :t
  : Topic id."

  [c p t]
  (-> (ProjectTopicName/of p t)
      (Publisher/newBuilder)
      (.setCredentialsProvider (credentials-provider c))
      (.build)))

(defn ->pubsub-ingestable
  "Coerces a value into an object that can be ingested by PubSub. Returns an object
  of type PubsubMessage.

  :s
  : Single-arity serialization function that should return a UTF-8 String.

  :v
  : The value being ingested. Should be accepted by s."
  [s v]
  (let [data (ByteString/copyFromUtf8
              (s v))]
    (-> (PubsubMessage/newBuilder)
        (.setData data)
        (.build))))

(defn publish!
  "Publishes a message. Returns an ApiFuture object that complete when
  individual messages have been delivered successfully.

  :p
  : Google PubSub Publisher object acquired using `start-publisher`

  :x
  : Message to be published. Expected to be of type PubSubMessage, created using
  : `->pubsub-ingestable`."
  [^Publisher p x]
  (.publish p x))

(defn publish-batch!
  "Publishes a batch of messages. Returns a vector of ApiFuture objects that
   complete when individual messages have been delivered successfully.

  :p
  : Google PubSub Publisher object acquired using `start-publisher`

  :xs
  : Messages to be published. Expected to be of type PubSubMessage, created using
  : `->pubsub-ingestable`.
  "
  [^Publisher p xs]
  (map (partial publish! p) xs))
