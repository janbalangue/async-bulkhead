# Changelog

> *Production users should start at ≥ 0.1.7.*

All notable, user-visible changes to runtime behavior and public APIs are documented in this file.

This changelog documents only **user-visible changes** to runtime behavior, public APIs, and operational guarantees; internal refactors and implementation details are omitted unless they affect those guarantees.

Until version `1.0.0`, all releases are **pre-stable** and may introduce breaking changes. Any such changes will be explicitly documented.

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

Some early 0.1.x versions were affected by cancelled or incomplete Maven Central publication attempts. Because Maven Central does not allow reuse of version numbers once a deployment is cancelled or rejected,
those versions should be considered **non-canonical**.

The first fully published and supported release series begins with 0.1.7.

---

## [0.3.0] - 2026-01-13

### Added
- **Observability hooks** via `BulkheadListener`:
  - `onAdmitted()` — invoked after permit acquisition and before supplier invocation
  - `onRejected()` — invoked on fail-fast rejection
  - `onReleased(TerminalKind kind, Throwable error)` — invoked exactly once per admitted operation.
- `TerminalKind` enum to classify terminal outcomes as `SUCCESS`, `FAILURE`, or `CANCELLED`.
- Lightweight **introspection methods**:
  - `limit()` — configured concurrency limit
  - `available()` — instantaneous available permits (advisory snapshot)
  - `inFlight()` — instantaneous in-flight count derived from `limit - available` (advisory snapshot).

### Changed
- **Cancellation semantics are now explicit and user-visible**:
  - cancelling the `CompletionStage` returned by `submit(...)` produces a *cancelled* stage
  - cancellation releases capacity exactly once
  - cancellation is **not** propagated to the underlying operation.
- Listener callbacks are **best-effort and non-intrusive**:
  - exceptions thrown by listeners are swallowed
  - listener failures cannot affect admission, rejection, or permit-release semantics.

### Fixed
- Hardened permit accounting in edge cases:
  - ensured exactly-once permit release across completion, exceptional completion, and cancellation races
  - failures during terminal handler registration fail the submission and release capacity to prevent permit leaks.

### Notes
- Invariant violations in internal permit accounting continue to be surfaced as `IllegalStateException`.

> *These indicate internal bugs and should never be observed in correct usage.*
---

## [0.2.3] - 2026-01-05
### Documentation
- Rewrote README.md to align strictly with DESIGN.md semantics.
- Clarified the core invariant: in-flight means admitted until terminal.
- Explicitly documented fail-fast, unordered admission and terminal-based capacity release.
- Added a concise comparison section explaining differences from framework-based and reactive bulkheads.
- Tightened wording around cancellation, rejection, and capacity lifetime.
- No runtime, API, or behavioral changes.

---

## [0.2.2] - 2026-01-05

### Fixed
- Release bookkeeping: publish missing changelog entries and align tagged release with published artifacts.

### Notes
- No runtime or API changes from 0.2.1.

---

## [0.2.1] - 2026-01-05

### Documentation
- Restored the missing changelog entry for v0.2.0.
- No code, API, or behavioral changes.

---

## [0.2.0] - 2026-01-05

### Changed
- Defined **cancellation** of the returned `CompletionStage` as a **terminal outcome** that releases capacity.
- Defined **permit lifetime** strictly from successful admission until the returned `CompletionStage` reaches a terminal state (success, exceptional completion, or cancellation).
- Guaranteed **exactly-once permit release** across all races between completion, exceptional completion, and cancellation.
- Tightened admission semantics: capacity is acquired **only at the moment of submission**; there is no deferred, waiting, or speculative admission.

### Clarified
- Formalized the core invariant: *in-flight means admitted until terminal*.
- Clarified that the returned `CompletionStage` is the **sole authority** for permit lifetime.
- Clarified that rejection is a **normal control signal** representing intentional load shedding under saturation.
- Clarified that `BulkheadRejectedException` indicates **capacity exhausted and no work started**.

### Documentation
- Significantly expanded and refined **DESIGN.md** to explicitly define semantics, invariants, races, cancellation behavior, and non-goals.
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
- Release workflow: corrected `GPG_PRIVATE_KEY` environment scoping so the key imports correctly in CI and signatures (`.asc`) are produced.
- Release signing: ensured `.asc` signature artifacts are generated as part of the release build (via the `release` profile).

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
- Corrected Maven Central publishing configuration (release deployment no longer depends on `distributionManagement`; no API or semantic changes).

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
