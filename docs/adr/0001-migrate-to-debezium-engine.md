# ADR 0001: Migrate from EmbeddedEngine to DebeziumEngine

## Status

Accepted

## Context

This library wraps Debezium's embedded runtime and currently constructs
`io.debezium.embedded.EmbeddedEngine`. That class was removed in Debezium
3.2.0.Alpha1. Updating only `debezium-embedded` therefore fails during class
loading; updating only the PostgreSQL connector mixes Debezium versions and
fails with `NoSuchMethodError`.

Debezium 3.6.1 provides `AsyncEmbeddedEngine` behind the public
`io.debezium.engine.DebeziumEngine` builder API. The implementation keeps its
own state machine private, so it does not expose the old `isRunning()` method.

## Decision

Update every direct Debezium dependency to `3.6.1.Final` in one change.

Construct engines through `DebeziumEngine.create(ChangeEventFormat.of(Connect.class))`
and process `RecordChangeEvent<SourceRecord>` values. The wrapper converts the
enclosed `SourceRecord` to its existing Clojure map representation, while it
acknowledges the enclosing record through the Debezium committer.

Keep the public `running?` function. Track its state with the engine lifecycle
callbacks: it becomes true at `pollingStarted`, and false at `pollingStopped`
or completion. This defines "running" as actively polling connector tasks.

## Consequences

The public Clojure entry points remain available, but `create-engine` returns
the `DebeziumEngine` interface rather than the removed `EmbeddedEngine` class.
Consumers that type-hinted or otherwise depended on the concrete Java class
must migrate to the public Debezium API.

The asynchronous engine can use multiple threads internally. The wrapper uses
the batch `ChangeConsumer` API, so record acknowledgement remains explicit and
the existing consumer contract continues to receive vectors of Clojure maps.

## Alternatives considered

### Remove `running?`

This would avoid wrapper-owned lifecycle state, but unnecessarily breaks the
library's existing Clojure API.

### Depend on `AsyncEmbeddedEngine` directly

This would couple the wrapper to another implementation class and still would
not provide a public running-state API. Using `DebeziumEngine` follows the
upstream-supported construction API and limits implementation coupling.

### Update the two Debezium artifacts independently

This is invalid because the connector and runtime call one another's internal
classes. The observed `Field.withDependents` linkage error is evidence that
mixed versions are not binary compatible.
