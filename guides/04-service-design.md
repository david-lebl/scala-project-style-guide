# \[WIP\] 04 — Service Design

> Public service traits, `Live` implementations, self-contained services, and input/output DTOs.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **Anatomy of a Service**
   - Public trait in the root package — the stable contract
   - `Live` implementation in `impl` — the changeable internals
   - ZIO accessor methods in the companion object

2. **Public DTOs — Input and Output Types**
   - Input case classes: deliberately simple, shaped for callers
   - View/response case classes: read-model, not the domain entity
   - Why the public surface does not expose domain types
   - Shared ID types (e.g. `OrderId`) in the public package

3. **The `Live` Implementation**
   - Data flow: public DTO in → parse to domain → business logic → persist → map to view out
   - Internal parse/map methods — private, free to change
   - Dependency on repository ports and other internal ports
   - `private[<context>]` scoping

4. **Self-Contained Services**
   - A service should be usable with only its `ZLayer` dependencies provided
   - No implicit global state, no thread-local context
   - Explicit dependencies via constructor (ZLayer.fromFunction)

5. **Multiple Services in One Bounded Context**
   - When two services share a domain model — put them in the same `*-core`
   - Each gets its own public trait, DTOs, error enum (or shared error enum)
   - They can share repository ports and domain types inside `impl`
   - Heuristic: if they share >50% of vocabulary, merge; otherwise separate context

6. **Service Method Conventions**
   - Return `IO[ContextError, View]` for operations that can fail with domain errors
   - Use `Task[A]` (via `.orDie`) only for infrastructure-level operations
   - Naming: `checkout`, `getOrder`, `cancelOrder` — verb-first, domain language
   - Avoid overly generic names (`process`, `handle`, `execute`)

7. **Stateless Services**
   - Services are stateless — all state lives in repositories
   - No mutable fields in `Live` implementations
   - Configuration is a dependency, not internal state

---

*See also: [03 — Error Model](03-error-model.md), [07 — Dependency Injection](07-dependency-injection.md)*
