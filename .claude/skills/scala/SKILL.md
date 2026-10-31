---
name: scala
description: Scala 3 coding style, tooling, functional programming and effect-style (ZIO) guidance for backend services built with hexagonal architecture and Domain-Driven Design. Auto-load for any task involving Scala code.
---

# Scala tooling

* Drive the build with sbt (1.x) from the project root:
  * multiple commands go in one quoted argument: `sbt "clean; compile; test"`
  * one module: `sbt "orderingCore/test"`; one suite:
    `sbt "orderingCore/testOnly com.myco.ordering.impl.OrderServiceLiveSpec"`
  * slash syntax only (`Test / compile`); shared settings via `ThisBuild /` or
    a `commonSettings` sequence.
* resolve dependency versions from
  `https://repo1.maven.org/maven2/<group-with-slashes>/<artifact>/maven-metadata.xml`,
  including the Scala suffix (`dev/zio/zio_3`). Take the latest stable
  `<version>`; `<release>` may be a prerelease (`RC`, `M<n>`, `SNAP`, `alpha`,
  `beta`). NEVER use `search.maven.org/solrsearch` (stale by months). Find
  unknown coordinates via `https://index.scala-lang.org/api/autocomplete?q=<name>`.
* before committing, ALWAYS run `sbt scalafmtAll`.
* the project MUST compile with zero warnings. `scalacOptions` include
  `-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`, `-Xfatal-warnings`.
  Fix warnings in code; use `@nowarn` only for generated code or unfixable
  third-party issues, with a comment explaining why.

# Coding style

* ALWAYS use braceless syntax — do not use `{}`.
* responsibilities MUST be segregated between appropriately named entities.
* before creating or moving a `.scala` file, decide its package, filename, and
  each top-level declaration's visibility (narrowest the call sites allow).
  Read [Code Organization and Visibility](110-code-organization.md).
* each function MUST handle exactly one concern — a single logical operation,
  or a short orchestration of named steps.
* every public and package-private `def`, `val` and `given` MUST have an
  explicit result type.
* use `scala.util.chaining` `.pipe`/`.tap` to drop single-use `val`s that only
  feed the next line; keep a named `val` when the name documents intent.
* tests MUST be targeted — each test covers exactly one scenario. No
  overlapping or redundant tests.

# Functional programming

Details: [Domain Modeling](120-domain-modeling.md), [Functional
Principles](130-functional-principles.md).

* pure functions, immutable data, ADTs, total functions. Domain logic is pure;
  effects live at the edges.
* NEVER `var` fields, NEVER shared mutable state. Shared state is a `Ref` (or
  sits behind a repository port). Use only immutable collections.
* model state as an immutable case class; transitions are pure
  `State => State` functions.
* make illegal states unrepresentable: enums, sealed ADTs, opaque types,
  `NonEmptyChunk`, `Option` for absence (never `null`).
* NEVER raw `String`/`Int`/`Long`/`Boolean` for domain concepts — opaque types
  or case classes. Public DTOs are the only place primitives are allowed.
* replace `Boolean` parameters and return values with two-case enums.
* smart constructors return `Either` — never `require`, `assert` or `throw`.
* no partial functions in main code: no `.get`, `.head`, `asInstanceOf`,
  `Enum.valueOf`, non-exhaustive matches.

# Effect style

Details: [Effect Fundamentals](200-effect-fundamentals.md), [Error
Model](210-error-model.md), [Resources and
Lifecycle](220-resources-and-lifecycle.md), [Concurrency and
Streaming](230-concurrency-and-streaming.md).

* every side effect is a `ZIO` value. Wrap it where it happens:
  `ZIO.attempt`, `ZIO.attemptBlocking` for blocking I/O, `ZIO.fromEither`.
* NEVER `unsafeRun`, `Unsafe.unsafe`, `Await` or `Future` in core or infra.
  The program runs once, in `app`'s `ZIOAppDefault`.
* time, randomness and IDs come from `Clock`, `Random` (or an injected
  `IdGenerator` port) — NEVER `Instant.now()`, `UUID.randomUUID()`,
  `Thread.sleep`.
* domain errors go in the typed channel as the context's error enum
  (`IO[OrderError, A]`). Infrastructure failures are defects: adapters call
  `.orDie`, ports return `UIO`/`IO[DomainError, A]` — NEVER `Task` in a
  service or port signature.
* resources are acquired with `ZIO.acquireRelease` / `ZLayer.scoped`; never
  hand-written `try/finally`.
* concurrency is structured: `zipPar`, `foreachPar`, `race`, `.forkScoped`.
  NEVER `.forkDaemon`, raw threads, locks or `AtomicReference`.
* NEVER materialize unbounded data — stream with `ZStream` or page in the
  repository port.

The same principles apply to Cats Effect and Kyo; use their equivalent
primitives (`IO`/`Resource`/`Ref`, Kyo's `Abort`/`Resource`/`Env`).

# Architecture

Details: [Project Structure](100-project-structure.md) and the Application
Architecture chapters below.

* every bounded context is two sbt modules: `<context>-core` (public contract
  in `com.myco.<context>`, domain internals in `…impl`, all
  `private[<context>]`) and `<context>-infra` (adapters in `…impl.<tech>`).
  Direction: `app` → `*-infra` → `*-core` → `commons`.
* **Cardinal Rule:** the public package of `*-core` is a stable contract;
  everything in `impl` is free to change.
* **Independence Rule:** core modules never depend on other core modules.
  Cross-context calls go through a port in the consumer's core and an adapter
  in the consumer's infra.
* files under `impl` use chained package clauses (`package com.myco.ordering`
  / `package impl` / `package postgres`).
* service = public trait + accessor companion + `private[<context>] final case
  class …Live` with a companion `val layer`. DTOs in, views out.
* repository ports take and return domain types; DAO mapping lives in the
  infra adapter.
* `ZLayer` wiring happens only in `app` and tests.

# Use-Case Guide

BEFORE writing code that touches a topic below, read the relevant chapter(s)
in full from this skill's directory. Follow every `> **Required:**` /
`> **Important:**` / `> **Warning:**` callout exactly — do NOT substitute
another approach.

## Foundations

- [Project Structure](100-project-structure.md) — two-layer `*-core`/`*-infra` split, public vs `impl` packages, chained package clauses, sbt `dependsOn` rules, `commons`, new-context checklist
- [Code Organization and Visibility](110-code-organization.md) — top-level visibility (`private[<context>]`, `private[<tech>]`), file-per-type rule, package naming, enforcing boundaries
- [Domain Modeling](120-domain-modeling.md) — no primitives, opaque types, ADTs, illegal states unrepresentable, smart constructors, entity = id + data, enums instead of `Boolean`, domain ≠ DTO ≠ DAO
- [Functional Principles](130-functional-principles.md) — referential transparency, total functions, immutability, expressions over statements, exhaustive matching, explicit types, Scala 3 feature discipline

## Effects

- [Effect Fundamentals](200-effect-fundamentals.md) — `UIO`/`IO`/`Task` choice, suspending side effects, `attemptBlocking`, for-comprehensions, lawful `Clock`/`Random`, discarding results, `ZIOAppDefault`, logging
- [Error Model](210-error-model.md) — error enum per context with `Code`, typed channel vs defects, `.orDie`/`refineOrDie` in adapters, short-circuit vs `validatePar`, `someOrFail`, cross-context translation, anti-patterns
- [Resources and Lifecycle](220-resources-and-lifecycle.md) — `Scope`, `acquireRelease`, `ZIO.scoped`, `ZLayer.scoped`, entry point, background jobs with `Schedule` + `forkScoped`, graceful shutdown
- [Concurrency and Streaming](230-concurrency-and-streaming.md) — `zipPar`/`foreachPar`/`race`, fork scoping, `Ref`/`Ref.Synchronized`, bounded `Queue`, `Hub`, `Promise`, `ZStream` (`mapZIOPar`, `mapAccum`, `groupedWithin`), blocking, interruption

## Application Architecture

- [Service Design](300-service-design.md) — public trait + accessors, input/output DTOs, `Live` (parse → logic → persist → view), naming, stateless services, multiple services per context
- [Persistence](310-persistence.md) — repository ports in domain language, `UIO` ports, DAO mapping in adapters, parse on load, in-memory `Ref` repositories, transaction boundaries, pagination
- [Bounded Contexts](320-bounded-contexts.md) — Independence Rule, anti-corruption port + adapter, error translation, snapshot types, merging heuristic, events
- [Dependency Injection](330-dependency-injection.md) — `ZLayer.fromFunction`/`derive`/`scoped`, `.provide` in `app`, per-module bundles, test layers
- [Configuration](340-configuration.md) — config as layer, per-module config classes, `deriveConfig`, `Config.Secret`, fail fast
- [API Design](350-api-design.md) — zio-http routes in `impl.http`, zio-json codecs, error → HTTP via `Code.httpStatus`, defects → 500, URL and versioning conventions, auth, pagination
- [Extraction](360-extraction.md) — modulith → microservice, remote adapters (timeout, retry, circuit breaker), event-driven and partial extraction, checklist

## Testing

- [Testing](400-testing.md) — what to test where, no mocks, in-memory layers, `.exit` + `fails`, `TestClock`/`TestRandom`, property-based tests with `Gen`/`check`, container-based adapter tests
