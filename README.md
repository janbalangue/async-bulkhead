# Async Bulkhead (Java)

⚠️ **Early-stage, design-first project (pre-1.0)**

This repository provides a small, opinionated **async bulkhead** for Java with
explicit, test-backed semantics around overload behavior.

The goal is to make overload **bounded, visible, and predictable**.

---

## Why another bulkhead?

Most Java bulkhead implementations are part of **larger resilience frameworks** and make
tradeoffs that are reasonable at that scale, but undesirable when you want a **single,
well-defined primitive**.

This project exists because many existing bulkheads:
* mix **admission control** with execution concerns (thread pools, schedulers)
* introduce **queues**, **timeouts**, or **retries** that hide overload instead of surfacing it
* provide unclear or implicit semantics around when capacity is consumed and released
* couple behavior to specific reactive or framework abstractions

This bulkhead is intentionally different.

It focuses on **one thing only**:
* bounding the number of **in-flight async operations**
* **failing fast** when saturated
* never starting work it cannot admit
* making rejection an **explicit, composable signal**

There is no queue.
There is no waiting.
There is no internal execution model.

The returned `CompletionStage` is the **sole authority** for permit lifetime:
capacity is released only when that stage reaches a terminal state
(success, failure, or cancellation).

If you want a full resilience suite, adaptive policies, or framework integration,
this is probably not what you want.

If you want a **small, predictable, semantics-first async bulkhead** you can compose
with your own execution, retry, and fallback logic, this may work well for you.

---

## What this is

An **async bulkhead** that:

* limits the number of **in-flight async operations**
* **fails fast** when capacity is exhausted
* never starts work it cannot admit
* exposes explicit rejection semantics

A task is considered **in-flight** from the moment a submission is successfully
admitted until the returned `CompletionStage` reaches a **terminal state**
(successful completion, exceptional completion, or cancellation).

---

## What this is not

### Out of scope for v0.x:

* Reactive framework integrations (Reactor, RxJava)
* Priority or weighted scheduling
* Adaptive or auto-tuned limits
* Per-tenant or distributed bulkheads
* Circuit breakers, retries, or fallbacks
* Owning or managing a thread pool

---

## Failure modes and guarantees

This bulkhead provides **strong but narrow guarantees**.

### What this bulkhead guarantees

* A **hard upper bound** on the number of *in-flight async operations* admitted
* **Fail fast rejection** when capacity is exhausted
* No work starts unless it is successfully admitted
* Capacity is released only when the returned `CompletionStage` reaches a **terminal state**
  (success, failure, or cancellation)

This makes overload **bounded, explicit, and observable**.

### What this bulkhead does *not* guarantee

Using this bulkhead does not make a system “safe by default.” Teams can still experience
concurrency-related failures in the following cases:

* ### Work starts outside the bulkhead
The supplied task must be cold. If real work begins before submission
(e.g. creating a “hot” CompletionStage), the bulkhead cannot prevent overload.

Rule of thumb: nothing expensive should happen before the bulkhead admits the task.

* ### Fan-out inside admitted work
Even with a bounded number of admitted operations, each task may spawn multiple concurrent
sub-operations (database queries, HTTP calls, async continuations).

Effective concurrency becomes **N × M**, which can still overwhelm downstream systems.

* ### The bulkhead protects the wrong boundary
The bulkhead limits *admission*, not downstream resources. You can still exhaust:
* database or HTTP connection pools
* thread pools used by continuations
* CPU, memory, or GC capacity
* upstream rate limits

Bulkheads should be placed at the **true contention boundary**, not arbitrarily.

* ### Cancellation and timeouts are not propagated
If callers abandon requests but underlying work continues to run, load can accumulate
outside the bulkhead’s visibility.

Always combine bulkheads with **timeouts and cooperative cancellation**.

* ### Capacity is mis-sized
A bulkhead that allows too much concurrency can still cause saturation
(timeouts, connection exhaustion, memory pressure).

Start with conservative limits and size based on **latency distributions and downstream capacity**.

* ### Distributed concurrency is unbounded
This bulkhead is **per process**. In a multi-instance deployment, total concurrency scales
with the number of instances unless constrained elsewhere.

* ### Operations never complete
If a returned `CompletionStage` never reaches a terminal state due to a bug or hung call,
capacity is never released and the bulkhead will eventually reject all submissions.

___

### Design intent

This library intentionally **fails fast** instead of queueing or delaying work.
Rejection is a **signal**, not an error to be hidden.

If your system requires buffering, retries, prioritization, or distributed limits,
those concerns should be handled **outside** this bulkhead.

---

### Why this works well

* It is honest: no false sense of safety
* It aligns with your semantics (“in-flight means admitted until terminal”)
* It teaches correct usage without being verbose
* It preempts the most common “but we still overloaded” reports

---

## Stability

### Pre 1.0 (v0.x)

* Semantics are explicit and test-enforced
* APIs may change before 1.0; breaking changes are documented
* Rely only on documented behavior

Pin versions and review the changelog when upgrading.

---

## Who this is for

### For:
* a small, framework-agnostic async bulkhead
* fail-fast overload with no queues
* explicit, composable rejection semantics

### Not for:
* reactive frameworks or resilience suites
* retries, fallbacks, or adaptive limits
* queued or blocking admission

Focused on **one primitive, done correctly**.

___

## Requirements

* **Java 17**
* Maven 3.x

---

## Basic usage

```java
import io.janbalangue.bulkhead.Bulkhead;
import io.janbalangue.bulkhead.BulkheadRejectedException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

class Example {

  CompletionStage<String> someAsyncOperation() {
    return CompletableFuture.completedFuture("ok");
  }

  void demo() {
    Bulkhead bulkhead = new Bulkhead(2);

    CompletionStage<String> result =
            bulkhead.submit(this::someAsyncOperation);

    result.whenComplete((value, err) -> {
      if (err == null) {
        // success
        // e.g. use value
        return;
      }

      Throwable cause =
              (err instanceof CompletionException) ? err.getCause() : err;

      if (cause instanceof BulkheadRejectedException) {
        // rejected (bulkhead saturated)
      } else {
        // task failed
      }
    });
  }
}
```
