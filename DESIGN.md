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

- **Concurrency limiting**: a maximum number of **in-flight** async operations
    - A task is considered *in-flight* from the moment a submission is successfully
      admitted (a permit is acquired) until the **returned `CompletionStage`
      reaches a terminal state**.
    - A terminal state is defined as successful completion, exceptional completion,
      or cancellation of the returned `CompletionStage`.
    - Permit release is therefore **observed strictly at `CompletionStage` completion**,
      not at supplier invocation time nor at any intermediate execution stage.
- **Fail-fast rejection** when saturated
- **Explicit rejection signal** via `BulkheadRejectedException`
- **Permit release** when operations complete (success, failure, or cancellation)

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
- Executing operations on an internal thread pool

---

## 4. Public API Model

The bulkhead is submission-based and async-first.

Conceptual API:
```java
<T> CompletionStage<T> submit(
  Supplier<? extends CompletionStage<T>> task
);
```

