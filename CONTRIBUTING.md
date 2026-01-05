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

### Semantics-first tests

This project treats unit tests as **semantic contracts**, not just functional checks.

In particular, tests may assert guarantees such as:
- fail-fast rejection under saturation
- supplier non-invocation when submissions are rejected
- explicit rejection signaling via `BulkheadRejectedException`
- permit release on all completion paths

Contributions that change public behavior must update tests and documentation
(`DESIGN.md`, `CHANGELOG.md`) accordingly.


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

> This project’s changelog documents **only user-visible changes to runtime behavior, public APIs, and operational guarantees**; internal refactors and implementation details are omitted unless they affect those guarantees.
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
