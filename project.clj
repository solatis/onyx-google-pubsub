(defproject org.onyxplatform/onyx-google-pubsub "0.12.7.1"
  :description "Onyx plugin for Google Cloud Pub/Sub"
  :url "https://github.com/onyx-platform/onyx-google-pubsub"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"snapshots" {:url "https://clojars.org/repo"
                              :username :env
                              :password :env
                              :sign-releases false}
                 "releases" {:url "https://clojars.org/repo"
                             :username :env
                             :password :env
                             :sign-releases false}}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 ^{:voom {:repo "git@github.com:onyx-platform/onyx.git" :branch "master"}}

                 [com.google.guava/guava "24.1-jre"]
                 [com.google.code.findbugs/jsr305 "3.0.0"]
                 [org.apache.curator/curator-framework "4.0.1"]
                 [org.apache.curator/curator-test "4.0.1"]

                 [org.onyxplatform/onyx "0.12.7"
                  :exclusions [com.google.guava/guava
                               org.apache.curator/curator-framework
                               org.apache.curator/curator-test]]

                 ;; Google Cloud Stuff -- Google Cloud Java SDK has quite
                 ;; a bit of dependency spaghetti, so we need to be explicit
                 ;; for a few deps.
                 [com.google.cloud/google-cloud-pubsub "0.45.0-beta"
                  :exclusions [com.google.code.findbugs/jsr305]]

                 [cheshire "5.7.0"]]
  :global-vars  {*warn-on-reflection* true}
  :resource-paths ["resources/"]
  :profiles {:dev {:dependencies []
                   :plugins [[lein-set-version "0.4.1"]
                             [lein-update-dependency "0.1.2"]
                             [lein-pprint "1.1.1"]]}})
