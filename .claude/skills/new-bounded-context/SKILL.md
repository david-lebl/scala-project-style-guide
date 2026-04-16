---
name: new-bounded-context
description: Scaffold a new bounded context with *-core and *-infra modules following the two-layer split from guide 01. Use when the user asks to create a new bounded context, new module, new service area, or "add a context for X". Produces compiling skeleton with package structure, placeholder service, error enum, in-memory repo, and app wiring hook.
argument-hint: [context-name-kebab]
allowed-tools: Read Grep Glob Edit Write Bash(sbt *) Bash(mill *) Bash(ls *) Bash(mkdir *) Bash(test *)
---

Scaffold a new bounded context. Read the guides first; the scaffolding shape is derived from them, not hard-coded here.

## Pre-flight

1. Read `guides/01-project-structure.md` — the canonical two-layer split, package layout, and dependency rules live here.
2. Read `guides/06-bounded-contexts.md` — the Independence Rule (core never depends on another core) and the anti-corruption-layer pattern.
3. Read `CLAUDE.md` for the repo's root namespace (e.g. `com.myco`). If `com.myco` is wrong for this repo, grep an existing `*-core` source to find the actual base package.
4. Detect build tool (`build.sbt` vs `build.mill`).
5. Check the context name is new: `Glob modules/<name>-core` (or `examples/<name>-core`) must return nothing.

## Inputs

`$ARGUMENTS[0]` — required. Context name in kebab-case (`ordering`, `billing`, `catalogue`). Reject `-core` / `-infra` suffixes — they are added automatically. Reject camelCase.

## Checklist

1. Validate the name (kebab, letters + hyphens only, not empty, not already used).
2. Determine the base package from the existing modules. If the repo already has `com.myco.ordering`, the new context is `com.myco.<name>`.
3. Decide the module root. In this repo it's `examples/` (that's where `build.sbt` lives). In a consumer repo it's usually `modules/`.
4. Create directories per guide 01 §2:
   ```
   <root>/<name>-core/src/main/scala/<base-pkg>/<name>/
   <root>/<name>-core/src/main/scala/<base-pkg>/<name>/impl/
   <root>/<name>-core/src/test/scala/<base-pkg>/<name>/
   <root>/<name>-infra/src/main/scala/<base-pkg>/<name>/impl/postgres/
   <root>/<name>-infra/src/main/scala/<base-pkg>/<name>/impl/http/
   <root>/<name>-infra/src/main/scala/<base-pkg>/<name>/impl/adapters/
   <root>/<name>-infra/src/test/scala/<base-pkg>/<name>/impl/
   ```
5. Write placeholder files mirroring the pattern seen in the existing example (read `examples/ordering-core/src/main/scala/...` to see the exact style):
   - `<Name>Service.scala` — public trait with no methods yet plus accessor object stub.
   - `<Name>Error.scala` — sealed enum with one placeholder case and nested `Code`. Invoke the `new-error` recipe in guide 03 to shape it.
   - `impl/<Name>.scala` — domain entity stub `case class <Name>(id: <Name>Id, data: <Name>Data)`.
   - `impl/<Name>Repository.scala` — repository port trait returning `UIO`.
   - `impl/InMemory<Name>Repository.scala` — `Ref[Map]` implementation.
   - `impl/<Name>ServiceLive.scala` — `private[<name>] final class` with a companion `val layer`.
6. Update the build:
   - **sbt:** add two `lazy val <name>Core = project in file("<root>/<name>-core").settings(...)` and `<name>Infra` blocks in `build.sbt`. `<name>Infra` depends on `<name>Core`. Append both to the aggregate. Reuse settings from `orderingCore` / `orderingInfra`.
   - **mill:** add matching `object <name>Core` / `<name>Infra` in `build.mill` using the same shape as existing modules.
7. Add the module's `ZLayer` to the `app` module's main layer wiring. Follow guide 07.
8. Run `sbt compile` (or `mill __.compile`) — the skeleton must compile cleanly.
9. Print a tree diff of what was created.

## Invariants to enforce

- The public package is `<base-pkg>.<name>`. Only service, DTOs, error enum, and events go there.
- The internal package is `<base-pkg>.<name>.impl`, and all types in it are `private[<name>]`.
- `*-core` has **no** dependency on any other `*-core` module (guide 06 — Independence Rule).
- `*-infra` depends on its own `*-core`. If the new context consumes another context, that is an ACL adapter (use the `new-acl-adapter` skill), not a direct dep.
- The `Live` service class is `private[<name>] final class` — never `public class`.
- Chained package clauses (`package com.myco.<name>; package impl; package postgres`) in nested sub-packages (guide 01 §3).

## Output format

```
Created bounded context '<name>'

Files (<N>):
  <tree diff>

Build updated:
  <build.sbt | build.mill> — added modules '<name>Core', '<name>Infra'

Compile: OK (<duration>)

Next steps:
  1. Flesh out <Name>Service with actual verbs
  2. /new-error <name> <case-names>   (if error shape needs refinement)
  3. /new-repository <Name>           (if persistence is needed)
  4. Wire <name>Infra's layers into app/Main.scala
```

## Common mistakes

- Creating a third module (`*-api`, `*-domain`). Guide 01 §1 is explicit: two modules, not three.
- Forgetting the `impl` sub-package — putting domain types directly in `com.myco.<name>`.
- Adding the new core to another core's dependencies. Guide 06.
- Leaving the `Live` class `public`. Guide 04.
- Running `sbt compile` only at the new module; you must verify the whole aggregate still compiles.
- Hard-coding `com.myco` when the target repo uses a different base package.