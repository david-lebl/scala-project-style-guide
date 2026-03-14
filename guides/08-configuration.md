# \[WIP\] 08 — Configuration

> Per-module config, zio-config, layered loading, secrets, and environment-specific overrides.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **Configuration as a Layer**
   - Config case classes per module: `OrderingConfig`, `PostgresConfig`
   - Config classes live in `impl` — they are internal to the bounded context
   - Loaded as `ZLayer` and injected like any other dependency

2. **zio-config Patterns**
   - Deriving config descriptors from case classes
   - Loading from HOCON / environment variables / system properties
   - Layered loading: defaults → file → env vars → CLI args (last wins)

3. **Per-Module Config Structure**
   - Each `*-core` may define a config for domain-level settings (feature flags, thresholds)
   - Each `*-infra` defines configs for infrastructure (DB connection, HTTP port, Kafka brokers)
   - Configs are composed in the `app` module

4. **HOCON Layout**
   - One namespace per bounded context: `ordering { db { ... } }`, `shipping { db { ... } }`
   - Shared infra config at root: `server { port = 8080 }`
   - `application.conf` + `application-local.conf` + `application-prod.conf`

5. **Secrets Management**
   - Never commit secrets to config files
   - Load from environment variables or a secrets manager
   - `zio-config` support for env var substitution: `${DB_PASSWORD}`
   - Pattern: `SecretValue` opaque type that redacts in `toString`

6. **Environment-Specific Overrides**
   - Profile-based loading: `ZIO.config` with a `ConfigProvider` chain
   - Test configs: minimal, in-memory-friendly, no real connections
   - Docker / Kubernetes: environment variables as the primary source

7. **Validation at Startup**
   - Fail fast: if config is invalid, the app should not start
   - `ZLayer` construction failure = startup failure (ZIO handles this)
   - Custom validation rules: port ranges, non-empty strings, URL formats

---

*See also: [07 — Dependency Injection](07-dependency-injection.md), [01 — Project Structure](01-project-structure.md)*
