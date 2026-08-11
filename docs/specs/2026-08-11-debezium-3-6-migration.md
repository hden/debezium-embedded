# Debezium 3.6 Migration Specification

## Goal

Upgrade the library to Debezium `3.6.1.Final` and replace all use of the
removed `EmbeddedEngine` API with the supported `DebeziumEngine` API.

## Acceptance criteria

- Both direct Debezium dependencies resolve to `3.6.1.Final`.
- The production namespace and test namespace no longer import
  `io.debezium.embedded.EmbeddedEngine` or its builder.
- `create-engine` builds a `DebeziumEngine` that consumes
  `RecordChangeEvent<SourceRecord>` values.
- The consumer supplied to `create-engine` continues to receive a vector of
  the existing Clojure map event representation.
- Each consumed Debezium record is marked processed and each batch is marked
  finished.
- `running?` returns true only while the engine reports polling has started,
  and false after polling stops or the engine completes.
- The PostgreSQL integration test starts the 3.6 engine, receives events, and
  verifies the lifecycle state.
- Clojure formatting and the relevant test suite pass.

## Non-goals

- Do not modify, close, or merge Renovate-managed pull requests.
- Do not expose asynchronous-engine thread or task-count settings through this
  wrapper.
- Do not change the map shape delivered to consumer functions.
- Do not add support for engines created outside `create-engine` to `running?`.

## Constraints

- All direct Debezium artifacts must use the exact version `3.6.1.Final`.
- Preserve the Clojure function names `create-engine` and `running?`.
- Define running as the lifecycle interval between `pollingStarted` and
  `pollingStopped` or completion.
