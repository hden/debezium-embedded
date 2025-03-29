(defproject hden/debezium-embedded "3.0.8-SNAPSHOT"
  :description "A Clojure wrapper for the debezium-embedded engine."
  :url "http://example.com/FIXME"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [io.debezium/debezium-embedded "3.0.8.Final"]]
  :profiles {:dev {:dependencies [[funcool/promesa "11.0.678"]
                                  [io.debezium/debezium-connector-postgres "3.0.8.Final"]]}}
  :repl-options {:init-ns debezium-embedded.core})
