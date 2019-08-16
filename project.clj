(defproject hden/debezium-embedded "0.2.0-SNAPSHOT"
  :description "A Clojure wrapper for the debezium-embedded engine."
  :url "http://example.com/FIXME"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [io.debezium/debezium-embedded "0.10.0.Beta4"]]
  :repl-options {:init-ns debezium-embedded.core})
