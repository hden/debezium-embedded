# Plan Attack Surface

When reviewing plans or designs, evaluate them against these vectors to identify "showstopper" risks:

## 1. Goal Mismatch
- Does the plan align with the user's ultimate goal?
- Does it address the root cause or just surface symptoms?

## 2. Missing or Weakly Supported Assumptions
- Is the design based on unverified assumptions (e.g., "this should work," "I assume X is true")?
- What happens if these assumptions are proven wrong?

## 3. Violated Invariants & Conventions
- Does it break existing system rules, architectural principles, or data consistency?
- Does it violate repository-specific mandates found in files like `GEMINI.md`, `.cursorrules`, `CODE_GUIDELINES.md`, or `README.md`?

## 4. Incomplete Repository Investigation
- Are there gaps in identifying the impact area?
- Does it ignore relevant tests, documentation, or upstream/downstream dependencies?

## 5. Hidden Coupling and Affected Callers
- Are there side effects in seemingly unrelated areas?
- Has the impact on all callers and dependents (including dynamic ones or reflection) been assessed?

## 6. Failure Paths, Concurrency, and Partial State
- Can the system be left in an inconsistent or partial state on failure?
- Are retries, timeouts, race conditions, and concurrency issues addressed?

## 7. Compatibility, Migration, and Rollback Hazards
- Does the plan introduce **unintended** breaking changes or compatibility regressions?
- For **intended** breaking changes, is the migration path clearly defined and are all affected dependents accounted for?
- Is there a safe rollback strategy that prevents data corruption or permanent inconsistent state?

## 8. Missing Executable Validation
- Is there a concrete, empirical way to prove the plan is correct (e.g., a specific test case)?
- Are automated tests or reproduction scripts part of the validation phase?

## 9. Structural Complexity (Complecting)
- Does the design **complect** (intertwine/braid) unrelated concerns, state, or responsibilities?
- Beware of solutions that are merely **"Easy"** (familiar or convenient in the short term) but increase **Complexity** by entangling logic.
- Prioritize **Simplicity** (unentangled, single-purpose, composable components) even if it requires more initial effort than the "easy" path.
