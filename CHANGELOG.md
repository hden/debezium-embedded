# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Accept the first `polling-stopped` callback after `stop-requested` as a
  normal graceful-shutdown callback; repeated callbacks remain anomalies.
- Reject reordered or duplicate connector and polling callbacks while retaining
  each raw observation and its protocol anomaly for diagnosis.

### Changed

- Consolidate lifecycle interpretation into one finite projection of observed
  facts without changing the public API.

## [4.0.0] - 2026-08-11

### Added

- Wrapper-owned `start!` and synchronous `stop!` lifecycle operations.
- `::on-event` for translated, best-effort lifecycle observations.
- Lifecycle and acknowledgement tests for shutdown safety.
- A TLC-checked finite model of the wrapper lifecycle decisions.

### Changed

- Upgrade Debezium Embedded and the PostgreSQL connector together to
  `3.6.1.Final`.
- Define `running?` as active capture between wrapper-observed lifecycle
  boundaries.
- Require explicit Debezium `:offset.storage` configuration; the library no
  longer selects an offset store.

### Removed

- Direct execution of the value returned from `create-engine`.
- Exposure of raw Debezium engine, completion callback, and connector callback
  APIs.

### Fixed

- Prevent acknowledgement after consumer or acknowledgement failure.
- Retry a startup-time shutdown rejection once after polling begins.
- Prevent a queued engine invocation or a shutdown request from starting after
  its lifecycle boundary has closed.
- Keep a successful completion or cancelled invocation as the terminal result
  when a later upstream outcome arrives.

[Unreleased]: https://github.com/hden/debezium-embedded/compare/v4.0.0...HEAD
[4.0.0]: https://github.com/hden/debezium-embedded/compare/v3.1.0...v4.0.0
