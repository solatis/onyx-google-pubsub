(defproject org.onyxplatform/onyx-google-pubsub "0.13.0.0"
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

                 [org.onyxplatform/onyx "0.13.0"
                  :exclusions [com.google.guava/guava]]

                 ;; Google Cloud Stuff -- Google Cloud Java SDK has quite
                 ;; a bit of dependency spaghetti, so we need to be explicit
                 ;; for a few deps.
                 [com.google.cloud/google-cloud-pubsub "1.31.0"]

                 [cheshire "5.8.0"]]
  :global-vars  {*warn-on-reflection* true}
  :resource-paths ["resources/"]
  :profiles {:dev {:dependencies []
                   :plugins [[lein-set-version "0.4.1"]
                             [lein-update-dependency "0.1.2"]
                             [lein-pprint "1.1.1"]]}})
