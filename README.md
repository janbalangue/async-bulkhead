# Async Bulkhead (Java)

⚠️ **Early-stage, design-first project**

This repository is building a small, opinionated **async bulkhead** for Java with explicit, tested
semantics around overload behavior (bounded concurrency, bounded waiting, queue wait timeouts, and observability).

There is no production-ready release yet.

---

## Goals

The core primitive is intended to help services remain stable under load by providing:

- **Concurrency limiting** (max in-flight async operations)
- **Bounded waiting** (bounded queue)
- **Fail-fast saturation behavior** (explicit rejection)
- **Latency protection** (queue wait timeouts)
- **Metrics hooks** (integration-friendly)

This is intentionally **not** a full resilience framework.

---

## Current status

- ✅ Multi-module structure in place (Maven)
- ✅ Semantics, guarantees, and non-goals documented
- 🚧 Core implementation in progress
- 🚧 APIs subject to change (pre-1.0)

If you need a ready-to-use library, check back later.

---

## Design-first approach

All behavior, guarantees, and non-goals are defined **before** implementation.

Please read **[DESIGN.md](DESIGN.md)** before opening issues or suggesting features.

---

## Repository layout

---

## Build requirements

- **Java 17** (target)
- Maven 3.x

Basic build (once code exists):

```bash
mvn -q verify
