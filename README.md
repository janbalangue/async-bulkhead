# Async Bulkhead (Java)

⚠️ **Early-stage, design-first project (pre-1.0)**

This repository provides a small, opinionated **async bulkhead** for Java with explicit, test-backed semantics around overload behavior.

The goal is to make overload **bounded, visible, and predictable**.

---

## Why another bulkhead?

Most Java bulkhead implementations are part of **larger resilience frameworks** and make reasonable tradeoffs at that scale, but undesirable ones when you want a **single, well-defined primitive**.

This project exists because many existing bulkheads:
- mix **admission control** with execution concerns (thread pools, schedulers)
- introduce **queues**, **timeouts**, or **retries** that hide overload instead of surfacing it
- provide unclear semantics around when capacity is consumed and released
- couple behavior to specific frameworks or reactive abstractions

It focuses on **one thing only**:
- bounding the number of **in-flight async operations**
- **failing fast** when saturated
- never starting work it cannot admit
- making rejection an **explicit, composable signal**

There is no queue.  
There is no internal execution model.

---

## Core behavior (high level)

- Admission is **fail-fast** and **non-blocking**
- Admission is **unordered** (no FIFO, no fairness guarantees)
- An operation is considered *in-flight* from successful admission until its
  returned `CompletionStage` reaches a **terminal state**
  (success, failure, or cancellation)
- Capacity is released **only** at terminal completion

Under contention, concurrent submissions race for available capacity.
If capacity is unavailable at the moment of submission, the operation is rejected.

---

## What this is

An **async bulkhead** that:
- limits the number of **in-flight async operations**
- sheds load explicitly via rejection
- exposes overload as a first-class signal

## What this is not

Out of scope for v0.x:
- queued or blocking admission
- retries, fallbacks, or circuit breakers
- adaptive or auto-tuned limits
- reactive framework integrations
- distributed or per-tenant bulkheads
- owning or managing a thread pool

---

## Comparison with other bulkheads

This bulkhead is intentionally **narrower and more explicit** than most existing Java bulkhead implementations.

Its behavior follows a single design invariant defined in **DESIGN.md**:

> In-flight means admitted until terminal.

All other semantics derive from this invariant.

---

### High-level comparison

Dimension              | This bulkhead            | Resilience4j bulkhead | Hystrix (legacy)    | Reactive bulkheads (e.g. Project Reactor)
-----------------------|--------------------------|-----------------------|---------------------|------------------------------------------
Primary concern        | Admission control        | Execution isolation   | Execution isolation | Stream backpressure
Queuing                | None                     | Optional              | Internal            | Implicit
Waiting for capacity   | Never                    | Sometimes             | Often               | Framework-defined
Async-first            | Yes                      | Mixed                 | Mostly sync         | Yes
In-flight definition   | Explicit, terminal-based | Implicit              | Thread-based        | Subscription-based
Cancellation semantics | Terminal & defined       | Often implicit        | Weak / unclear      | Framework-specific
Ordering/fairness      | None                     | Limited               | Limited             | Often ordered
Scope                  | Single primitive         | Resilience suite      | Full framework      | Reactive pipelines

---

### Core design differences

#### Admission control, not execution control

Most bulkheads control **how work executes**:
* thread pools
* schedulers
* executor queues

This bulkhead controls **whether work may start**.

It does **not**:
* execute tasks
* manage threads
* delay, buffer, or retry submissions

Admission is atomic and has exactly two outcomes:
* **admitted** (permit acquired, supplier invoked)
* **rejected** (no permit, supplier not invoked)

There is no intermediate state.

---

#### Fail fast, never wait

This bulkhead **never waits for capacity**.

If capacity is unavailable at submission time:
* the operation is rejected immediately
* no work is started
* rejection is surfaced synchronously as a failed `CompletionStage`

There is:
* no queue
* no reservation
* no deferred admission

This is a deliberate design choice to make overload **explicit and visible**.

---

#### Explicit in-flight semantics

This bulkhead defines *in-flight* precisely:
> An operation is **in-flight** from successful admission until the returned `CompletionStage` reaches a **terminal state**.

Terminal states are strictly defined as:
* successful completion
* exceptional completion
* cancellation

Capacity is released **only** when one of these states is observed.

This definition is documented, test-backed, and invariant under concurrency races.

---

#### Cancellation is a first-class terminal outcome

If the returned `CompletionStage` is cancelled:
* capacity is released exactly once
* no retries or restarts occur
* admission semantics remain unchanged

The bulkhead does **not** propagate cancellation downstream; it only observes it to maintain correct admission accounting.

---

#### Unordered, opportunistic admission

Admission is **not ordered**.

The bulkhead does not guarantee:
* FIFO behavior
* fairness
* eventual admission after rejection

Concurrent submissions race for available capacity.
Rejection under contention is expected and correct behavior.

---

#### Small by design, composable by intent

This library is **not** a resilience framework.

It intentionally excludes:
* queues or blocking admission
* retries or fallbacks
* circuit breakers
* adaptive or auto-tuned limits
* reactive framework integrations

Those concerns are meant to be composed *around* this primitive.

---

### When this bulkhead fits
* You want **explicit, bounded admission control**
* You need to reason clearly about **what is actually in-flight**
* You want overload to be **visible, not hidden**
* You already own execution, retries, and timeouts


### When it does not
* You need queuing or load smoothing
* You want framework-managed execution
* You require fairness or ordering
* You want a full resilience toolkit

---

## Design & production guidance

This README intentionally stays high level.

For precise semantics and guarantees, see:
- **DESIGN.md** — semantic model, invariants, races, cancellation behavior
- **PRODUCTION.md** — real-world usage guidance and failure modes

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
---

## Stability

**Pre-1.0 (v0.x)**
* Semantics are explicit and test-enforced
* APIs may change before 1.0; breaking changes are documented
* Rely only on documented behavior

Pin versions and review the changelog when upgrading.
