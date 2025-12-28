# Async Bulkhead – Design & Semantics

## 1. Goal

Provide a **small, correct, opinionated async bulkhead primitive** for Java that helps services
remain stable under overload.

Specifically, it aims to:
- bound concurrent async work (in-flight)
- bound waiting work (queue)
- define explicit saturation behavior (fail fast)
- protect tail latency via queue wait timeouts
- expose integration-friendly metrics hooks

This project targets **Java 17**.

---

## 2. Non-Goals (v0.x)

To keep the core primitive small and correct, the following are out of scope initially:

- Reactive framework integrations (Reactor, RxJava, etc.)
- Priority or weighted scheduling
- Adaptive or auto-tuned limits
- Per-tenant / per-key bulkheads
- Distributed coordination
- Circuit breakers, retries, or fallback policies
- Executing tasks on an internal thread pool

---

## 3. Public API Model

The primary API is submission-based and async-first:

```java
<T> CompletableFuture<T> submit(
  Supplier<? extends CompletionStage<T>> task
);