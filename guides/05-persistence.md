# \[WIP\] 05 — Persistence

> Repository ports with domain types, DAO mapping in adapters, and non-uniform model strategy.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **Repository Ports Speak Domain Language**
   - Ports defined in `impl` with rich domain types — no intermediate record types
   - `trait OrderRepository: def save(order: Order): Task[Unit]`
   - Why the port returns `Task` (infrastructure-level), not `IO[DomainError, A]`

2. **DAO Mapping in the Adapter**
   - The adapter owns the domain↔DAO conversion
   - `OrderDAO.fromDomain(order: Order): OrderDAO` and `.toDomain: Order`
   - DAO types are `private[postgres]` — invisible outside the adapter
   - Complex mappings: nested entities, JSON-serialised fields, multi-table aggregates

3. **Non-Uniform Models in Practice**
   - Domain entity, DAO, DTO — three representations, three owners
   - When the DAO drifts far from the domain (and why that's fine)
   - Chimney for reducing mapping boilerplate

4. **In-Memory Implementations**
   - `Ref[Map[Id, Entity]]` as the canonical test double
   - Keeping in-memory repos in `*-core` alongside the domain
   - Simulating failures in tests

5. **Database Technology Adapters**
   - Quill: compile-time query generation, type-safe SQL
   - Doobie + zio-interop: handwritten SQL with connection management
   - Package layout: `impl.postgres/`, `impl.mysql/`, etc.
   - DB migrations: Flyway scripts in `*-infra/src/main/resources/db/migration`

6. **Transaction Boundaries**
   - Transactions scoped to the repository adapter, not the service
   - Aggregate-level transactions: one `save` = one transaction
   - Cross-aggregate consistency: eventual consistency via events, not distributed transactions

7. **Parse, Don't Validate (at the persistence boundary)**
   - `DAO.toDomain` returns the domain entity or fails
   - Handling corrupt/legacy data: fail loudly, migrate, or default
   - Logging parse failures for monitoring

---

*See also: [02 — Domain Modeling](02-domain-modeling.md), [04 — Service Design](04-service-design.md)*
