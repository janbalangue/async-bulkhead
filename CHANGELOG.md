# Changelog

All notable, user-visible changes to runtime behavior and public APIs
are documented in this file.

Until version `1.0.0`, all releases are **pre-stable** and may introduce
breaking changes. Any such changes will be explicitly documented.

---

## [Unreleased]

*No unreleased user-visible changes.*

---

## [0.1.1] - 2025-12-30

### Fixed

* Maven build and publishing metadata corrections
* Minor test assertion compatibility fixes
* Documentation formatting cleanup

## [0.1.0] — 2025-12-30

### Added

* Async bulkhead with a fixed upper bound on **in-flight async tasks**
* Fail-fast rejection when capacity is exhausted (no waiting or queuing)
* Explicit rejection signaling via `BulkheadRejectedException`
* Guaranteed **non-invocation of suppliers** for rejected submissions
* Permit release on **all terminal outcomes** of the returned `CompletionStage`
  (success, exceptional completion, or cancellation)
* Deterministic, concurrency-focused tests enforcing all documented semantics
