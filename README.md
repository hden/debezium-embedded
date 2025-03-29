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

## Record Structure

Each record has the following structure:

```clojure
{:offset
 {:last-snapshot-record false,  ; Whether this is the last snapshot record
  :lsn 34360936,               ; PostgreSQL LSN (Log Sequence Number)
  :tx-id 784,                  ; Transaction ID
  :ts-usec 1743237554845451,   ; Timestamp in microseconds
  :snapshot "INITIAL",         ; Snapshot state
  :snapshot-completed false},  ; Snapshot completion flag
 :value
 {:before nil,                 ; Previous data (for updates/deletes)
  :after                       ; New data (for inserts/updates)
  {:id 1001,
   :first-name "Sally",
   :last-name "Thomas",
   :email "sally.thomas@acme.com"},
  :source                      ; Source information
  {:connector "postgresql",
   :schema "inventory",
   :table "customers",
   :db "postgres",
   :name "test",
   :ts-ms 1743237554845,      ; Timestamp in milliseconds
   :ts-us 1743237554845451,   ; Timestamp in microseconds
   :ts-ns 1743237554845451000, ; Timestamp in nanoseconds
   :snapshot "first",
   :sequence "[null,\"34360936\"]",
   :tx-id 784,
   :lsn 34360936,
   :version "3.0.8.Final"},
  :transaction nil,            ; Transaction information
  :op "r",                     ; Operation type (r: read, c: create, u: update, d: delete)
  :ts-ms 1743237554961,       ; Event timestamp in milliseconds
  :ts-us 1743237554961805,    ; Event timestamp in microseconds
  :ts-ns 1743237554961805000}} ; Event timestamp in nanoseconds
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
