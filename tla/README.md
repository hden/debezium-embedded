# Engine lifecycle model

`EngineLifecycle.tla` models wrapper-owned safety decisions, not Debezium's
private engine state and not the unbounded observation log. Its state is a
small quotient: interpreted phase, engine invocation and shutdown boundaries,
normal-protocol facts, one abstract batch, completion outcome, and the raw
kind of the first callback whose interpretation is rejected. Histories with
the same future admission and shutdown consequences are deliberately one TLC
state.

The Clojure runtime derives the same finite projection by reducing its
append-only observations through an interpretation table. It does not need
`core.match`: the table is the implementation counterpart of this model's
transition relation, so adding a control-flow dependency would not express an
additional domain rule.

Every Debezium callback is an input. A callback that cannot extend the normal
protocol records its raw kind and protocol anomaly atomically, then reaches a
non-capturing state. Subsequent malformed callbacks are omitted from the
quotient because they cannot restore admission; the Clojure implementation
still appends every raw observation for diagnosis.

The model checks safety only: capture has the normal facts, an admission
barrier cannot be reversed, a queued engine invocation cannot begin after the
stop boundary, an acknowledgement starts while capturing, a shutdown starts
before completion, malformed input retains its raw kind, and graceful
confirmation has its observable evidence. It deliberately does not prove
liveness, ordering/durability of Debezium's offset store, the application
consumer's side effects, or external committer I/O after it starts.

Run TLC with a downloaded `tla2tools.jar`:

```sh
cd tla
java -cp /path/to/tla2tools.jar tlc2.TLC -deadlock -config EngineLifecycle.cfg EngineLifecycle.tla
```

`-deadlock` is intentional: the model has terminal traces and specifies safety
properties only.
