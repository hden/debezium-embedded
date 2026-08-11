# Implement the Change-Capture Lifecycle Contract

**Goal:** ship a Debezium 3.6.1.Final wrapper whose lifecycle decisions remain
owned and verifiable by this library.

**Non-goals:** prove offset-store durability, expose a raw `DebeziumEngine`,
or accept upstream callbacks as valid merely because Debezium emitted them.

## 1. Upgrade the compatible Debezium pair

- Update `debezium-embedded` and the PostgreSQL connector together to
  `3.6.1.Final` in `project.clj`.
- Raise the library version to the next major version because callers now use
  the wrapper-owned `start!` and `stop!` lifecycle.

## 2. Keep one source of lifecycle truth

- Store an append-only observation trace per handle.
- Derive phase, record admission, terminal anomaly, and graceful completion
  only from that trace in `debezium-embedded.lifecycle`.
- Record raw upstream callbacks even when their order is invalid; append a
  derived protocol anomaly and never restore admission from that fact.

## 3. Bind every external effect to an observed start

- Record `engine-submission-started` before giving a wrapper runnable to the
  executor. The runnable records `engine-invocation-started` immediately before
  entering the raw engine, or `engine-invocation-cancelled` after a stop wins.
- Record `shutdown-request-started` before calling `Closeable.close`. Do not
  begin shutdown once completion has already been observed.
- Record `record-acknowledgement-started` or
  `batch-acknowledgement-started` before each committer call, only while the
  pure trace interpretation admits records.
- Record the return or anomaly from each started external effect separately.
  A barrier can prevent a later effect from starting; it cannot retract an I/O
  call that already began.

## 4. Translate the upstream boundary

- Build `DebeziumEngine` privately with translated consumer, completion, and
  connector callbacks.
- Expose an opaque `Closeable` handle and wrapper commands only:

  ```clojure
  (create-engine {::config config
                  ::consumer consume-events
                  ::default-shutdown-timeout-ms 2000
                  ::on-event observe-event})
  (start! handle {})
  (start! handle {:executor executor})
  (stop! handle {})
  (stop! handle {:timeout-ms timeout-ms})
  (running? handle)
  ```

- Translate failures at this boundary into Cognitect Anomalies maps. Retain an
  upstream cause as context and prefer no further acknowledgement after an
  anomaly.

## 5. Confirm shutdown safely

- `stop!` waits 2000 milliseconds by default, or uses `:timeout-ms` for that
  call.
- A rejected shutdown while starting may retry once when the first
  `polling-started` callback subsequently arrives. Other duplicate callbacks
  remain protocol anomalies and do not retry shutdown.
- Successful completion or cancellation fixes the terminal result. Later
  upstream outcomes are diagnostic observations, not a replacement failure
  result.

## 6. Verify the contract at three levels

- Clojure unit tests cover normal traces, malformed upstream callback orders,
  queued executor cancellation, acknowledgement barriers, and completion races.
- TLC checks the finite lifecycle quotient in `tla/`; generated states and
  traces remain ignored.
- Run formatting, whitespace, and the complete test suite before pushing.

```bash
standard-clj check src test
lein test
cd tla && java -cp /private/tmp/tla2tools.jar tlc2.TLC -deadlock \
  -config EngineLifecycle.cfg EngineLifecycle.tla
git diff --check
```
