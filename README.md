# debezium-embedded [![CircleCI](https://circleci.com/gh/hden/debezium-embedded/tree/master.svg?style=svg)](https://circleci.com/gh/hden/debezium-embedded/tree/master) [![Clojars Project](https://img.shields.io/clojars/v/hden/debezium-embedded.svg)](https://clojars.org/hden/debezium-embedded)

A Clojure wrapper for the Debezium embedded engine.

> This debezium-embedded module defines a small library that allows an application to easily configure and run Debezium connectors. -- [source](https://github.com/debezium/debezium/tree/master/debezium-embedded)

## Installation

```clojure
[hden/debezium-embedded "3.0.8-SNAPSHOT"]
```

## Dependencies

- Clojure 1.12.0 or higher
- Debezium Embedded 3.0.8.Final
- PostgreSQL connector (if using PostgreSQL)

## Usage

```clojure
(ns your-namespace
  (:require [debezium-embedded.core :refer [create-engine]]))

;; Create configuration
(def config
  {:name "debezium-engine"
   :connector.class "io.debezium.connector.postgresql.PostgresConnector"
   :database.hostname "localhost"
   :database.port "5432"
   :database.user "postgres"
   :database.password "postgres"
   :database.dbname "postgres"
   :schema.include.list "inventory"
   :topic.prefix "test"
   :plugin.name "pgoutput"
   :offset.storage "org.apache.kafka.connect.storage.MemoryOffsetBackingStore"
   :offset.flush.interval.ms "0"
   :converter.schemas.enable "false"})

;; Function to handle events
(defn handle-events [records]
  (println "Received records:" records))

;; Create and run the engine
(with-open [engine (create-engine {:config config
                                 :consumer handle-events})]
  (.execute thread-pool engine))
```

## Development

### Running Tests

```bash
lein test
```

### Starting REPL

```bash
lein repl
```

## Related Projects

See also:
- https://github.com/hden/debezium-embedded-jdbc

## License
Copyright © 2019 Haokang Den
