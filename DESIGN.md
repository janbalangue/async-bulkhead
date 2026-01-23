# async-bulkhead — Design (v0.3.x)

This document describes the *design goals, invariants, and semantics* of **async-bulkhead** as of **v0.3.0**. It is intentionally precise and opinionated. If something is not specified here, it is either a non-goal or explicitly undefined.

The library exists to provide **hard, explicit concurrency bounds** for asynchronous systems without hiding overload, delaying failure, or conflating backpressure with timeouts.

---

## 1. Core problem statement

Modern asynchronous systems rarely fail because individual operations are slow. They fail because **too much work is admitted at once**.

Common failure patterns include:

* fan-out amplification (1 request → N downstream async calls)
* unbounded async submission
* reliance on timeouts instead of admission control

`async-bulkhead` addresses these failures by **rejecting work before it starts** once a fixed concurrency limit is reached.

Key premise:

> *If work has been admitted, it must be allowed to run to completion.*

Everything else in this design follows from that premise.

---

## 2. What a bulkhead is (and is not)

### A bulkhead *is*

* a **concurrency admission gate**
* a mechanism for **fail-fast overload signaling**
* a way to make capacity constraints explicit and observable

### A bulkhead is *not*

* a timeout mechanism
* a queue
* a scheduler
* a retry controller
* a cancellation propagator

If you want fairness, retries, prioritization, or work cancellation, those must be layered *above* the bulkhead.

---

## 3. Admission model

Admission is **binary and immediate**:

* If capacity is available, the supplier is invoked exactly once
* If capacity is exhausted, the supplier is *not invoked* and the submission is rejected

There is:

* no waiting
* no buffering
* no reordering

Rejection is a *successful outcome* from the bulkhead’s perspective.

> *There is no guarantee of eventual admission under contention; repeated submissions may be rejected indefinitely.*

---

## 4. Supplier invocation rules

A supplier passed to the bulkhead:

* MUST NOT be invoked unless a permit is successfully acquired
* MUST be invoked at most once
* MUST be treated as potentially throwing

If the supplier throws synchronously:

* the permit is released
* the returned stage completes exceptionally with the same throwable

Supplier invocation is considered part of the admitted work.

---

## 5. In-flight definition

An operation is considered **in-flight** from the moment it is admitted until it reaches a **terminal state**.

Terminal states are:

* normal completion
* exceptional completion
* cancellation

The bulkhead observes terminal signals **only to release capacity**. It does not reinterpret, transform, suppress, or delay them.

---

## 6. Cancellation semantics

Cancellation is treated as a **terminal signal** for capacity accounting purposes only.

Specifically:

* Cancelling the `CompletionStage` returned by the bulkhead releases the associated permit
* Cancellation does **not** attempt to interrupt, stop, or cancel the underlying work
* If the supplier itself returns a stage that is later cancelled, that cancellation is observed as terminal and releases capacity

The bulkhead does not attempt to propagate cancellation *into* user code. Cancellation is an *observation*, not a control mechanism.

This ensures that:

* capacity is never leaked
* admitted work is never forcefully terminated by the bulkhead

> *Cancellation is equivalent to the caller abandoning interest in the result; it does not imply that underlying work has stopped.*

---

## 7. Failure and exception propagation

The bulkhead does not reinterpret failures.

* If the supplier throws, the throwable is propagated
* If the returned stage completes exceptionally, that exception is propagated
* Rejection is surfaced as a distinct, immediate signal

All terminal signals are treated equivalently with respect to capacity release.

---

## 8. Listener semantics

Bulkhead listeners exist to provide **observability**, not control.

Listeners MAY be notified of:

* successful admission
* rejection
* release of a permit with a terminal outcome

The following guarantees apply:

* Listener callbacks MUST NOT affect bulkhead semantics
* Listener callbacks MAY be invoked concurrently
* Listener callbacks MAY execute on arbitrary threads
* Listener callbacks MUST be treated as best-effort

Any exception thrown by a listener is intentionally ignored. Listener failure must not interfere with admission, execution, or release.

Listeners are suitable for metrics, logging, and tracing only.

---

## 9. Determinism and invariants

The bulkhead enforces the following invariants:

* A permit is acquired **at most once** per submission
* A permit is released **exactly once** per admitted operation
* The supplier is never invoked without a permit
* Rejected submissions never consume capacity

Violating any of these invariants is considered a bug.

> *Undocumented behavior, including snapshot timing and interleavings, must not be relied upon.*

---

## 10. Explicit non-goals

The following are *intentionally* out of scope:

* fairness or ordering guarantees
* request prioritization
* backpressure propagation
* retries or hedging
* adaptive or dynamic limits
* cancellation of underlying work

These concerns are better handled at higher layers, where more context is available.

---

## 11. Stability guarantees

`async-bulkhead` is pre-1.0 software.

* Semantic changes MAY occur between minor versions
* Breaking changes will be documented clearly
* Tests are treated as executable specifications

> All documented semantics are enforced by deterministic concurrency tests.

Users are encouraged to rely on documented behavior, not incidental implementation details.

---

## 12. Introspection snapshots

`async-bulkhead` exposes a small set of **introspection methods** intended for diagnostics, logging, and coarse-grained monitoring:

* `int limit()`
* `int available()`
* `int inFlight()`

These methods return **best-effort snapshots** of internal state at the moment they are called.

### Snapshot semantics

* Snapshot values are **not linearizable** with respect to submission or completion.
* Values may become stale immediately after being read due to concurrent activity.
* They MUST NOT be used to predict admission outcomes or enforce external coordination logic.

For example:

* `available() > 0` does **not** guarantee that a subsequent submission will be admitted.
* `inFlight()` may temporarily exceed expectations due to races between completion and observation.

These methods exist to:

* enrich logs and debug output
* support coarse operational visibility
* enable lightweight health reporting without introducing a metrics subsystem

They are explicitly **not** a concurrency control mechanism.

### ❌ Incorrect usage (anti-pattern)

The following pattern is **incorrect** and must not be used:

```java
// imports omitted for brevity:
// io.janbalangue.asyncbulkhead.Bulkhead
// io.janbalangue.asyncbulkhead.BulkheadRejectedException
if (bulkhead.available() > 0) {
bulkhead.submit(task);
}
```

This is invalid because `available()` is a **non-linearizable snapshot**.
The value may become stale immediately after it is read due to concurrent submissions or completions.

In particular:

* `available() > 0` does not guarantee that a subsequent `submit(...)` call will be admitted.
* Between the snapshot and the submission, capacity may already have been consumed by another thread.
* Using snapshot values to “pre-check” admission reintroduces race conditions that the bulkhead is explicitly designed to avoid.

Admission is decided only by `submit(...)` itself.

### ✅ Correct usage

Always attempt submission directly and handle rejection explicitly:

```java
CompletionStage<T> stage = bulkhead.submit(task);

stage.whenComplete((value, error) -> {
if (error instanceof BulkheadRejectedException) {
// explicit overload signal
}
});

```

Snapshot methods (`available()`, `inFlight()`, `limit()`) exist only for:

* diagnostics
* logging
* coarse operational visibility

They must never be used for coordination, admission prediction, or control flow.

> **Any attempt to use introspection snapshots to predict or influence admission behavior is a misuse of the API.**

---

## 13. Terminal classification (`TerminalKind`)

Each admitted operation terminates exactly once and is classified into one of the following terminal kinds:

* `SUCCESS` — the supplier’s stage completed normally
* `FAILURE` — the supplier’s stage completed exceptionally with a non-cancellation failure
* `CANCELLED` — the operation was cancelled

### Cancellation semantics

An operation is classified as `CANCELLED` if either of the following occurs:

* the caller cancels the `CompletionStage` returned by `submit(...)`, or
* the `CompletionStage` returned by the supplier completes with a `CancellationException`

In both cases:

* capacity is released exactly once
* listeners observe a terminal event with `TerminalKind.CANCELLED`
* cancellation is treated as a terminal outcome, not a failure

This classification exists to allow downstream systems to distinguish load shedding or caller abandonment from genuine execution failures.

> A terminal outcome is classified as `CANCELLED` if cancellation is observed directly or as the causal root of a wrapped exception (e.g. `CompletionException`).

---

## 14. Observability contract reminder

Both listeners and introspection snapshots are governed by the same guiding principles:

* They are **observational only**
* They are **best-effort**
* They MUST NOT affect bulkhead correctness or admission semantics
* Exceptions thrown by observers are ignored

This ensures that operational visibility can be added freely without compromising overload containment guarantees.

> Operational usage guidance and failure modes are covered in PRODUCTION.md.