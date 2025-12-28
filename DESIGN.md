# Async Bulkhead – Design & Semantics

## 1. Goal

The goal of this library is to provide a **small, correct, async bulkhead primitive**
for Java applications.

Specifically, it:
- bounds concurrent async work (in-flight)
- bounds waiting work (queue)
- fails fast under overload
- protects latency via queue wait timeouts
- exposes production-grade metrics hooks

This library is intentionally **opinionated** and **minimal**.

---

## 2. Non-Goals (v0.x)

The following are explicitly out of scope for the initial versions:

- Reactive framework integrations (Reactor, RxJava, etc.)
- Priority queues or weighted scheduling
- Adaptive or auto-tuned concurrency limits
- Per-tenant or per-key bulkheads
- Distributed coordination
- Circuit breakers or retries

These may be explored later, but correctness of the core primitive comes first.

---

## 3. Public API Model

The primary API is submission-based:

```java
<T> CompletableFuture<T> submit(
  Supplier<? extends CompletionStage<T>> task
);
