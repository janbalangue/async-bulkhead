# Contributing

Thanks for your interest in contributing.

This project is intentionally **small, opinionated, and design-driven**. Contributions are welcome,
but correctness and explicit semantics take priority over feature breadth.

---

## Start with the design

Before opening an issue or PR, read **[DESIGN.md](DESIGN.md)**.

DESIGN.md defines:
- core semantics and guarantees
- overload behavior
- non-goals for v0.x

Suggestions that conflict with documented non-goals may be declined.

---

## What contributions are welcome

### ✅ Bug reports
Especially around:
- race conditions
- permit leaks
- cancellation behavior
- queue/timeout semantics

Please include:
- minimal reproduction
- expected vs actual behavior
- Java version (target is Java 17)

### ✅ Tests
Tests that:
- expose edge cases
- prove semantics
- prevent regressions

are highly valued.

### ✅ Documentation improvements
Clarifications to semantics, guarantees, failure modes, and tuning guidance are welcome.

---

## Out of scope (v0.x)

To keep the core primitive small and correct, the following are out of scope initially:
- Reactive framework integrations (Reactor, RxJava, etc.)
- Priority or weighted scheduling
- Adaptive or auto-tuned limits
- Per-tenant or distributed bulkheads
- Circuit breakers, retries, or fallback policies

Requests in these areas may be closed with reference to DESIGN.md.

---

## Pull request guidelines

- Keep changes focused and minimal
- Avoid unrelated refactors
- Include tests for behavior changes
- Preserve documented semantics unless explicitly discussed
- Explain *why* a change is needed, not just *what* changed

PRs that change public semantics without prior discussion may be declined.

---

## API stability

This project is **pre-1.0**:
- APIs may change
- semantics should remain explicit and test-backed
- breaking changes will be documented in the changelog

---

## Communication style

Be respectful and constructive.

We value feedback focused on:
- correctness
- failure modes
- production behavior

We de-prioritize:
- naming debates
- speculative future use cases
- general-purpose abstractions

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
CHANGELOG.md (tightened)
markdown
Copy code
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
- Explicit exception for queue wait timeout failures
