# \[WIP\] 11 — Extraction

> Modulith → microservice, client stubs, remote adapters, and event-driven splits.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **Why the Module Split Enables Extraction**
   - `*-core` + `*-infra` is already a self-contained hexagon
   - The public service trait is the extraction seam
   - Anti-corruption ports mean consumers don't reference the extracted context's internals

2. **Extraction Steps**
   - Step 1: Publish `*-core` as a library artefact (Maven/Ivy)
   - Step 2: Move `*-core` + `*-infra` into own repository and deployable
   - Step 3: In the monolith, replace the in-process adapter with a remote adapter (HTTP/gRPC client)
   - Step 4: The consuming context's `Live` service and domain code — zero changes

3. **Remote Adapter Implementation**
   - Implements the anti-corruption port (e.g. `OrderingPort`)
   - Calls the extracted service over HTTP/gRPC
   - Translates HTTP errors → port-level errors (`Option.None`, defects)
   - Timeout, retry, circuit-breaker configuration

4. **Before/After Diagram**
   - Modulith: app → ordering-infra → ordering-core, shipping-infra → adapter → OrderService (in-process)
   - After extraction: ordering-service (own deployable), main-app → shipping-infra → remote adapter → HTTP → ordering-service

5. **Event-Driven Extraction**
   - Replace synchronous port call with event subscription
   - Publisher emits events to Kafka/messaging
   - Consumer's adapter subscribes and updates a local projection
   - The port now reads from the local projection instead of calling remotely

6. **Partial Extraction**
   - Extract only the write path (commands) while keeping the read path in the monolith
   - CQRS-style: command service as microservice, query service as local projection
   - Useful when read patterns differ from write patterns

7. **Pre-Extraction Checklist**
   - [ ] Context has its own `*-core` and `*-infra` modules
   - [ ] No other `*-core` depends on this context's `*-core`
   - [ ] All cross-context communication goes through anti-corruption ports
   - [ ] Context has its own database schema (no shared tables)
   - [ ] Health check, metrics, and logging are self-contained

---

*See also: [06 — Bounded Contexts](06-bounded-contexts.md), [01 — Project Structure](01-project-structure.md)*
