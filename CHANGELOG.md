# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Make `start!` synchronous: it returns only after Debezium reports polling
  started or completion, and returns an anomaly when polling did not start.

### Fixed

- Defer shutdown requested during task startup until Debezium reports polling
  started, avoiding the upstream's unsafe `close()` window.

## [4.0.0] - 2026-08-11

### Added

- Wrapper-owned `start!` and synchronous `stop!` lifecycle operations.
- `::on-event` for translated, best-effort lifecycle observations.
- `latest-event` and `polling?` as building blocks for application-owned
  readiness and liveness policies.

### Changed

- Upgrade Debezium Embedded and the PostgreSQL connector together to
  `3.6.1.Final`.
- Require explicit Debezium `:offset.storage` configuration; the library no
  longer selects an offset store.
- Delegate lifecycle interpretation and graceful-shutdown semantics to
  Debezium and the application. The wrapper retains only the latest Debezium
  callback fact.

### Removed

- Direct execution of the value returned from `create-engine`.
- Exposure of raw Debezium engine, completion callback, and connector callback
  APIs.
- `running?`; use `polling?` when a conservative callback-based readiness
  signal is appropriate.

### Fixed

- Do not acknowledge a batch when its consumer fails.

[4.0.0]: https://github.com/hden/debezium-embedded/compare/v3.1.0...v4.0.0
[Unreleased]: https://github.com/hden/debezium-embedded/compare/v4.0.0...HEAD
