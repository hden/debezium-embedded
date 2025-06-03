(defproject hden/debezium-embedded "3.1.0-SNAPSHOT"
  :description "A Clojure wrapper for the debezium-embedded engine."
  :url "https://github.com/hden/debezium-embedded"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.12.1"]
                 [camel-snake-kebab "0.4.3"]
                 [io.debezium/debezium-embedded "3.1.2.Final"]]
  :profiles {:dev {:global-vars {*warn-on-reflection* true}
                   :dependencies [[funcool/promesa "11.0.678"]
                                  ;; [org.slf4j/slf4j-simple "2.0.17"]
                                  [io.debezium/debezium-connector-postgres "3.1.2.Final"]]}}
  :repl-options {:init-ns debezium-embedded.core})
