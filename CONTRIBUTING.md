# Contributing

Thanks for your interest in contributing.

This project is intentionally **small, opinionated, and design-driven**.
Contributions are welcome, but correctness and clarity take priority over feature breadth.

Please read this document before opening issues or pull requests.

---

## Project Philosophy

This library aims to provide a **single, correct async bulkhead primitive**.

We prioritize:
1. Correctness under concurrency
2. Explicit semantics and guarantees
3. Predictable behavior under overload
4. Production trust

We deliberately avoid:
- feature creep
- premature generalization
- supporting every async framework

---

## Start with the Design

Before proposing changes, **read [DESIGN.md](DESIGN.md)**.

DESIGN.md defines:
- core semantics
- guarantees
- non-goals
- failure behavior

Suggestions that conflict with documented non-goals may be declined, even if technically feasible.

---

## What Contributions Are Welcome

### ✅ Bug reports
Especially those involving:
- race conditions
- permit leaks
- cancellation behavior
- incorrect queue or timeout semantics

Please include:
- minimal reproduction
- expected vs actual behavior
- Java version and runtime details

---

### ✅ Tests
Tests that:
- expose edge cases
- demonstrate correctness
- prevent regressions

are highly valued.

---

### ✅ Documentation improvements
Clarifications to:
- semantics
- guarantees
- failure modes
- tuning guidance

are always welcome.

---

### ⚠️ Feature requests (limited)
Feature requests should:
- clearly explain the production problem being solved
- fit within the project’s stated goals
- avoid expanding the core primitive unnecessarily

Large or speculative features may be deferred or declined.

---

## What Is Out of Scope (v0.x)

The following are currently out of scope:
- Reactive framework integrations (Reactor, RxJava, etc.)
- Adaptive or auto-tuned concurrency
- Priority or weighted queues
- Per-tenant or distributed bulkheads
- Circuit breakers, retries, or fallback logic

Requests in these areas may be closed with reference to DESIGN.md.

---

## Pull Request Guidelines

- Keep changes focused and minimal
- Avoid unrelated refactors
- Include tests for behavior changes
- Preserve existing semantics unless explicitly discussed
- Explain *why* a change is needed, not just *what* changed

Pull requests that change public semantics without prior discussion may be declined.

---

## API Stability

This project is **pre-1.0**.

- APIs may change
- semantics are documented and tested
- breaking changes will be noted in the changelog

Do not rely on undocumented behavior.

---

## Communication Style

Be respectful and constructive.

We welcome disagreement about design choices, but discussions should focus on:
- correctness
- production behavior
- failure modes

Not on:
- naming preferences
- hypothetical future use cases
- general-purpose abstractions

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

---

## Maintainer Discretion

Maintainers may:
- close issues that are out of scope
- decline pull requests that add unnecessary complexity
- prioritize changes based on project direction

This is not personal — it’s how the project stays focused.

---

## Thank You

Thoughtful feedback and careful contributions help make this library reliable and trustworthy.
