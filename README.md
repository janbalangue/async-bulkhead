# Async Bulkhead (Java)

⚠️ **Pre-1.0 with a stable semantic core**

This repository provides a small, opinionated **async bulkhead** for Java with **explicit, test-backed semantics** around overload behavior.
The goal is to make overload **bounded, visible, and predictable**.

> *This library does not prevent overload failures; it prevents overload from being silent.*

### Migration

A simple search/replace is sufficient:

`io.janbalangue.bulkhead` → `io.janbalangue.asyncbulkhead`


---

## Why another bulkhead?

Most Java bulkhead implementations are part of **larger resilience frameworks** and make reasonable trade-offs at that scale, but undesirable ones when you want a **single, well-defined primitive**.

This project exists because many existing bulkheads:

- mix **admission control** with execution concerns (thread pools, schedulers)
- introduce **queues**, **timeouts**, or **retries** that hide overload instead of surfacing it
- provide unclear semantics around when capacity is consumed and released
- couple behavior to specific frameworks or reactive abstractions

This library focuses on **one thing only**:

- bounding the number of **in-flight async operations**
- **failing fast** when saturated
- never starting work it cannot admit
- making rejection an **explicit, composable signal**

There is **no queue**.  
There is **no internal execution model**.

---

## Core behavior (high level)

- Admission is **fail-fast** and **non-blocking**
- Admission is **unordered** (no FIFO, no fairness guarantees)
- An operation is considered *in-flight* from successful admission until its returned
  `CompletionStage` reaches a **terminal state** (success, failure, or cancellation)

If terminal completion observation cannot be registered (for example, if callback
registration throws), the submission fails and capacity is released immediately
to avoid permit leaks.

Under contention, concurrent submissions race for available capacity.
If capacity is unavailable at the moment of submission, the operation is rejected.

---

## What this is

An **async bulkhead** that:

- limits the number of **in-flight async operations**
- sheds load explicitly via rejection
- exposes overload as a first-class signal

Cancellation is treated as a **terminal outcome** for capacity accounting:
capacity is released, but cancellation is **not propagated** to underlying work.

Both cooperative cancellation (via returned stages) and external cancellation (via user-managed signals) are explicitly modeled.
Capacity release, listener notification, and error observation are race-safe and invariant-preserving.

---

## What this is not (v0.x)

Out of scope:

- queued or blocking admission
- retries, fallbacks, or circuit breakers
- adaptive or auto-tuned limits
- reactive framework integrations
- distributed or per-tenant bulkheads
- owning or managing a thread pool

Those concerns are meant to be composed *around* this primitive.

---

## Design & production guidance

This README intentionally stays high level.

For precise semantics and guarantees, see:
- **DESIGN.md** — semantic model, invariants, races, cancellation behavior
- **PRODUCTION.md** — real-world usage guidance and failure modes

---

## Basic usage

```java
import io.janbalangue.asyncbulkhead.Bulkhead;
import io.janbalangue.asyncbulkhead.BulkheadRejectedException;

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
        return;
      }

      Throwable cause =
          (err instanceof CompletionException) ? err.getCause() : err;

      if (cause instanceof BulkheadRejectedException) {
        // rejected (bulkhead saturated)
      } else {
        // operation failed
      }
    });
  }
}
```

## Explicit overload handling helpers

Concise helpers for handling overload at submission time.

`submitOrElse(...)`

Run fallback logic only when the bulkhead is saturated:

```java
CompletionStage<String> result =
    bulkhead.submitOrElse(
        this::someAsyncOperation,
        () -> CompletableFuture.completedFuture("fallback")
    );
```

`submitOrElseValue(...)`

Return a value immediately on rejection:

```java
CompletionStage<String> result =
    bulkhead.submitOrElseValue(
        this::someAsyncOperation,
        "fallback"
    );
```

Guarantees:
* The primary supplier is never invoked if capacity is exhausted
* Rejection remains explicit and observable
* No queuing, waiting, or retries are introduced

## Rejection classification utilities

To distinguish overload rejection from execution failure in async pipelines:

```java
result.whenComplete((value, error) -> {
  if (BulkheadRejectedException.isRejected(error)) {
    // overload signal
  } else if (error != null) {
    // real failure
  }
});
```

These helpers exist purely for classification and do not affect bulkhead semantics.

## Observability

Bulkhead lifecycle events can be observed via listeners. Listeners are invoked on admission, rejection, and release, and are guaranteed to observe consistent capacity accounting.

Listener callbacks must be fast and non-blocking; they are intended for metrics, logging, and tracing-not control flow.

## Comparison with other bulkheads

* This bulkhead is intentionally narrower and more explicit than most existing Java bulkhead implementations.
* Its behavior follows a single design invariant defined in DESIGN.md:

> In-flight means admitted until terminal.

### High-level comparison

Dimension           	| This bulkhead	                      | Resilience4j bulkhead | Hystrix (legacy)     | Reactive bulkheads (e.g. Project Reactor) 
------------------------|-------------------------------------|-----------------------|----------------------|------------------------------------------
Primary concern	        | Admission control                   | Execution isolation   | Execution isolation  | Stream backpressure                       
Queuing	                | None	                              | Optional	          | Internal	         | Implicit                                  
Waiting for capacity	| Never	                              | Sometimes	          | Often	             | Framework-defined
Async-first      	    | Yes	                              | Mixed	              | Mostly sync	         | Yes
In-flight definition	| Explicit, terminal-based	          | Implicit	          | Thread-based	     | Subscription-based 
Cancellation semantics	| Terminal & defined	              | Often implicit	      | Weak / unclear	     | Framework-specific
Ordering/fairness	    | None	                              | Limited	              | Limited	             | Often ordered
Scope	                | Single primitive	                  | Resilience suite	  | Full framework	     | Reactive pipelines

## Stability

**Pre-1.0 (v0.x)**
* Semantics are explicit and test-enforced
* APIs may change before 1.0; breaking changes will be documented
* Rely only on documented behavior

Pin versions and review the changelog when upgrading.