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
with your own execution, retry, and fallback logic, this exists for that reason.

---

## What this is

An **async bulkhead** that:

* limits the number of **in-flight async tasks**
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
            if (err != null) {
                Throwable cause = (err instanceof CompletionException) ? err.getCause() : err;
                if (cause instanceof BulkheadRejectedException) {
                    // rejected (saturated)
                } else {
                    // task failed
                }
            }
        });
    }
}

```
