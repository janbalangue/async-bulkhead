# Async Bulkhead (Java)

A small, opinionated **async bulkhead** for Java that protects services from overload
by **bounding concurrent work, bounding waiting work, and enforcing queue wait timeouts**.

> Semaphores limit concurrency.  
> This library defines **overload behavior**.

---

## Why this exists

In production systems, overload usually fails like this:

- concurrency spikes
- queues grow silently
- latency explodes
- everything times out at once

Most teams try to fix this with `Semaphore`, ad-hoc queues, or thread blocking.
That solves *concurrency*, but not **backpressure, latency protection, or observability**.

This library provides a **single, correct primitive** for async overload control.

---

## What this library does

✅ Limits **in-flight async work**  
✅ Bounds **waiting work** with a FIFO queue  
✅ Fails fast when saturated  
✅ Enforces **queue wait timeouts** to protect tail latency  
✅ Exposes **metrics hooks** for production visibility

❌ It does **not** execute tasks on its own threads  
❌ It does **not** try to be a full resilience framework

---

## Quickstart

```java
AsyncBulkhead bulkhead =
    AsyncBulkhead.builder()
        .maxConcurrent(50)
        .maxQueue(200)
        .maxQueueWait(Duration.ofMillis(100))
        .listener(metricsListener)
        .build();

CompletableFuture<Response> future =
    bulkhead.submit(() -> httpClient.callAsync(request));
