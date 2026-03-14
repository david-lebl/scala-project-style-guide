# 01 — Project Structure

> Module layout, build definition, package conventions, and dependency rules for a Scala 3 + ZIO monorepo.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **The Two-Layer Split**
   - 1st layer: `*-core` — public contract + domain internals + Live implementation
   - 2nd layer: `*-infra` — persistence adapters, HTTP routes, anti-corruption adapters
   - Why two layers and not three (no separate `*-api` module)
   - What "stable contract" vs "free to change" means in practice

2. **Canonical Layout**
   - Full directory tree for a bounded context
   - Public package (`com.myco.<context>`) — what goes here
   - Impl package (`com.myco.<context>.impl`) — what goes here
   - Infra sub-packages (`impl.postgres`, `impl.http`, `impl.adapters`)

3. **Chained Package Clauses**
   - How chained `package` declarations enable zero-import access
   - Chained vs single-line form — when each is used
   - Convention: always chain in `impl` sub-packages

4. **Build Definition (sbt)**
   - `build.sbt` template for a multi-context monorepo
   - Dependency rules: core → commons only, infra → own core + other cores, app → all infra
   - Common sbt settings (Scala version, compiler flags, shared dependencies)
   - Sub-project naming conventions

5. **Package Visibility as Architecture**
   - `private[<context>]` on all `impl` types
   - How this enforces that other contexts only see the public contract
   - `private[postgres]` for DAO types — adapter-internal only

6. **The `commons` Module**
   - What belongs in the shared kernel (Money, Country, types used across contexts)
   - What does NOT belong (domain-specific types that feel universal but aren't)
   - Versioning and backward compatibility considerations

7. **What Goes Where — Summary Table**
   - Mapping of artefact → package → build module → visibility → changeability

8. **Checklist — Adding a New Module**
   - Step-by-step for creating a new bounded context from scratch
   - When to add a new context vs add a service to an existing context

---

*See also: [02 — Domain Modeling](02-domain-modeling.md), [06 — Bounded Contexts](06-bounded-contexts.md)*
