# Async Bulkhead – Design & Semantics

## 1. Goal

Provide a **small, correct, opinionated async bulkhead primitive** for Java that helps services
remain stable under overload by bounding concurrent work.

This project targets **Java 17**.

The bulkhead is designed to be:
- easy to integrate (no framework coupling)
- explicit about overload behavior
- test-driven with semantics locked by unit tests

---

## 2. Scope (v0.1)

In v0.1, the bulkhead provides:

- **Concurrency limiting**: a maximum number of **in-flight** async tasks
- **Fail-fast rejection** when saturated
- **Explicit rejection signal** via `BulkheadRejectedException`
- **Permit release** when tasks complete (success, failure, or cancellation)

v0.1 intentionally does **not** include a waiting queue.

---

## 3. Non-goals (v0.x)

To keep the primitive small and correct, the following are out of scope initially:

- Reactive framework integrations (Reactor, RxJava)
- Priority or weighted scheduling
- Adaptive or auto-tuned limits
- Per-tenant/per-key bulkheads
- Distributed coordination
- Circuit breakers, retries, fallback policies
- Executing tasks on an internal thread pool

---

## 4. Public API Model

The bulkhead is submission-based and async-first.

Conceptual API:

```java
<T> CompletionStage<T> submit(
  Supplier<? extends CompletionStage<T>> task
);