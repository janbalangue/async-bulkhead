# Changelog

All notable, user-visible changes to runtime behavior and public APIs
will be documented in this file.

Until version `1.0.0`, all releases are considered **pre-stable** and may
introduce breaking changes. Such changes will be documented clearly.

---

## [Unreleased]

### Added
- Defined core semantics, guarantees, and non-goals for the async bulkhead

---

## [0.1.0] — TBD

### Added
- Async bulkhead core implementation
- Concurrency limit enforcing a maximum number of in-flight async tasks
- Fast rejection when capacity is exhausted
- Metrics listener interface for observing bulkhead behavior
- Tests enforcing documented concurrency and overload semantics

---

## [0.2.0] — TBD

### Added
- Bounded FIFO queue for waiting async tasks
- Cancellation handling for queued tasks
- Fairness guarantees for queued task execution (best-effort)

---

## [0.3.0] — TBD

### Added
- Queue wait timeouts to bound pre-execution latency under load
- Explicit exception for queue wait timeout
