# Implement the Change-Capture Lifecycle Contract

> **For implementers:** execute each checkbox in order. A checkbox is complete
> only after its stated test has passed.

**Goal:** preserve a wrapper-owned, verifiable change-capture lifecycle while
upgrading both Debezium artifacts to 3.6.1.Final.

**Architecture:** `debezium-embedded.core` exposes an opaque `Closeable`
handle and owns every interaction with Debezium. Its only lifecycle input is
an append-only observation trace. A pure interpreter derives phase, record
admission, and the result of `stop!`; the effectful adapter appends facts and
performs the corresponding Debezium call. The raw engine, its callbacks,
records, and committer remain implementation details.

**Tech stack:** Clojure 1.12, Leiningen, Debezium Engine 3.6.1.Final, Kafka
Connect `OffsetBackingStore`, `clojure.test` (plus `test.check` for generated
trace tests).

**Non-goals:** do not retain a raw `DebeziumEngine` compatibility adapter; do
not provide an implicit in-memory offset store; do not claim to verify durable
offset persistence that Debezium itself does not report.

## 1. Upgrade the compatible Debezium pair

**Files:**

- Modify: `project.clj`

- [ ] Change `io.debezium/debezium-embedded` and the dev
  `io.debezium/debezium-connector-postgres` dependency together from
  `3.1.0.Final` to `3.6.1.Final`.
- [ ] Add `org.clojure/test.check` only to the test profile for generated
  interpreter tests.

**Verify:**

```bash
lein test
```

Expected: compilation now fails only at legacy `EmbeddedEngine` references;
there must be no mixed Debezium 3.1 / 3.6 dependency left.

## 2. Specify the pure trace interpreter with tests first

**Files:**

- Create: `src/debezium_embedded/lifecycle.clj`
- Create: `test/debezium_embedded/lifecycle_test.clj`

- [ ] In the test file, first write direct examples for every normal phase
  transition and terminal outcome in the state diagram.
- [ ] Add generated finite traces over all recognised observations. Assert:
  (a) `capturing` occurs only at the normal admissible prefix; (b) after a
  stop condition, completion, or anomaly, no suffix returns to `capturing`;
  and (c) phase, admission, and confirmation are deterministic functions of
  the same trace.
- [ ] Add examples for duplicate, reordered, and post-completion callbacks;
  each must retain the raw fact and derive a protocol anomaly without restoring
  admission.
- [ ] Add graceful-confirmation examples: no-start stop; submission failure;
  a fully acknowledged normal shutdown; an unacknowledged batch; a missing
  `connector-stopped`; and the permitted startup-stop trace with
  `polling-started` but no `polling-stopped`.
- [ ] Implement only pure values and functions in `lifecycle.clj`: append an
  observation, derive phase, determine admissibility, retain the first
  anomaly, detect protocol violations, and determine the wrapper-observable
  `stop!` result. Do not import Debezium here.

**Verify:**

```bash
lein test :only debezium-embedded.lifecycle-test
```

Expected: trace tests demonstrate the structural invariant without an engine,
thread, or timing-dependent assertion.

## 3. Build the opaque handle around the interpreter

**Files:**

- Modify: `src/debezium_embedded/core.clj`
- Modify: `test/debezium_embedded/core_test.clj`

- [ ] Replace all `EmbeddedEngine` imports and builder use with
  `DebeziumEngine.create(ChangeEventFormat/of Connect)`. Keep the resulting
  engine private inside a wrapper-owned handle.
- [ ] Define the public contract exactly as follows:

  ```clojure
  (create-engine {::config config
                  ::consumer consume-events
                  ::default-shutdown-timeout-ms 2000 ; optional
                  ::on-event observe-event}) ; optional
  (start! handle {})
  (start! handle {:executor executor})
  (stop! handle {})
  (stop! handle {:timeout-ms timeout-ms})
  (running? handle)
  ```

  `create-engine` returns either an opaque handle or a Cognitect anomaly map.
  Reject absent `::config`, `::consumer`, or `:offset.storage` with
  `:cognitect.anomalies/incorrect`; do not merge a memory-store default.
- [ ] Make the handle implement `java.io.Closeable`. Its `close` delegates to
  `(stop! handle {})`, returning normally for nil and throwing `ex-info` whose
  data is the anomaly at the Java boundary only.
- [ ] Linearize appending a lifecycle observation with public start/stop
  commands, engine submission, consumer invocation, and committer invocation.
  Runtime references such as the private engine and submitted future are not
  lifecycle inputs; all lifecycle conclusions must be read from the trace.
- [ ] Have `start!` reject duplicate or terminal starts as an `:incorrect`
  anomaly, append `start-requested`, then append exactly one of
  `run-submitted`, `run-submission-anomaly`, or `run-cancelled`.
- [ ] Have `stop!` append `stop-requested`, cancel an unsubmitted invocation,
  request raw-engine shutdown otherwise, and wait for the configured deadline.
  A close rejection while starting records `shutdown-anomaly` and schedules
  exactly one retry at `polling-started`; a later explicit `stop!` permits one
  additional serialized attempt. Deadline expiry appends
  `shutdown-unconfirmed`; it leaves the handle `stopping`.
- [ ] Translate every Debezium or wrapper exception at the boundary into a
  Cognitect anomaly map, preserving an existing `ExceptionInfo` anomaly and
  retaining the source and cause. The mapping is: interrupted →
  `:cognitect.anomalies/interrupted`, recognised transient connection failure
  → `:cognitect.anomalies/unavailable`, invalid command →
  `:cognitect.anomalies/incorrect`, all other upstream/protocol failures →
  `:cognitect.anomalies/fault`.

**Verify:**

```bash
lein test :only debezium-embedded.core-test/factories
```

Expected: public construction validates the new configuration map and never
returns a raw Debezium type.

## 4. Translate upstream callbacks and protect acknowledgement order

**Files:**

- Modify: `src/debezium_embedded/core.clj`
- Modify: `test/debezium_embedded/core_test.clj`

- [ ] Adapt Debezium completion and connector callbacks into observations:
  connector/polling start and stop, completion, and translated completion
  anomaly. Do not expose either original callback option or raw arguments.
- [ ] Adapt batches into the existing event-map shape. Before calling the
  application consumer, derive admission from the trace. Record
  `batch-admitted`, then `batch-handled` or `consumer-anomaly`.
- [ ] Before every `markProcessed` and `markBatchFinished`, append the matching
  acknowledgement-attempt fact and re-check admission. Append its returned or
  anomaly fact. After the first anomaly, no acknowledgement attempt may occur.
- [ ] Test with a controlled internal Debezium adapter/committer seam rather
  than a live connector: consumer exception, record acknowledgement exception,
  batch acknowledgement exception, a callback after completion, and a stop
  racing a pending batch. Assert the resulting trace and external-call order.

**Verify:**

```bash
lein test :only debezium-embedded.core-test
```

Expected: no consumer or acknowledgement call is made after the first
non-admissible observation, and every exposed error is an anomaly map.

## 5. Isolate the observability hook

**Files:**

- Modify: `src/debezium_embedded/core.clj`
- Modify: `test/debezium_embedded/core_test.clj`

- [ ] Emit fully qualified wrapper event maps after recording observations and
  derived phase changes. Use an implementation-owned bounded FIFO dispatcher
  with a non-blocking rejection policy; preserve order among events actually
  delivered.
- [ ] Never execute `::on-event` while holding the trace linearization lock.
  Discard a notification when the queue is full; discard a hook exception.
- [ ] Test a permanently blocking hook, a throwing hook, and a hook which
  calls `stop!`. In all cases, assert that the lifecycle command and completion
  continue, while the hook is unable to alter consumer or acknowledgement
  outcomes.

**Verify:**

```bash
lein test :only debezium-embedded.core-test
```

Expected: hook behaviour is observability-only and cannot stall the control or
data paths.

## 6. Make the offset-store and integration contract explicit

**Files:**

- Modify: `test/debezium_embedded/core_test.clj`
- Modify: `README.md`

- [ ] Keep a private `MemoryOffsetBackingStore` configuration fixture in the
  `debezium-embedded.core-test` namespace for ephemeral integration tests. Do
  not add it to the library namespace or public API.
- [ ] Convert the PostgreSQL integration test from raw `.execute` to `start!`
  and from implicit close to `stop!`; assert the observable
  `ready → starting → capturing → stopping → stopped` sequence.
- [ ] Update README for Debezium 3.6.1.Final and show `create-engine`,
  `start!`, and synchronous `stop!` within `with-open`. State that callers
  supply a production `OffsetBackingStore`, while the in-memory example is
  only ephemeral.

**Verify:**

```bash
lein test
```

Expected: unit and integration tests pass against a single 3.6.1.Final
Debezium version pair.

## 7. Final consistency and regression checks

**Files:**

- Review: `docs/adr/0001-preserve-engine-lifecycle-contract.md`
- Review: `docs/specs/2026-08-11-engine-lifecycle-contract.md`
- Review: `README.md`, `project.clj`, `src/debezium_embedded/core.clj`, and
  tests

- [ ] Confirm the README exposes no raw engine execution, completion callback,
  or connector callback.
- [ ] Confirm production source has no `EmbeddedEngine` import or implicit
  `MemoryOffsetBackingStore` configuration.
- [ ] Confirm every public map key has the documented qualification convention,
  and no public error path leaks a raw Debezium exception.
- [ ] Run formatting and whitespace checks before the full test suite.

**Verify:**

```bash
standard-clj check src test
git diff --check
lein test
```

Expected: formatted Clojure, no whitespace errors, and a green suite. Manual
CircleCI inspection is the final external verification after the branch is
pushed.
