---
name: new-config
description: Scaffold a per-module configuration class using zio-config plus a ZLayer that loads it, following guide 08. Use when the user asks to add a config, read an env var, make something configurable, or introduces infrastructure knobs (timeouts, URLs, credentials).
argument-hint: [module-name] [field:type ...]
allowed-tools: Read Grep Glob Edit Write Bash(sbt *) Bash(mill *) Bash(test *)
---

Generate a module-scoped `Config` case class plus the zio-config descriptor and a loader `ZLayer`. Secrets use a redacted wrapper.

## Pre-flight

1. Read `guides/08-configuration.md` in full. The per-module placement, the `Secret` opaque, and fail-fast behaviour come from there.
2. Read `guides/07-dependency-injection.md` §on config layers to match the wiring style.
3. Decide the owning module:
   - Config for domain concerns (retry counts, business rules) → `<ctx>-core/impl/config/`.
   - Config for infrastructure (DB URL, HTTP port, external service timeouts) → `<ctx>-infra/impl/<tech>/config/` or the corresponding `impl.<tech>` sub-package.

## Inputs

- `$ARGUMENTS[0]` — required. Module name (`ordering-core`, `ordering-infra-postgres`, `app`).
- `$ARGUMENTS[1..]` — optional. Field specs: `name:Type`, `name:Type?` (optional), `name:Secret` (redacted). Examples: `dbUrl:String`, `poolSize:Int`, `apiKey:Secret`.

## Checklist

1. Resolve target module and package.
2. Read guide 08 for:
   - Config case class shape (`final case class <Name>Config(...)`).
   - zio-config `Config.*` descriptor (e.g. `Config.string.nested("DB_URL")`).
   - Layer: `val layer: Layer[Config.Error, <Name>Config] = ZLayer { ZIO.config(descriptor) }`.
   - `Secret` usage — opaque type with redacted `toString`. If the repo already has a `Secret`, reuse it; otherwise note that one is needed and create it in `commons`.
3. Pick field types:
   - `String` for plain text, but lift into an opaque type when validation exists (email, URL — see `/new-domain-model`).
   - `Secret` for credentials, tokens, passwords, API keys. Never plain `String`.
   - `Duration` for time values — not `Long`.
   - `Int` / `Long` for counts and sizes.
4. Generate `<Name>Config.scala`:
   - Case class with scaladoc on each field — explain the *operational meaning*, not just the type.
   - Companion with `val descriptor: Config[<Name>Config]`.
   - Companion `val layer: Layer[Config.Error, <Name>Config]`.
5. Update `application.conf` (or `reference.conf`) with sensible defaults where safe. Never put secrets in reference config.
6. Register the layer in `app/Main.scala`.
7. Compile.

## Invariants to enforce

- Config classes live inside the owning module, not in a shared `config` module.
- Secrets are always wrapped in `Secret`. `toString` must redact.
- Config load fails fast on missing or invalid fields — the app must not start with bad config.
- No global mutable config object. Config is a dependency, like any other service.
- Defaults belong in `reference.conf`, not hard-coded in Scala. Fields without defaults must be required.

## Output format

```
Created <Name>Config in <module>

Files:
  <path>/<Name>Config.scala
  (optional) src/main/resources/reference.conf  — defaults appended

Fields:
  - dbUrl: String          from DB_URL
  - poolSize: Int          from DB_POOL_SIZE (default 10)
  - apiKey: Secret         from API_KEY (redacted)

Layer: <Name>Config.layer  — registered in app/Main.scala
Compile: OK
```

## Common mistakes

- Passing `String` where `Secret` is required. Credentials leak into logs this way.
- Global `object AppConfig` pulled from `sys.env`. Guide 08 explicitly rejects this.
- Defaults in code (`= "localhost"`) instead of `reference.conf`. Makes environments non-reproducible.
- Lenient parsing that swallows config errors. Fail fast, with a clear message about the missing field.
- Putting all config classes in one `commons` module. Per-module placement is load-bearing — it keeps modules independently deployable.