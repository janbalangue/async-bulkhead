# Changelog

All notable, user-visible changes to runtime behavior and public APIs
are documented in this file.

Until version `1.0.0`, all releases are **pre-stable** and may introduce
breaking changes. Any such changes will be explicitly documented.

---

## [Unreleased]

### Build / CI
- Hardened release workflow with:
  - strict tag ↔ POM version validation
  - explicit preflight `verify` before deployment
  - concurrency protection to prevent double-publishing
  - safer non-interactive GPG key handling
- Clarified separation of concerns between CI and release workflows:
  - CI runs `mvn verify` only and does not require secrets or signing
  - Release workflow exclusively owns signing and Maven Central deployment

---

## Publication note

Some early 0.1.x versions were affected by cancelled or incomplete Maven Central publication attempts.
Because Maven Central does not allow reuse of version numbers once a deployment is cancelled or rejected,
those versions should be considered **non-canonical**.

The first fully published and supported release series begins with 0.1.7.

---

## [0.2.0] - 2026-01-03

### Changed
- Defined **cancellation** of the returned `CompletionStage` as a **terminal outcome** that releases capacity.
- Defined **permit lifetime** strictly from successful admission until the returned `CompletionStage` reaches a
  terminal state (success, exceptional completion, or cancellation).
- Guaranteed **exactly-once permit release** across all races between completion, exceptional completion, and
  cancellation.
- Tightened admission semantics: capacity is acquired **only at the moment of submission**; there is no deferred,
  waiting, or speculative admission.

### Clarified
- Formalized the core invariant: *in-flight means admitted until terminal*.
- Clarified that the returned `CompletionStage` is the **sole authority** for permit lifetime.
- Clarified that rejection is a **normal control signal** representing intentional load shedding under saturation.
- Clarified that `BulkheadRejectedException` indicates **capacity exhausted and no work started**.

### Documentation
- Significantly expanded and refined **DESIGN.md** to explicitly define semantics, invariants, races, cancellation
  behavior, and non-goals.
- Pruned and refocused **README.md** to serve as a high-level entry point aligned with v0.2 semantics.
- Added explicit **production guidance and failure modes** documentation.

### Compatibility
- **Pre-1.0**: behavior relying on previously undefined or implicit cancellation behavior may observe changes.
- No API surface changes; runtime behavior is now explicitly defined and test-backed.


---

## [0.1.7] - 2025-12-31

### Fixed
- Maven Central publishing reliability and version alignment.

### Build
- Release workflow stabilization; no runtime or semantic changes.

### Documentation
- No user-visible documentation or API changes.

---

## [0.1.6] - 2025-12-31

### Notes
- **Not published** (Central Portal deployment cancelled; version cannot be reused).

---

## [0.1.5] - 2025-12-31

### Notes
- **Yanked / not published** (Maven Central deploy was cancelled; version cannot be reused).

### Fixed
- Release workflow: corrected `GPG_PRIVATE_KEY` environment scoping so the key imports correctly in CI and signatures
  (`.asc`) are produced.
- Release signing: ensured `.asc` signature artifacts are generated as part of the release build (via the `release`
  profile).

### Build
- Minor CI/release reliability improvements around non-interactive GPG usage.

---

## [0.1.4] - 2025-12-30

### Fixed
- Javadoc generation: corrected HTML issues and improved doc consistency.

### Changed
- Release build: attach Javadoc JARs and sources JARs for Maven Central.
- Signing: moved GPG signing into the `release` profile to avoid local/dev build friction.

### Documentation
- Clarified overload/rejection semantics and usage examples.

---

## [0.1.3] – 2025-12-30

### Fixed
- Corrected Maven Central publishing configuration (release deployment no longer depends on
  `distributionManagement`; 
  no API or semantic changes).

### Build
- Added a tag-gated release workflow suitable for CI publishing.

---

## [0.1.2] - 2025-12-30
### Fixed

- Maven Central publishing readiness fixes (GPG signing, metadata, staging)
- Parent / module POM alignment and groupId consistency
- Test stability under high concurrency (permit release & rejection assertions)

### Documentation
- Clarified async permit lifecycle and rejection semantics
- Minor README and formatting cleanups

---

## [0.1.1] - 2025-12-30

### Fixed

- Maven build and publishing metadata corrections
- Minor test assertion compatibility fixes
- Documentation formatting cleanup

---

## [0.1.0] — 2025-12-30

### Added

- Async bulkhead with a fixed upper bound on **in-flight async operations**
- Fail-fast rejection when capacity is exhausted (no waiting or queuing)
- Explicit rejection signaling via `BulkheadRejectedException`
- Guaranteed **non-invocation of suppliers** for rejected submissions
- Permit release on **all terminal outcomes** of the returned `CompletionStage`
  (success, exceptional completion, or cancellation)
- Deterministic, concurrency-focused tests enforcing all documented semantics
