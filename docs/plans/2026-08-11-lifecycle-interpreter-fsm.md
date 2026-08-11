# Finite Lifecycle Interpretation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:executing-plans` to execute this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** make lifecycle interpretation a readable, deterministic finite
projection without changing the public lifecycle contract.

**Architecture:** retain the append-only observation trace as the only mutable
input. Replace its two overlapping branch systems with one private projection
and an observation-interpretation table keyed by interpreted phase and
observation kind. Each table entry returns a new projection; an absent or
rejected entry appends the existing derived protocol anomaly. `phase`, record
admission, terminal anomaly, and graceful completion read the folded
projection. No lifecycle state is independently stored or managed.

**Tech Stack:** Clojure 1.12, `clojure.test`, standard-clj, TLC. No new runtime
dependency; in particular, do not add `core.match`.

## Global Constraints

- Preserve every public function, map shape, observation keyword, and
  `stop!` result.
- Preserve raw invalid callbacks followed by a derived protocol anomaly.
- Keep lifecycle interpretation private and derived only by folding the trace.
- Match the finite interpretation variables and permitted transitions in
  `tla/EngineLifecycle.tla`.
- Do not add a dependency merely to express control flow.

---

### Task 1: Freeze externally observable interpreter behaviour

**Files:**

- Modify: `test/debezium_embedded/lifecycle_test.clj`

**Produces:** table-driven characterisation cases for trace → phase, derived
protocol anomaly, terminal anomaly, and graceful-completion result.

- [ ] **Step 1: Add literal trace cases for every normal phase transition**

  Include `ready → starting → capturing → stopping → stopped`, including the
  normal shutdown suffix below.

  ```clojure
  [::lifecycle/start-requested
   ::lifecycle/engine-submission-started
   ::lifecycle/engine-invocation-started
   ::lifecycle/connector-started
   ::lifecycle/polling-started
   ::lifecycle/stop-requested
   ::lifecycle/polling-stopped
   ::lifecycle/connector-stopped
   ::lifecycle/completion-observed]
  ```

- [ ] **Step 2: Add literal invalid-callback cases**

  Cover duplicate and reordered connector/polling callbacks, completion from
  `capturing`, and every callback after `stopped`. Assert that the raw fact is
  retained and exactly one protocol anomaly immediately follows it.

- [ ] **Step 3: Add terminal-result cases**

  Cover failed completion, successful completion followed by a late anomaly,
  cancellation followed by a submission rejection, and the graceful trace.

- [ ] **Step 4: Run the interpreter tests before refactoring**

  Run: `lein test :only debezium-embedded.lifecycle-test`

  Expected: all characterisation cases pass against the current interpreter.

- [ ] **Step 5: Commit the test-only baseline**

  ```sh
  git add test/debezium_embedded/lifecycle_test.clj
  git commit -m "test: characterize lifecycle interpreter traces"
  ```

### Task 2: Introduce an explicit private interpretation projection

**Files:**

- Modify: `src/debezium_embedded/lifecycle.clj`
- Test: `test/debezium_embedded/lifecycle_test.clj`

**Consumes:** literal trace cases from Task 1.

**Produces:** private `initial-projection` and `interpret` values. They contain
only the finite facts that alter a later wrapper decision:

```clojure
{:phase       ::ready
 :engine      ::not-invoked | ::invoked | ::cancelled | ::rejected
 :connector   ::not-started | ::started | ::stopped
 :polling     ::not-started | ::started | ::stopped
 :shutdown    {:requests 0 :failed? false}
 :completion  ::pending | ::succeeded | ::failed
 :protocol?   false
 :batches     {:admitted 0 :acknowledged 0}}
```

- [ ] **Step 1: Write a failing private-projection test**

  Fold the normal shutdown trace and assert the hand-written terminal state:

  ```clojure
  {:phase      ::lifecycle/stopped
   :engine     ::lifecycle/invoked
   :connector  ::lifecycle/stopped
   :polling    ::lifecycle/stopped
   :completion ::lifecycle/succeeded}
  ```

- [ ] **Step 2: Run the focused test**

  Run: `lein test :only debezium-embedded.lifecycle-test/interprets-normal-shutdown`

  Expected: FAIL because `interpret` does not yet exist.

- [ ] **Step 3: Add `initial-projection` and `interpret`**

  Fold observations into a private value only. Do not modify public functions
  yet. Derive phase from the projection; do not retain a mutable phase.

- [ ] **Step 4: Run the focused test**

  Expected: PASS.

- [ ] **Step 5: Commit the projection introduction**

  ```sh
  git add src/debezium_embedded/lifecycle.clj test/debezium_embedded/lifecycle_test.clj
  git commit -m "refactor: introduce lifecycle interpretation"
  ```

### Task 3: Make the observation-interpretation table the only decision point

**Files:**

- Modify: `src/debezium_embedded/lifecycle.clj`
- Test: `test/debezium_embedded/lifecycle_test.clj`

**Consumes:** `interpret` from Task 2.

**Produces:** a private interpretation table and one `interpret-observation`
function:

```clojure
(interpret-observation projection observation)
;; => a new projection, whose :protocol? records rejection when applicable
```

- [ ] **Step 1: Write a failing observation-interpretation test**

  Assert the first `polling-stopped` from `stopping` produces
  `{:polling ::stopped :phase ::stopping}` and a second one marks protocol
  rejection.

- [ ] **Step 2: Run the focused test**

  Run: `lein test :only debezium-embedded.lifecycle-test/polling-stop-interpretation`

  Expected: FAIL while the old branch system remains.

- [ ] **Step 3: Implement the table and `interpret-observation`**

  Key the table by `[(:phase projection) observation-kind]`. Each recognised
  entry is a small named pure projection update. Its guard reads explicit
  projection fields; it does not rescan observations. Unknown events and
  rejected guards use the existing protocol-anomaly path.

- [ ] **Step 4: Replace both old branch systems**

  Delete `next-phase`, `protocol-violation?`, and `phase-and-seen-kinds`.
  Make `phase` call `(:phase (interpret observations))`, and make
  `append-observation` ask `interpret-observation` whether to append the
  derived anomaly.

- [ ] **Step 5: Run all interpreter tests**

  Run: `lein test :only debezium-embedded.lifecycle-test`

  Expected: all Task 1 characterisation cases pass unchanged.

- [ ] **Step 6: Commit the finite-state-machine replacement**

  ```sh
  git add src/debezium_embedded/lifecycle.clj test/debezium_embedded/lifecycle_test.clj
  git commit -m "refactor: centralize lifecycle transitions"
  ```

### Task 4: Derive terminal and graceful results from the interpreter

**Files:**

- Modify: `src/debezium_embedded/lifecycle.clj`
- Test: `test/debezium_embedded/lifecycle_test.clj`

**Consumes:** the completed observation-interpretation table from Task 3.

**Produces:** `terminal-anomaly` and `graceful-completion?` that use one
interpreter fold and explicit aggregate evidence instead of independent trace
walks.

- [ ] **Step 1: Run terminal-result characterisation tests**

  Run: `lein test :only debezium-embedded.lifecycle-test`

  Expected: their expected terminal values are already pinned by Task 1.

- [ ] **Step 2: Replace independent rescans**

  Use the interpreter's terminal outcome and batch/connector evidence. Retain
  the original anomaly map and preserve the rule that anomalies after a
  successful completion or cancellation are diagnostic.

- [ ] **Step 3: Run all interpreter tests**

  Expected: PASS.

- [ ] **Step 4: Commit the derived-result simplification**

  ```sh
  git add src/debezium_embedded/lifecycle.clj test/debezium_embedded/lifecycle_test.clj
  git commit -m "refactor: derive lifecycle terminal results once"
  ```

### Task 5: Align the executable model and verify regression safety

**Files:**

- Modify: `tla/EngineLifecycle.tla`
- Modify: `tla/README.md`
- Modify: `CHANGELOG.md`

**Produces:** a model whose variable names and transition descriptions match
the Clojure interpreter, without changing the promised trace semantics.

- [ ] **Step 1: Rename only model variables that no longer describe the FSM**

  Keep the finite quotient and its invariants. Do not add concrete trace
  retention or Debezium-private implementation state to TLC.

- [ ] **Step 2: Document the no-`core.match` choice**

  State that the Clojure observation-interpretation table is the implementation
  counterpart to the TLA+ transition relation.

- [ ] **Step 3: Run complete verification**

  ```sh
  standard-clj check src test
  lein test
  cd tla && java -cp /private/tmp/tla2tools.jar tlc2.TLC -deadlock \
    -config EngineLifecycle.cfg EngineLifecycle.tla
  git diff --check
  ```

- [ ] **Step 4: Commit verification alignment**

  ```sh
  git add tla/EngineLifecycle.tla tla/README.md CHANGELOG.md
  git commit -m "docs: align lifecycle model with interpreter"
  ```
