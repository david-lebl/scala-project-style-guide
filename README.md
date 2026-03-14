# Scala Backend Style Guide

A comprehensive, opinionated style guide for building backend services in **Scala 3 with ZIO** inside a monorepo.

The guide is designed around hexagonal architecture and Domain-Driven Design principles.
Each bounded context is a self-contained hexagon that can live inside a modulith today and be extracted into a
standalone microservice tomorrow — without rewriting its internals.

> **Target stack:** Scala 3, ZIO 2, sbt, zio-http / tapir, zio-json, quill / doobie

> Inspired by:
> 1. [Modeling in Scala, part 1](https://kubuszok.com/2024/modeling-in-scala-part-1/) by Mateusz Kubuszok
> 2. [Diamond Architecture](https://github.com/DevInsideYou/diamond-architecture) by DevInsideYou
> 3. [Domain-Driven Design Made Functional](https://share.google/GzSThdgT1UOdJtIWA) by Scott Wlaschin.

---

## Quick Reference

| #  | Guide                                                     | What it covers                                                                                                                          |
|----|-----------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| 01 | [Project Structure](guides/01-project-structure.md)       | Two-layer module split (`*-core` / `*-infra`), build definition, package layout, chained package clauses, dependency rules              |
| 02 | [Domain Modeling](guides/02-domain-modeling.md)           | ADTs, strong types, opaque types, smart constructors, entities vs values, nesting, making illegal states unrepresentable                |
| 03 | [Error Model](guides/03-error-model.md)                   | Error ADTs per bounded context, error messages & codes, ZIO error channel strategy, HTTP error mapping, error composition across layers |
| 04 | [Service Design](guides/04-service-design.md)             | Public service traits, `Live` implementations, self-contained services, input/output DTOs, multiple services per context                |
| 05 | [Persistence](guides/05-persistence.md)                   | Repository ports with domain types, DAO mapping in adapters, non-uniform models, parse-don't-validate                                   |
| 06 | [Bounded Contexts](guides/06-bounded-contexts.md)         | Anti-corruption layer, cross-context ports & adapters, merging heuristic, event-driven decoupling                                       |
| 07 | [Dependency Injection](guides/07-dependency-injection.md) | ZLayer patterns, module composition, test layers, avoiding God wiring                                                                   |
| 08 | [Configuration](guides/08-configuration.md)               | Per-module config, zio-config, layered loading, secrets, environment-specific overrides                                                 |
| 09 | [Testing](guides/09-testing.md)                           | In-memory layers, property-based testing, integration tests with containers, test structure                                             |
| 10 | [API Design](guides/10-api-design.md)                     | HTTP routes, JSON codecs, versioning, OpenAPI, request/response conventions                                                             |
| 11 | [Extraction](guides/11-extraction.md)                     | Modulith → microservice, client stubs, remote adapters, event-driven split                                                              |

### Architecture Decision Records

| ADR                                                               | Decision                                                    |
|-------------------------------------------------------------------|-------------------------------------------------------------|
| [ADR-001](decisions/001-zio-over-cats-effect.md)                  | ZIO over Cats Effect                                        |
| [ADR-002](decisions/002-anti-corruption-over-direct-core-deps.md) | Anti-corruption layer over direct core-to-core dependencies |

### Compilable Examples

The [`examples/`](examples/) directory contains a minimal but compilable multi-module sbt project demonstrating the
patterns from all guides. See [`examples/README.md`](examples/README.md) for setup instructions.

---

## The Cardinal Rule

> **The core module's public surface is a stable contract. Everything in the `impl` package is free to change.**

Other bounded contexts and infrastructure modules depend on the core module's public package.
Everything behind it — domain models, business rules, repository ports — can be refactored at will.

## The Independence Rule

> **Core modules never depend on other core modules.**

Every `*-core` depends only on `commons`. Cross-context communication uses anti-corruption ports (defined in the
consumer's core) and adapters (implemented in the consumer's infra module).

---

## Module Structure at a Glance

```
monorepo/
├── modules/
│   ├── commons/                 ← shared kernel (Money, Country, …)
│   │
│   ├── ordering
│   │   ├── 01-c-core/           ← 1st layer: public contract + domain + Live
│   │   │   └── com.myco.ordering         (public: service, DTOs, errors)
│   │   │   └── com.myco.ordering.impl    (private: domain, ports, Live)
│   │   │
│   │   └── 02-c-infra/          ← 2nd layer: persistence, HTTP, adapters
│   │       └── com.myco.ordering.impl.postgres
│   │       └── com.myco.ordering.impl.http
│   │
│   ├── shipping
│   │   └── ...
│   │   
│   └── app/                     
│       └── client-app/          ← client, wires required layers together
│       └── server-app/          ← server, wires required layers together
```


---

## Recommended Libraries

| Purpose         | Library                          |
|-----------------|----------------------------------|
| Effect system   | ZIO 2                            |
| HTTP            | zio-http / tapir + zio-http      |
| JSON            | zio-json                         |
| Database        | quill-zio / doobie + zio-interop |
| Config          | zio-config                       |
| Testing         | zio-test                         |
| Type transforms | Chimney                          |
| Refined types   | iron                             |

---

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md) for how to propose changes to this guide.

## References

- [Kubuszok — Modeling in Scala, Part 1](https://kubuszok.com/2024/modeling-in-scala-part-1/)
- [Wlaschin — Domain Modeling Made Functional](https://pragprog.com/titles/swdddf/domain-modeling-made-functional/)