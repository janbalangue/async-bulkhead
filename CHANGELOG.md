# Changelog

All notable, user-visible changes to runtime behavior and public APIs
are documented in this file.

Until version `1.0.0`, all releases are **pre-stable** and may introduce
breaking changes. Any such changes will be explicitly documented.

---

## [Unreleased]

*No unreleased user-visible changes.*

---

## [0.1.5] - 2025-12-31

### Fixed
- Release workflow: corrected `GPG_PRIVATE_KEY` environment scoping so the key imports correctly in CI and signatures (`.asc`) are produced.
- Release signing: ensured `.asc` signature artifacts are generated as part of the release build (via the `release` profile).

### Build
- Minor CI/release reliability improvements around non-interactive GPG usage.


## [0.1.4] - 2025-12-30

### Fixed
- Javadoc generation: corrected HTML issues and improved doc consistency.

### Changed
- Release build: attach Javadoc JARs and sources JARs for Maven Central.
- Signing: moved GPG signing into the `release` profile to avoid local/dev build friction.

### Documentation
- Clarified overload/rejection semantics and usage examples.

## [0.1.3] – 2025-12-30

### Fixed
* Corrected Maven Central publishing configuration (release deployment no longer depends on `distributionManagement`; no API or semantic changes).

### Build
* Added a tag-gated release workflow suitable for CI publishing.

## [0.1.2] - 2025-12-30
### Fixed

* Maven Central publishing readiness fixes (GPG signing, metadata, staging)
* Parent / module POM alignment and groupId consistency
* Test stability under high concurrency (permit release & rejection assertions)

### Documentation
* Clarified async permit lifecycle and rejection semantics
* Minor README and formatting cleanups

## [0.1.1] - 2025-12-30

### Fixed

* Maven build and publishing metadata corrections
* Minor test assertion compatibility fixes
* Documentation formatting cleanup

## [0.1.0] — 2025-12-30

### Added

* Async bulkhead with a fixed upper bound on **in-flight async operations**
* Fail-fast rejection when capacity is exhausted (no waiting or queuing)
* Explicit rejection signaling via `BulkheadRejectedException`
* Guaranteed **non-invocation of suppliers** for rejected submissions
* Permit release on **all terminal outcomes** of the returned `CompletionStage`
  (success, exceptional completion, or cancellation)
* Deterministic, concurrency-focused tests enforcing all documented semantics
