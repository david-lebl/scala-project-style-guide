# Configuration

Configuration as typed, per-module values loaded once at startup and injected
as layers. Read this before adding a setting, an environment variable, or a
secret.

## Dependencies

- `"dev.zio" %% "zio"` — `Config`, `ZIO.config`, `Config.Secret`
- `"dev.zio" %% "zio-config-magnolia"` — `deriveConfig`
- `"dev.zio" %% "zio-config-typesafe"` — HOCON source (optional)

---

## Config is a layer

Configuration is a dependency like any other: a case class, loaded by a layer,
injected into the constructors that need it. Nothing reads environment
variables or files directly.

Config classes live next to their user:

- **domain config** (limits, thresholds, feature switches) → `impl` of `*-core`;
- **infrastructure config** (DB URL, pool size, HTTP port) → the adapter's
  package in `*-infra`.

```scala
package com.myco.ordering
package impl

private[ordering] final case class OrderingConfig(
  maxItemsPerOrder: MaxItems,
  defaultCurrency:  Currency
)
```

Config values follow the same no-primitives rule as the domain (see [Domain
Modeling](120-domain-modeling.md)): opaque types, enums instead of `Boolean`
switches, `Duration` instead of `Long` millis.

## Loading

Each config exposes a layer that reads its own path:

```scala
package com.myco.ordering
package impl
package postgres

import zio.*
import zio.config.magnolia.deriveConfig

final case class PostgresConfig(url: String, username: String, password: Config.Secret, poolSize: Int)

object PostgresConfig:
  val layer: Layer[Config.Error, PostgresConfig] =
    ZLayer(ZIO.config(deriveConfig[PostgresConfig].nested("ordering", "db")))
```

- Nest by context and adapter (`ordering.db`, `shipping.http`) so keys never
  collide.
- Set the `ConfigProvider` once in `app` (environment variables, HOCON, or
  both with `orElse`) via `Runtime.setConfigProvider` in `bootstrap`.
- Use `Config` combinators (`.validate`, `.mapOrFail`) to parse into domain
  types, so an invalid value fails at load time.

## Fail fast

Invalid or missing configuration stops the application at startup — the layer
fails and `.provide` never completes. Never default silently, never read config
lazily on first request.

## Secrets

- Type secrets as `Config.Secret` (or your own opaque type with a redacting
  `toString`). Their `toString` is redacted, so they never appear in logs or
  error messages.
- Unwrap only at the point of use (`password.value.mkString`), inside the adapter.
- Never commit secrets; supply them through environment variables or a secrets
  manager feeding the `ConfigProvider`.

## Injecting into services

```scala
private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository,
  config:    OrderingConfig
) extends OrderService:

  override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
    for
      items <- ZIO.fromEither(parseItems(input.items))
      _     <- ZIO.cond(items.size <= config.maxItemsPerOrder.value, (), OrderError.TooManyItems(items.size))
      ...
    yield view
```

In tests, provide a fixed value with `ZLayer.succeed(OrderingConfig(...))`.
