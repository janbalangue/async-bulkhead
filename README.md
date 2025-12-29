# Async Bulkhead (Java)

⚠️ **Early-stage, design-first project (pre-1.0)**

This repository provides a small, opinionated **async bulkhead** for Java with
explicit, test-backed semantics around overload behavior.

The goal is to make overload **bounded, visible, and predictable**.

---

## What this is

An **async bulkhead** that:

- limits the number of **in-flight async tasks**
- **fails fast** when capacity is exhausted
- never starts work it cannot admit
- exposes explicit rejection semantics

This is intentionally **not** a full resilience framework.

---

## What this is not

Out of scope for v0.x:

- Reactive framework integrations (Reactor, RxJava)
- Priority or weighted scheduling
- Adaptive or auto-tuned limits
- Per-tenant or distributed bulkheads
- Circuit breakers, retries, or fallbacks
- Owning or managing a thread pool

---

## Requirements

- **Java 17**
- Maven 3.x

---

## Basic usage

```java
Bulkhead bulkhead = new Bulkhead(2);

CompletionStage<String> result =
    bulkhead.submit(() -> someAsyncOperation());
