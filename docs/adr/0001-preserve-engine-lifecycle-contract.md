# ADR 0001: Preserve the engine lifecycle contract

## Status

Accepted

## Context

This library gives Clojure applications a `running?` function for managing an
embedded Debezium engine. The underlying engine no longer publishes an
equivalent public running-state query: its lifecycle state machine is private.

The immediate trigger is removal of `EmbeddedEngine` after Debezium 3.1. The
replacement changes the implementation, but applications still need a stable
answer to whether this wrapper is capturing database changes.

## Decision

Keep the public `running?` function. Its truth is active change capture, not
engine allocation or task submission.

The wrapper records lifecycle observations and derives `running?`, lifecycle
phase, and record admissibility from them. It does not store an upstream
"trust" judgement or an independent error/safety state alongside the phase.
Upstream callbacks are untrusted observations; a malformed or reordered
callback is recorded as a domain anomaly and leads to safe shutdown.

All failures crossing the Clojure boundary are Cognitect Anomalies maps. On an
anomaly, the wrapper stops admitting records and offsets, requests shutdown
where an engine may still run, and never restarts the engine automatically.

The wrapper, rather than its caller, owns engine submission and shutdown. The
public handle does not expose a raw Debezium engine. `start!` and `stop!` use a
consistent `(fn target arg-map)` command shape. This intentionally replaces the
former pattern of passing `create-engine`'s result directly to an executor:
that pattern cannot preserve a linearized lifecycle contract.

The exact facts, interpretations, and verification requirements belong to the
Change-Capture Lifecycle Contract specification.

## Consequences

The public Clojure entry point remains available, while its meaning is owned by
this library rather than an implementation-specific Debezium state query.

The lifecycle model has one mutable source of truth: an ordered observation
trace. This prevents contradictory stored combinations such as a lifecycle
that is simultaneously capture-capable and error-quarantined.

Consumers receive at-least-once delivery. The wrapper prevents its own offset
acknowledgement after an anomaly, but consumers must tolerate replay after
partially completed external side effects.

Applications must configure an `OffsetBackingStore`; the library no longer
defaults to in-memory offsets. `MemoryOffsetBackingStore` is an explicit test
fixture, not a public library default or recovery mechanism.

Successful `stop!` means that the wrapper observed graceful termination and
all admitted batches were acknowledged. It does not certify durable offset
persistence: Debezium can suppress an `OffsetBackingStore.stop` failure before
it reaches the completion callback. The configured store is therefore an
explicit external precondition, and observable failures remain anomalies.

The optional event hook is asynchronous best-effort observability. Its bounded
delivery queue may discard an event rather than delay capture, acknowledgement,
or shutdown; a hook cannot execute on the lifecycle linearizer.

A rejected upstream shutdown leaves the handle in `stopping`, not falsely
`stopped`. The wrapper retries at a safe lifecycle boundary and reports an
unconfirmed shutdown at its deadline. Until completion is observed, a fresh
replacement handle must not be started locally.

## Alternatives considered

### Store a separate `trusted` / `quarantined` state

`trusted` describes an unsupported judgement about Debezium, while
`quarantined` describes a wrapper response. They are neither complementary
facts nor a coherent lifecycle axis. Storing them with a phase also admits
contradictory combinations.

### Preserve direct engine execution

Allowing callers to execute or close a raw Debezium engine bypasses the
observation and acknowledgement linearizer. A compatibility adapter would
retain that bypass, so the lifecycle guarantee requires an explicit migration
to wrapper-owned `start!` and `stop!` operations.

### Depend on `AsyncEmbeddedEngine` directly

This would couple the wrapper to an implementation class and still would not
provide a public running-state API.

### Remove `running?`

This would avoid wrapper-owned lifecycle interpretation, but it would break an
existing Clojure API without giving applications an equivalent contract.

### Update the two Debezium artifacts independently

This does not address the lifecycle contract, and mixed Debezium versions are
not binary compatible.
