---
name: new-repository
description: Scaffold a repository port plus in-memory test implementation plus a Postgres DAO adapter skeleton for a domain entity, following guide 05. Use when the user asks to add a repository, add persistence, store X in the database, or needs a port+adapter for a new entity.
argument-hint: [entity-name]
allowed-tools: Read Grep Glob Edit Write Bash(sbt *) Bash(mill *) Bash(test *)
---

Generate the three-file set for a repository: port (core), in-memory (core, tests), DAO + Postgres adapter (infra). The port speaks domain, not DAO.

## Pre-flight

1. Read `guides/05-persistence.md` in full. Key ideas this skill depends on:
   - Port methods return `UIO[...]` — no infrastructure errors leak into the domain.
   - The port trait uses domain types, never DAOs.
   - DAO ↔ domain mapping lives in the adapter.
   - Transaction boundaries at adapter level.
   - Corrupt-data handling: parse on load, report explicitly.
2. Read `guides/02-domain-modeling.md` §2 to confirm the id / data / entity shape is in place for this entity.
3. Locate the entity: the file `<ctx>-core/.../impl/<Name>.scala` must already exist. If not, tell the user to run `/new-domain-model <Name>` first.

## Inputs

- `$ARGUMENTS[0]` — required. Entity name in PascalCase (`Order`, `Customer`). Must match an existing domain model.

## Checklist

1. Resolve the bounded context that owns the entity.
2. Read guide 05 §2–§5 and note the canonical port / adapter shape.
3. Generate the port in `<ctx>-core/src/main/scala/<pkg>/<ctx>/impl/<Name>Repository.scala`:
   - `private[<ctx>] trait <Name>Repository`
   - Methods: `save(entity: <Name>): UIO[Unit]`, `get(id: <Name>Id): UIO[Option[<Name>]]`, `delete(id: <Name>Id): UIO[Unit]`. Add whatever the user asked for; do not invent extras.
   - Scaladoc on every method describing the domain contract (not the SQL).
4. Generate the in-memory implementation in `<ctx>-core/src/main/scala/<pkg>/<ctx>/impl/InMemory<Name>Repository.scala`:
   - `private[<ctx>] final class InMemory<Name>Repository(ref: Ref[Map[<Name>Id, <Name>]])`
   - Companion `val layer: ULayer[<Name>Repository] = ZLayer { Ref.make(Map.empty[<Name>Id, <Name>]).map(new InMemory<Name>Repository(_)) }`.
5. Generate the DAO skeleton in `<ctx>-infra/src/main/scala/<pkg>/<ctx>/impl/postgres/<Name>DAO.scala`:
   - `private[<ctx>] final case class <Name>DAO(...)` with primitive fields matching the SQL schema.
   - `object <Name>DAO { def fromDomain(e: <Name>): <Name>DAO = ???; def toDomain(d: <Name>DAO): Either[<Ctx>Error, <Name>] = ??? }`. Leave the mapping bodies as `???` — this skill does not know the schema yet. Comment above each `???` describes what to fill in.
6. Generate the Postgres adapter in `<ctx>-infra/src/main/scala/<pkg>/<ctx>/impl/postgres/Postgres<Name>Repository.scala`:
   - Depends on the SQL client used by the repo (grep for existing adapters to copy the style: `quill`, `doobie`, plain `zio-jdbc`, etc.).
   - `UIO`-returning methods via `.orDie` on the database IO (per guide 03 — infra failures are defects).
   - Provides a `val layer: ZLayer[<SqlClient>, Nothing, <Name>Repository]`.
7. Register the layer: in-memory for tests, Postgres for production, wired in `app/Main.scala`.
8. Compile.

## Invariants to enforce

- Port lives in `impl/` and is `private[<ctx>]`. It must not be re-exported.
- Port uses domain types — `<Name>`, `<Name>Id` — never `<Name>DAO`.
- Port returns `UIO`. Infrastructure errors are `.orDie`, not surfaced as `IO[Err, _]`.
- DAO mapping is in the infra adapter, never in `*-core`.
- In-memory implementation matches the port exactly — useful as a test double with zero deviation.
- Transaction boundary: one port method = one database transaction at the adapter.
- On load, if `toDomain` fails (corrupt row), the adapter uses `.orDieWith` with a clear message. Do not silently filter.

## Output format

```
Scaffolded <Name> repository

Files:
  <ctx>-core/.../impl/<Name>Repository.scala
  <ctx>-core/.../impl/InMemory<Name>Repository.scala
  <ctx>-infra/.../impl/postgres/<Name>DAO.scala          (mapping stubs — fill in)
  <ctx>-infra/.../impl/postgres/Postgres<Name>Repository.scala

Layers:
  InMemory<Name>Repository.layer   — wire into test layers
  Postgres<Name>Repository.layer   — wire into app/Main.scala

Compile: OK (with ??? stubs in <Name>DAO)

Next steps:
  - Fill in <Name>DAO.fromDomain / toDomain with the actual schema
  - Add the migration SQL (not scaffolded)
```

## Common mistakes

- Port methods returning `IO[SqlError, _]`. Guide 05 and guide 03 both say no — use `UIO`, let infra die.
- DAO type in the port signature (`save(dao: <Name>DAO)`). Port speaks domain only.
- DAO mapping functions living in `*-core`. They belong in the adapter.
- Shared in-memory `Ref` across test runs — causes flaky isolation. Each layer build creates a fresh `Ref`.
- Skipping the `toDomain` parse: loading raw primitives and pretending they are a refined domain entity.