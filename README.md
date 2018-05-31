## onyx-google-pubsub

Onyx plugin for Google Cloud Pub/Sub.

#### Installation

In your project file:

```clojure
[org.onyxplatform/onyx-google-pubsub "0.13.0.0"]
```

In your peer boot-up namespace:

```clojure
(:require [onyx.plugin.pubsub-input]
          [onyx.plugin.pubsub-output])
```

##### Dependency conflicts

Because Onyx 0.13 still relies on an older version of Apache `org.apache.curator/curator-test`, which in turn relies on an old version of Google's Guava library, there is a dependency conflict with Google's own Java SDKs which use a much newer version of Guava.

The solution is to ensure that you're excluding `com.google.guava/guava` from all your Onyx inclusions, and rely on the version that is pulled in through `com.google.cloud/google-cloud-pubsub` like this:

```
[org.onyxplatform/onyx "0.13.0"
 :exclusions [com.google.guava/guava]]
```

This exclusion is safe and has been tested extensively in production at [SocialStreams.io](https://www.socialstreams.io/).

#### Limitations

* Per Google Cloud Pub/Sub's behavior, message ordering is not preserved.

* Conforming to Google Pub/Sub's behavior, this plugin implements at-least-once delivery. As a
result, you are recommended to implement idempotent input processors.

* For high throughput we are making use of the async API. This means that there can be a large
number of messages 'in flight', depending on your workload. This could theoretically cause
undesired behavior. You can control the maximum number of in-flight messages using the
:pubsub/max-inflight-messages attribute.

* Where the output plugin publishes messages directly to a topic, the input plugin assumes
a pre-existing subscription and requires you to provide it. If you launch multiple input
jobs concurrently, Google Cloud Pub/Sub distributes workload evenly among active subscribers.

* There is no ability to process message metadata.

#### Functions

##### Input Task

Catalog entry:

```clojure
{:onyx/name <<TASK_NAME>>
 :onyx/plugin :onyx.plugin.pubsub-input/input
 :onyx/type :input
 :onyx/medium :pubsub
 :onyx/batch-size 10
 :onyx/batch-timeout 1000
 :pubsub/project "yourproject-12345"
 :pubsub/subscription "yoursubscription-54321"
 :pubsub/deserializer-fn :clojure.edn/read-string
 :pubsub/max-inflight-messages 50000
 :onyx/doc "Reads segments from a Google Cloud Pub/Sub Subscription"}
```

###### Attributes

|key                                      | type      | description
|-----------------------------------------|-----------|------------
|`:pubsub/google-application-credentials` | `string`  | Absolute file location to the application credentials JSON file. Typically called `application_default_credentials.json`, you can generate and download these from Google Cloud's IAM.
|`:pubsub/deserializer-fn`                | `keyword` | A keyword pointing to a fully qualified function that will deserialize the message payload from a string.
|`:pubsub/project`                        | `string`  | Your Google Cloud project id.
|`:pubsub/subscription`                   | `string`  | The subscription id
|`:pubsub/max-inflight-messages`          | `integer` | Maximum number of in-flight messages.


##### Output Task

Catalog entry:

```clojure
{:onyx/name <<TASK_NAME>>
 :onyx/plugin :onyx.plugin.pubsub-output/output
 :onyx/type :output
 :onyx/medium :pubsub
 :onyx/batch-size 10
 :onyx/batch-timeout 1000
 :pubsub/project "yourproject-12345"
 :pubsub/subscription "yoursubscription-54321"
 :pubsub/serializer-fn :clojure.core/pr-str
 :pubsub/max-inflight-messages 50000
 :onyx/doc "Writes segments to a Google Cloud Pub/Sub Topic"}
```

###### Attributes

|key                                      | type      | description
|-----------------------------------------|-----------|------------
|`:pubsub/google-application-credentials` | `string`  | Absolute file location to the application credentials JSON file. Typically called `application_default_credentials.json`, you can generate and download these from Google Cloud's IAM.
|`:pubsub/serializer-fn`                  | `keyword` | A keyword pointing to a fully qualified function that will serialize the message payload to a string.
|`:pubsub/project`                        | `string`  | Your Google Cloud project id.
|`:pubsub/topic`                          | `string`  | The topic
|`:pubsub/max-inflight-messages`          | `integer` | Maximum number of in-flight messages.

#### Running tests

You need to provide two variables:
 * Your Google Cloud project id, eg 'my-project-54321'
 * The location of your credentials JSON file, commonly application_default_credentials.json.

You can edit these values at `test/onyx/plugin/pubsub_util.clj`.

#### Contributing

Pull requests into the master branch are welcomed.

#### License

Distributed under the Eclipse Public License, the same as Clojure.
