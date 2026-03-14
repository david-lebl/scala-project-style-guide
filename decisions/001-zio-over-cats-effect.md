# ADR-001: ZIO over Cats Effect

**Status:** Accepted
**Date:** 2026-01-01

## Context

We need an effect system for our Scala 3 backend. The two leading options are ZIO 2 and Cats Effect 3 (with the Typelevel ecosystem).

## Decision

We chose **ZIO 2**.

## Reasons

- **Typed error channel.** `ZIO[R, E, A]` gives us a typed error parameter `E` that aligns with our domain error enum strategy (see [03 — Error Model](../guides/03-error-model.md)). Cats Effect's `IO[A]` uses `Throwable` for all errors, requiring monad transformers (`EitherT`) or custom error-handling patterns to achieve the same.
- **Built-in dependency injection.** `ZLayer` provides compile-time verified dependency injection without an external library. This aligns with our module composition strategy.
- **Batteries included.** ZIO ships with `Ref`, `Queue`, `Hub`, `Schedule`, `Scope`, and test utilities. Fewer external dependencies to evaluate and maintain.

## Trade-offs

- Cats Effect has a larger community and more third-party library integrations.
- Tagless final / MTL style (common in Typelevel) is not idiomatic ZIO — we commit to ZIO-specific patterns.
- ZIO's learning curve is steeper for developers coming from raw Scala / Akka, but more approachable than Cats Effect for those new to functional programming.

## Consequences

- All application service traits return `IO[E, A]` or `Task[A]`.
- Dependency injection uses `ZLayer` exclusively.
- Testing uses `zio-test`.
