---
name: new-service
description: Scaffold a new service (public trait + Live class + DTOs + ZLayer) inside a bounded context, following guide 04. Use when the user asks to add a service, add an operation / use case to a context, or create a new verb-based API like PlaceOrder, CancelOrder. Generates accessor methods via ZIO.serviceWithZIO.
argument-hint: [service-name] [verb1,verb2,...?]
allowed-tools: Read Grep Glob Edit Write Bash(sbt *) Bash(mill *) Bash(test *)
---

Create a service trait in the public package and its `Live` implementation in `impl`, matching guide 04.

## Pre-flight

1. Read `guides/04-service-design.md` in full. If `guides/04-service-design-capabilities.md` also exists, read it too — some contexts in this repo prefer the capabilities variant. Ask the user which style if both are present and the context has no existing service to copy.
2. Read `guides/07-dependency-injection.md` for the ZLayer / `val layer` companion pattern.
3. Locate the bounded context: `<root>/<ctx>-core/src/main/scala/<base-pkg>/<ctx>/`.
4. Read an existing service in this repo (e.g. `OrderService.scala` / `OrderServiceLive.scala`) to match the concrete style.

## Inputs

- `$ARGUMENTS[0]` — required. Service name in PascalCase (`OrderService`, `CheckoutService`). Usually `<Ctx>Service`.
- `$ARGUMENTS[1]` — optional. Comma-separated verbs (`place,cancel,get`). Each becomes a trait method stub.
- Fields beyond that (DTO names, return types) — ask the user if they are load-bearing; guess only when obvious.

## Checklist

1. Choose or confirm the service name. If the context already has a service, decide: extend it or create a new one. Guide 04 says split when vocabularies diverge significantly; otherwise extend.
2. Read the relevant guide sections and note:
   - Public trait shape (in `<base-pkg>.<ctx>`).
   - `Live` class shape (in `.impl`, `private[<ctx>] final class`).
   - Accessor pattern (`object <Name>` with `ZIO.serviceWithZIO` forwarders).
   - DTO separation: `<Verb>Input` and `<Verb>View` (or `<Entity>View`).
   - ZLayer companion: `val layer: ZLayer[<deps>, Nothing, <Name>] = ZLayer.fromFunction(<Live>.apply)`.
3. Generate (or edit) the files:
   - `<ctx>-core/src/main/scala/<pkg>/<ctx>/<Name>.scala` — trait + accessor object, scaladoc on every method.
   - `<ctx>-core/src/main/scala/<pkg>/<ctx>/<Verb>Input.scala` / `<Entity>View.scala` — DTOs (case classes, `derives` JSON codec if the repo uses one).
   - `<ctx>-core/src/main/scala/<pkg>/<ctx>/impl/<Name>Live.scala` — `Live` class with constructor deps, companion `val layer`.
4. Trait methods return `IO[<Ctx>Error, View]` (typed error channel) per guide 03.
5. `Live` is wired by constructor: `private[<ctx>] final class <Name>Live(repo: <Entity>Repository, clock: Clock)`. Dependencies that do not yet exist → flag, do not invent.
6. Register the new `val layer` in the `app` main wiring list (guide 07).
7. Compile.

## Invariants to enforce

- Public trait in `com.myco.<ctx>`; `Live` in `com.myco.<ctx>.impl` with `private[<ctx>]` visibility.
- DTOs (`Input`, `View`) live in the public package. Domain entities stay in `impl`. `Live` is the translator (DTO ↔ domain).
- Trait method signatures use public types only (DTOs, error enum). Never leak domain types from `impl`.
- Error channel typed to `<Ctx>Error`; infrastructure failures surface as defects.
- Companion `val layer: ZLayer[...]` — do not use `Has[_]` or other pre-ZIO-2 shapes.
- Stateless `Live`. No `var` fields; any cache-like state goes behind a `Ref` in a dependency.

## Output format

```
Created service '<Name>' in <ctx>

Files:
  <path>/<Name>.scala               (trait + accessors)
  <path>/<Verb>Input.scala          (DTO)
  <path>/<Entity>View.scala         (DTO)
  <path>/impl/<Name>Live.scala      (private Live class + val layer)

Layer registered in: app/src/main/scala/<pkg>/app/Main.scala

Compile: OK

Unresolved dependencies flagged:
  - <Entity>Repository (run /new-repository <Entity>)
```

## Common mistakes

- Putting the `Live` class in the public package, or omitting `private[<ctx>]`.
- Trait methods that return domain entities instead of `View` DTOs.
- Adding a second error enum for this service. Reuse `<Ctx>Error`.
- Injecting `ZLayer` dependencies by `ZIO.service[X].flatMap` inside method bodies instead of constructor-based DI (guide 07).
- Forgetting to register the layer in `app/Main.scala`, leaving the service unreachable at runtime.