# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

This repository is an **opinionated style guide** for building backend services in Scala 3 with ZIO inside a monorepo, applying hexagonal architecture and Domain-Driven Design. It consists of:

- `guides/` — 11 markdown guides (01–11) covering every layer of the architecture
- `decisions/` — Architecture Decision Records (ADRs)
- `examples/` — A compilable multi-module sbt project demonstrating all guide patterns
- `GUIDELINE.md` — A comprehensive single-document version of all guides

The goal of the design: each bounded context is a self-contained hexagon that can live in a modulith today and be extracted as a standalone microservice tomorrow without rewriting its internals.

## Working with the Examples Project

The `examples/` directory is a real sbt project:

```bash
cd examples
sbt compile        # verify everything compiles
sbt test           # run all tests
sbt "app/run"      # start the app
```

To run tests for a single module:
```bash
sbt "orderingCore/test"
sbt "orderingInfra/test"
```

The examples use Scala 3.5.2, ZIO 2.1.14, with `-Xfatal-warnings` and `-Wunused:all` enforced.

## Architecture: Two-Layer Module Split

Every bounded context is split into exactly two sbt modules:

- **`*-core` (1st layer):** Public contract (`OrderService` trait, DTOs, `OrderError` enum) lives in `com.myco.ordering`. Private domain internals (rich domain model, repository port, `Live` implementation) live in `com.myco.ordering.impl` with `private[ordering]` visibility.
- **`*-infra` (2nd layer):** Infrastructure adapters (Postgres, HTTP, anti-corruption adapters) in `com.myco.ordering.impl.postgres`, etc. Depends on `*-core`.

Dependency direction: `app` → `*-infra` → `*-core` → `commons`.

**The Cardinal Rule:** The `*-core` public package is a stable contract. Everything in `impl` is free to change.

**The Independence Rule:** Core modules never depend on other core modules — only on `commons`. Cross-context communication goes through an anti-corruption layer (port defined in the consumer's core, adapter in the consumer's infra).

## Key Patterns in the Guides

**Chained package clauses** — sub-packages under `impl` use chained declarations to get zero-import access to all `impl` types:
```scala
package com.myco.ordering
package impl
package postgres
```

**Service pattern** — public trait in `com.myco.ordering`, `Live` implementation in `com.myco.ordering.impl` as `private[ordering] final class OrderServiceLive`. Accessor methods are generated via `ZIO.serviceWithZIO`.

**Error model** — each context has one sealed error enum in its public package, with a nested `Code` enum and `httpStatus`/`message` fields. Errors compose across layers via `.mapError`.

**Repository ports** — defined in `impl` with domain types (not DAOs). DAO mapping lives in the infra adapter, never in the core.

**ZLayer wiring** — `Live` implementations provide their own `ZLayer` as a companion `val layer`. The `app` module aggregates layers; no wiring logic appears in core or infra.

## Guide Map

| # | Guide | Key decisions |
|---|-------|---------------|
| 01 | Project Structure | Module naming, package layout, dependency rules |
| 02 | Domain Modeling | Opaque types, smart constructors, ADTs, illegal states |
| 03 | Error Model | Error ADTs with codes, ZIO error channel, HTTP mapping |
| 04 | Service Design | Public traits, Live class, input/output DTOs |
| 05 | Persistence | Repository ports, DAO adapters, parse-don't-validate |
| 06 | Bounded Contexts | Anti-corruption layer, cross-context ports |
| 07 | Dependency Injection | ZLayer patterns, test layers |
| 08 | Configuration | Per-module config, zio-config |
| 09 | Testing | In-memory layers, property-based, integration with containers |
| 10 | API Design | HTTP routes, JSON codecs, OpenAPI |
| 11 | Extraction | Modulith → microservice, remote adapters |

## ADRs

- **ADR-001** (`decisions/001-zio-over-cats-effect.md`): ZIO chosen for its typed error channel, built-in `ZLayer` DI, and batteries-included test utilities.
- **ADR-002** (`decisions/002-anti-corruption-over-direct-core-deps.md`): Core modules never depend on each other — enables independent extraction and prevents circular dependencies.