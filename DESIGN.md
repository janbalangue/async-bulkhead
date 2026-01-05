# Async Bulkhead — Design & Semantics (v0.2.x)
## 1. Goal

Provide a **small, correct, semantics-first async bulkhead** for Java that bounds
the number of **in-flight asynchronous operations**.

The purpose of this bulkhead is to make overload **explicit, bounded, and visible**
by enforcing **admission control**, not by hiding load through queuing or retries.

> Think of the bulkhead as a gate: admission is instantaneous, capacity is held until
> terminal completion, and excess load is rejected immediately.

> See PRODUCTION.md for real-world usage patterns and failure modes.

**Target**: Java 17

**Scope**: Single-process, async-only admission control

## 2. Core design principles

> **Design invariant**: *in-flight means admitted until terminal*. All other semantics
> derive from this invariant.

The bulkhead relies on the returned `CompletionStage` to allow terminal observation
(e.g., via `whenComplete`). If that contract is violated, the bulkhead fails the
submission and surfaces the error.

### 2.1 Admission, not execution

The bulkhead controls **whether an operation may start**.

It does **not**:
* execute operations
* schedule work
* manage threads
* retry, delay, or buffer submissions

All execution concerns are owned by the caller.

### 2.2 Fail fast, never wait

This bulkhead **never waits** for capacity.

If capacity is unavailable at :
* the submission is **rejected immediately**
* no work is started
* rejection is explicit and observable

There is no queue.

### 2.3 Admission is not ordered

Admission is **opportunistic, not ordered**.

The bulkhead does not guarantee:
* FIFO ordering
* fairness across threads or callers
* eventual admission after rejection

Concurrent submissions race for available capacity.
If capacity is unavailable at the moment `submit` is called, the operation is rejected.

Callers must not assume ordering or fairness.

### 2.4 In-flight means “admitted until terminal”

An operation is considered **in-flight** from the moment it is successfully admitted
(a permit is acquired) until the returned `CompletionStage` reaches a
terminal state.

The returned `CompletionStage` is the sole authority for permit lifetime.

## 3. Terminal states

Terminal states are strictly defined as:
* successful completion
* exceptional completion
* cancellation

Capacity is released **only** when one of these terminal states is observed.

Intermediate execution steps, continuations, or callbacks do not affect permit
lifetime.

## 4. Public API model

Conceptual API:
```java
<T> CompletionStage<T> submit(
  Supplier<? extends CompletionStage<T>> operation
);
```

The API is **submission-based** and **async-first**.

## 5. Submission semantics

Submission is atomic and has exactly two outcomes:

### 5.1 Admitted

* Capacity is available at submission time
* A permit is acquired
* The supplier is invoked exactly once
* The returned `CompletionStage` governs permit release

### 5.2 Rejected

* Capacity is unavailable
* No permit is acquired
* The supplier is **not invoked**
* A `BulkheadRejectedException` is returned synchronously as a failed stage

There is no intermediate or deferred state.

## 6. Supplier invocation guarantees

The bulkhead provides the following guarantees:
* A supplier is invoked **at most once**
* A supplier is invoked **only if admission succeeds**
* A rejected submission **never invokes** the supplier
* Supplier invocation occurs **after** permit acquisition

Submitted operations **must be cold**.
Any side effects occurring before admission are invalid usage.

## 7. Permit lifecycle

Permit management is the **core correctness concern** of this bulkhead. All
concurrency, cancellation, and race-handling logic exists to uphold a single
invariant: **exactly one permit is released for every admitted operation, and
only after a terminal signal is observed**.

### 7.1 Acquisition

* Permit acquisition happens synchronously during submission
* Admission succeeds only at 
* There is no reservation, waiting, or speculative acquisition

## 7.2 Release

A permit is released exactly once, when the returned `CompletionStage`
reaches a terminal state.

Permit release is:
* deterministic
* idempotent
* independent of execution threads

## 7.3 Completion races

In the presence of races between:
* successful completion
* exceptional completion
* cancellation

the bulkhead guarantees:
* exactly one permit release
* no permit leaks
* no double release

The **first observed terminal signal wins**.

## 7.4 Handler registration failure

* If completion handler registration throws, the submission is treated as terminal
  failure; the returned stage fails with that error; **capacity is released exactly once**.
* This is rare, but it prevents leaks in pathological `CompletionStage` implementations.

Example snippet:
> **Handler registration failure**: If attaching the terminal observer to the returned
> `CompletionStage` throws (e.g., `whenComplete` fails), the bulkhead treats the 
> submission as terminal failure: it releases capacity exactly once and returns a failed
> stage to the caller.

## 8. Cancellation semantics

Cancellation is a **first-class terminal outcome**.

If the returned `CompletionStage` is cancelled:
* capacity is released
* admission semantics remain unchanged

The bulkhead does **not** propagate cancellation downstream; it only observes it
to manage capacity.

## 9. Rejection semantics

Rejection is a **normal control signal**, not an exceptional failure. It indicates
intentional load shedding under saturation and is expected to occur in healthy
systems under bursty load.

Rejection is explicit and synchronous.

`BulkheadRejectedException` signals that:
* capacity was exhausted
* no work was started
* the system is intentionally shedding load

## 10. Thread-safety and concurrency guarantees

* The bulkhead is thread-safe
* Concurrent submissions are supported
* All documented guarantees hold under high contention

Correctness is enforced through deterministic concurrency tests.

## 11. Non-goals (v0.x)

The following are explicitly **out of scope** and will not be added in v0.x:
* Queues or waiting admission
* Timeouts, retries, or fallback policies
* Reactive framework integrations
* Thread pool ownership or scheduling
* Adaptive or auto-tuned limits
* Distributed or per-key bulkheads

These concerns must be composed around this bulkhead.

## 12. Intentional failure modes

This bulkhead does not protect against:
* work starting outside the bulkhead
* fan-out inside admitted operations
* downstream resource exhaustion
* hung or never-completing operations
* distributed concurrency amplification

These are system-level concerns that must be addressed explicitly.

## 13. Design intent summary

This bulkhead is:
* **Small** — minimal API, minimal behavior
* **Explicit** — no hidden queues or retries
* **Honest** — overload is visible
* **Unordered** — admission is opportunistic
* **Composable** — integrates with any async model
* **Test-defined** — tests are the contract

> This library intentionally optimizes for correctness and predictability over throughput
> smoothing or fairness.
