# Coverage and CRAP quality gate

## Goal

Add reproducible test-coverage and CRAP (Change Risk Anti-Pattern) analysis
to this Leiningen project. The same quality gate must run locally before a
push and in CircleCI.

## Scope

- Generate Cloverage HTML and LCOV coverage reports from `src` and `test`.
- Run the pinned `crap4clj` analyzer against the generated LCOV report.
- Fail the quality gate when any analyzed function has a CRAP score of 30 or
  higher.
- Add documented commands for coverage, CRAP analysis, and the combined
  quality gate.
- Version a `pre-push` hook and document the one-time Git configuration that
  enables it.
- Run the combined quality gate in CircleCI after the existing test command.

## Design

Leiningen remains the test and coverage runner. A project-local script invokes
`lein cloverage` with LCOV output, then invokes a pinned `crap4clj` Clojure CLI
alias with `--use-existing-coverage`. The script parses the analyzer's report
and exits non-zero when a score is at least 30.

`make coverage`, `make crap`, and `make quality` provide the public developer
commands. `make quality` is the shared entry point for CircleCI and the
versioned `.githooks/pre-push` hook, so local and CI enforcement cannot drift.

The hook is opt-in through `git config core.hooksPath .githooks`; this avoids
silently replacing each contributor's existing local hook configuration.

## Acceptance criteria

- `make coverage` creates Cloverage coverage output, including LCOV data.
- `make crap` prints a function-level CRAP report based on that coverage data.
- `make quality` fails if a reported CRAP score is 30 or higher, and succeeds
  otherwise.
- CircleCI runs `make quality` after `lein test`.
- `.githooks/pre-push` runs `make quality` and blocks a push on failure.
- The README documents the commands, threshold, and hook setup.

## Non-goals

- No repository-wide minimum percentage coverage threshold is introduced in
  this change.
- Existing source or test behavior is not refactored solely to lower CRAP.
- No global Git configuration is modified automatically.
