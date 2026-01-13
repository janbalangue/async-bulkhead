# Production guidance

This document describes **real-world usage guidance and failure modes**
for the async bulkhead.

The bulkhead is intentionally small and opinionated. It provides
**bounded, explicit admission control** for async operations.
It does not attempt to make systems safe by default.

Used correctly, it makes overload **visible and survivable**.
Used incorrectly, it can provide a false sense of safety.

---

## 1. Place the bulkhead at the true contention boundary

The bulkhead limits **admission**, not downstream resources.

It should be placed **before** work that consumes scarce capacity, such as:
- outbound HTTP calls
- database queries
- calls into other services
- fan-out orchestration layers

Placing a bulkhead after work has already started does not prevent overload.

Rule of thumb:
> The bulkhead should be the first thing that could reasonably say “no”.

### Admission is not ordered

Admission is **opportunistic and unordered**.

The bulkhead does not provide FIFO ordering, fairness, or eventual admission.
Under contention, submissions race for available capacity and may be rejected
indefinitely.

> Production systems must not rely on fairness or ordering guarantees.

> *Starvation is possible under sustained contention and is considered acceptable behavior.*

---

## 2. Ensure submitted work is *cold*

The supplied task **must not start work until admitted**.

Bad:
```java
CompletionStage<Response> stage = client.callAsync(); // work already started
bulkhead.submit(() -> stage);
```

Good:
```java
bulkhead.submit(() -> client.callAsync());
```

Rule of thumb:
> Nothing expensive should happen before the bulkhead admits the task.

If work starts early, this violates the bulkhead’s guarantees and the bulkhead
cannot provide meaningful protection.

### Use well-behaved CompletionStage implementations

The bulkhead observes terminal completion by registering a callback on the returned
`CompletionStage` (for example, via `whenComplete`).

If a custom or non-standard `CompletionStage` throws during callback registration,
the submission will fail and the bulkhead will release capacity immediately.
This indicates a broken stage implementation.

In production, prefer standard, well-behaved `CompletionStage` implementations
such as `CompletableFuture` or framework-provided stages with normal callback
registration semantics.

## 3. Size conservatively and measure

A bulkhead that allows too much concurrency can still cause:
* timeouts
* connection pool exhaustion
* memory pressure
* GC amplification
* cascading failures

Start with a small limit.

Observe:
* latency distributions (p95 / p99)
* rejection rates
* downstream saturation signals

Increase limits only when you can explain why the downstream system can handle it.

Fan-out inside admitted operations can amplify effective concurrency.
See section 4 for details.

Be especially careful when:
* composing async operations
* using thenCompose chains
* orchestrating parallel downstream requests

The bulkhead controls entry, not internal explosion.

Rejection under contention is race-based and unordered; brief bursts of rejection
are expected even when average load appears acceptable.

## 4. Fan-out amplification

Even with a bounded number of admitted operations, each admitted operation may
spawn **multiple concurrent sub-operations**.

Examples include:
* parallel database queries
* multiple outbound HTTP calls
* async continuations using `thenCompose` / `thenApplyAsync`
* orchestration or aggregation layers

Effective concurrency can therefore become:
```text
admitted_operations × fan_out
```

This can still overwhelm downstream systems even when the bulkhead limit is low.
Be especially careful when:
* composing async calls
* using fan-out patterns
* orchestrating parallel downstream requests

The bulkhead limits admission, not internal concurrency.

It does not protect against amplification inside admitted operations.

## 5. Cancellation and timeouts

Capacity is released only when the returned `CompletionStage`
reaches a **terminal state**.

If an operation never completes, capacity is never released.

Cancelling the `CompletionStage` returned by the bulkhead releases capacity,
but it does **not** cancel or interrupt the underlying work.

> *If this surprises you, this library is not what you want.*

> Cancellation is observed solely for permit accounting and is not propagated into user code.

Cancellation only affects admission accounting.
Always combine bulkheads with downstream timeouts and cooperative cancellation
to prevent abandoned work from continuing to consume resources.

Always ensure:
* downstream calls have timeouts
* cancellation is propagated where possible
* hung operations are detectable

Bulkheads and timeouts are complementary.

Neither replaces the other.

If callers abandon requests but underlying work continues to run,
load can accumulate outside the bulkhead’s visibility.

## 6. Remember this is per-process

This bulkhead is not **distributed**.

If global limits matter, enforce them elsewhere:
* load balancers
* rate limiters
* upstream admission control

## 7. When not to use this bulkhead

This library is likely a poor fit if you require:
* queued or blocking admission
* retries or fallback policies
* adaptive or auto-tuned limits
* framework-managed execution

## 8. Common misuse

* Using it without downstream timeouts
* Using it after work has started
* Using snapshots for coordination

> Starvation is acceptable because this bulkhead makes no fairness claims by design.