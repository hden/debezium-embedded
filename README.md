# debezium-embedded [![CircleCI](https://circleci.com/gh/hden/debezium-embedded/tree/master.svg?style=svg)](https://circleci.com/gh/hden/debezium-embedded/tree/master) [![Clojars Project](https://img.shields.io/clojars/v/hden/debezium-embedded.svg)](https://clojars.org/hden/debezium-embedded)

A Clojure wrapper for the Debezium embedded engine.

> This debezium-embedded module defines a small library that allows an application to easily configure and run Debezium connectors. -- [source](https://github.com/debezium/debezium/tree/master/debezium-embedded)

## Installation

```clojure
[hden/debezium-embedded "4.0.0-SNAPSHOT"]
```

## Dependencies

- Clojure 1.12.0 or higher
- Debezium Embedded 3.6.1.Final
- PostgreSQL connector (if using PostgreSQL)

## Usage

```clojure
(ns your-namespace
  (:require [debezium-embedded.core :as core]))

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

;; Create, start, and stop the wrapper-owned engine.
;; Use a durable OffsetBackingStore in production; this in-memory store is
;; appropriate only for ephemeral local runs.
(with-open [engine (core/create-engine {::core/config config
                                        ::core/consumer handle-events
                                        ::core/on-event (fn [event]
                                                          (prn event))})]
  (core/start! engine {})
  ;; ... application work ...
  (core/stop! engine {}))
```

`start!` waits without a timeout until Debezium reports that polling has
started or completes. A successful return therefore means that polling has
started; completion before polling returns an anomaly. `stop!` requests
shutdown and waits for Debezium's completion callback; it returns an anomaly
when that callback reports failure or does not arrive before its configured
timeout.

## Status and probes

Debezium owns connector lifecycle. This wrapper retains only the most recent
Debezium lifecycle or completion callback as an observed fact.

`polling?` is a conservative readiness building block: it is true only when
the most recent callback is `::polling-started`. It becomes false when a later
callback reports `::polling-stopped` or `::completed`.

```clojure
(core/polling? engine)
```

`latest-event` exposes the fact so the application can define its own health
policy. For example, an application might restart only after a failed Debezium
completion, while treating an intentional successful shutdown as live:

```clojure
(let [{:keys [event success?]} (core/latest-event engine)]
  (not (and (= event ::core/completed)
            (false? success?))))
```

`::on-event` receives the same callback facts and wrapper-local failure events
(including consumer, acknowledgement, submission, and shutdown failures)
asynchronously. Its value has this shape:

```clojure
{::core/event       ::core/event-observed
 ::core/observation {:event ::core/polling-started}}
```

The wrapper deliberately does not prescribe readiness or liveness policy.
Those policies decide traffic routing and restart behavior, which belong to
the application and its orchestrator.

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

### Coverage and CRAP

```bash
make coverage # Generate Cloverage HTML and LCOV reports.
make crap     # Generate coverage, print a CRAP report, and enforce CRAP < 30.
make quality  # Run the shared local and CI quality gate.
```

The quality gate fails when a function's CRAP score is 30 or higher. Coverage
reports are written to `target/coverage/`.

To enable the versioned pre-push hook, run this once in your clone:

```bash
git config core.hooksPath .githooks
```

The hook runs `make quality` and prevents the push when it fails.

### Publishing to Clojars

```bash
make deploy
```

This runs the quality gate and then uses the existing Leiningen publishing
workflow: `lein deploy clojars`. Configure Clojars credentials in your local
Leiningen profile; do not store them in this repository.

### Starting REPL

```bash
lein repl
```

## Related Projects

See also:
- https://github.com/hden/debezium-embedded-jdbc

## License
Copyright © 2019 Haokang Den
