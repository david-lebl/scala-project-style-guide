# ADR-002: Anti-Corruption Layer over Direct Core-to-Core Dependencies

**Status:** Accepted
**Date:** 2026-01-01

## Context

When a bounded context (e.g., Shipping) needs data from another context (e.g., Ordering), we must decide how to wire this dependency. The two options are:

1. **Direct dependency:** `shipping-core` depends on `ordering-core` and uses `OrderService` directly.
2. **Anti-corruption layer:** `shipping-core` defines its own port (`OrderingPort`), and `shipping-infra` provides an adapter that bridges to `OrderService`.

## Decision

**Anti-corruption layer.** Core modules never depend on other core modules. Cross-context communication always goes through a port (in the consumer's core) and an adapter (in the consumer's infra).

## Reasons

- **Independence.** Each core module can be developed, tested, and extracted independently.
- **No circular dependencies.** If ordering later needs shipping data, both can define their own ports without creating a cycle.
- **Domain language isolation.** Shipping speaks its own language (`OrderSnapshot`) — it's not coupled to ordering's view types, which may evolve independently.
- **Mechanical extraction.** When extracting a context to a microservice, the adapter is the only thing that changes — swap in-process call for HTTP/gRPC.

## Trade-offs

- **More code.** Each cross-context dependency adds a port trait, a snapshot type, and an adapter class.
- **Indirection.** Developers must trace through port → adapter → service when debugging cross-context flows.
- **Overhead for tightly coupled contexts.** If two services truly share vocabulary and evolve together, the anti-corruption layer adds ceremony without value.

## Mitigation

If the anti-corruption layer feels like pure boilerplate (snapshot types mirror the other context's DTOs exactly), the two services probably belong in the **same bounded context** as sibling services sharing one domain model. See [06 — Bounded Contexts §4](../guides/06-bounded-contexts.md).

## Consequences

- `*-core` depends only on `commons`.
- `*-infra` depends on own `*-core` + other `*-core` modules (for adapter implementation).
- All cross-context data is translated at the adapter boundary.
