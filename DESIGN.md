# async-bulkhead — Design (v0.5.0)

This document describes the *design goals, invariants, and semantics* of **async-bulkhead** as of **v0.5.0**.  
It is intentionally precise and opinionated.

If something is not specified here, it is either:
- a non-goal, or
- explicitly undefined.

> **Note**  
> The public Java package namespace is:
>
> `io.janbalangue.asyncbulkhead.*`

The library exists to provide **hard, explicit concurrency bounds** for asynchronous systems without hiding overload, delaying failure, or conflating admission control with execution, queuing, or timeouts.

---

## 1. Core problem statement

Modern asynchronous systems rarely fail because individual operations are slow.  
They fail because **too much work is admitted at once**.

Common failure patterns include:

- fan-out amplification (1 request → N downstream async calls)
- unbounded async submission
- relying on timeouts instead of admission control
- overload being discovered *after* work has already started

`async-bulkhead` addresses these failures by **rejecting work before it starts**
once a fixed concurrency limit is reached.

Key premise:

> **If work has been admitted, it must be allowed to run to completion.**

For the purposes of this design, "completion" includes success, failure, or cancellation.

Everything else in this design follows from that premise.

---

## 2. What a bulkhead is (and is not)

### A bulkhead *is*

- a **concurrency admission gate**
- a mechanism for **fail-fast overload signaling**
- a way to make capacity constraints **explicit and observable**

### A bulkhead is *not*

- a timeout mechanism
- a queue
- a scheduler or executor
- a retry or fallback controller
- a cancellation propagator

If you want fairness, retries, prioritization, or work cancellation,
those concerns must be layered *around* the bulkhead.

---

## 3. Admission model

Admission is **binary and immediate**:

- If capacity is available, the supplier is invoked exactly once
- If capacity is exhausted, the supplier is **not invoked** and the submission is rejected

There is:

- no waiting
- no buffering
- no reservation
- no deferred admission

Rejection is a **normal and intentional outcome**.

---

## 4. In-flight lifecycle and terminal states

An operation is considered **in-flight** from the moment it is successfully admitted until the returned `CompletionStage` reaches a **terminal state**.

Terminal states are:
- successful completion
- exceptional completion
- cancellation

Capacity accounting is defined strictly in terms of these terminal states.
Once a terminal state is observed, capacity **must be released exactly once**.

No other events (timeouts, user intent, or external signals) affect capacity.

---

## 5. Cancellation semantics

Cancellation is treated as a **terminal outcome**, not a control mechanism.

Two forms of cancellation are recognized:

### Cooperative cancellation

Cooperative cancellation occurs when the returned `CompletionStage` is completed with cancellation by user code or downstream composition.

In this case:

- the operation is considered terminal (distinct from success nor failure)
- capacity is released
- cancellation is **not propagated** to underlying work

### External cancellation

External cancellation refers to user-managed cancellation signals that do not directly complete the returned `CompletionStage`.

External cancellation:

- does not affect admission
- does not immediately release capacity
- only influences capacity once a terminal stage is observed

The bulkhead does not attempt to coordinate or enforce external cancellation.

---

## 6. Listener semantics and ordering

Bulkhead listeners observe lifecycle events for **admission**, **rejection**, and **release**.

Listener guarantees:

- Admission listeners observe only successfully admitted operations
- Rejection listeners observe only failed admissions
- Release listeners are invoked exactly once per admitted operation

Release notification occurs **after** the terminal state of the operation is observed and capacity has been released.

Listener expectations:

- should be fast and non-blocking
- should not throw
- must not influence bulkhead behavior

Listener failures are ignored to preserve bulkhead invariants.

---

## 7. Concurrency, races, and invariants

Under contention, admission, completion, and cancellation may race.

The bulkhead guarantees:

- capacity is never exceeded
- capacity is never leaked
- capacity is released at most once per admission

All observable behavior must preserve these invariants regardless of interleaving or timing.

---

## 8. Undefined behavior

The following aspects of bulkhead behavior are **explicitly undefined** and must not be relied upon by users:

- **Admission ordering or fairness**
  - There is no FIFO, LIFO, or priority-based admission.
  - Concurrent submissions may be admitted or rejected in any order.

- **Listener invocation ordering**
  - No ordering is guaranteed between different listener callbacks.
  - No ordering is guaranteed between listener invocation and user code beyond what is explicitly specified.

- **Threading and execution context**
  - The bulkhead does not guarantee which thread invokes suppliers, completion callbacks, or listeners.
  - No happens-before relationship is implied beyond what is required to observe a terminal state.

- **Timing guarantees**
  - No guarantees are made about when rejection, admission, or release is observed relative to wall-clock time.
  - Observability may be delayed by scheduling or execution effects.

- **Cancellation propagation**
  - The bulkhead does not guarantee that cancellation signals affect underlying work.
  - Coordination between cancellation and user-managed work is out of scope.

Any behavior not explicitly specified elsewhere in this document is undefined and may change without notice, even in minor versions prior to 1.0.