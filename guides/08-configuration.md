# \[WIP\] 08 — Configuration

> Per-module config, zio-config, layered loading, secrets, and environment-specific overrides.

---

## 1. Configuration as a Layer

Configuration is a dependency like any other — loaded as a `ZLayer` and injected into services that need it.

```scala
// ordering-core: .../impl/OrderingConfig.scala

package com.myco.ordering
package impl

private[ordering] final case class OrderingConfig(
  maxItemsPerOrder: Int,
  defaultCurrency:  String
)
```

```scala
// ordering-infra: .../impl/postgres/PostgresConfig.scala

package com.myco.ordering
package impl
package postgres

final case class PostgresConfig(
  url:      String,
  username: String,
  password: String,
  poolSize: Int
)
```

Config classes live in the package that uses them:

- **Domain-level config** (feature flags, thresholds) → `impl` package in `*-core`.
- **Infrastructure config** (DB connection, HTTP port) → infra sub-package in `*-infra`.

---

### Secret value type

For defense in depth, wrap secrets in an opaque type that redacts in `toString` and logging:

```scala
opaque type Secret = String
object Secret:
  def apply(value: String): Secret = value
  extension (s: Secret)
    def value: String     = s
    def redacted: String  = "***"

  // Override toString at the config case class level
  given Show[Secret] with
    def show(s: Secret): String = "***"
```

```scala
final case class PostgresConfig(
  url:      String,
  username: String,
  password: Secret,    // won't appear in logs
  poolSize: Int
)
```

---

## 6. Per-Module Config Wiring

Each module exposes its config as a layer. The `app` module composes them:

```scala
// ordering-infra: .../impl/postgres/PostgresConfig.scala
object PostgresConfig:
  val layer: TaskLayer[PostgresConfig] =
    ZLayer:
      ZIO.config(deriveConfig[PostgresConfig].nested("ordering", "db"))
```

```scala
// app/Main.scala

object Main extends ZIOAppDefault:
  override val run =
    program.provide(
      // Configs
      OrderingConfig.layer,
      PostgresConfig.layer,
      ShippingConfig.layer,
      ServerConfig.layer,
      // Services
      OrderServiceLive.layer,
      PostgresOrderRepository.layer,
      // …
    )
```

Services that need config declare it as a dependency:

```scala
private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository,
  idGen:     IdGenerator,
  config:    OrderingConfig     // injected via ZLayer
) extends OrderService:

  override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
    for
      items <- ZIO.fromEither(parseItems(input.items))
      _     <- ZIO.cond(
                 items.size <= config.maxItemsPerOrder,
                 (),
                 OrderError.TooManyItems(items.size, config.maxItemsPerOrder)
               )
      // …
    yield view
```


---

## 9. Summary

1. **Config is a layer.** Load with `ZLayer`, inject like any other dependency.
2. **Per-module config classes.** Domain config in `*-core/impl`, infra config in `*-infra/impl.<tech>`.
4. **Never commit secrets.** Use `${?VAR}` substitution or a secrets manager.
5. **Fail fast.** Invalid config = application won't start.

---

*See also: [07 — Dependency Injection](07-dependency-injection.md), [01 — Project Structure](01-project-structure.md)*
