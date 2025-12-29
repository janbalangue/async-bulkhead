# Changelog

All notable, user-visible changes to runtime behavior and public APIs
are documented in this file.

Until version `1.0.0`, all releases are considered **pre-stable** and may
introduce breaking changes. Such changes will be documented clearly.

---

## [Unreleased]

### Added
- Defined explicit async bulkhead semantics in `DESIGN.md`
- Fail-fast saturation behavior with no waiting
- Explicit `BulkheadRejectedException` for saturation failures
- Guaranteed supplier non-invocation when submissions are rejected
- Permit release guarantees on task completion (success, failure, cancellation)
- Test-backed enforcement of concurrency and overload semantics

---

## [0.1.0] — TBD

### Added
- Core async bulkhead implementation
- Concurrency limit enforcing a maximum number of in-flight async tasks
- Immediate rejection when capacity is exhausted
- Explicit rejection signaling via `BulkheadRejectedException`
- Deterministic tests locking bulkhead acceptance, rejection, and permit-release behavior

---

## [0.2.0] — TBD (planned)

### Added
- Bounded FIFO queue for waiting submissions
- Best-effort fairness guarantees for queued tasks
- Cancellation handling for queued submissions

---

## [0.3.0] — TBD (planned)

### Added
- Queue wait timeouts to bound pre-execution latency
- Explicit exception type for queue wait timeout failures
