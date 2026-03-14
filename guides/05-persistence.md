# \[WIP\] 05 — Persistence

> Repository ports with domain types, DAO mapping in adapters, and non-uniform model strategy.

---

## 1. Repository Ports Speak Domain Language

The repository port is a trait defined in the `impl` package. Its methods accept and return **rich domain entities**, not intermediate record types or primitives.

```scala
package com.myco.ordering
package impl

import zio.*

private[ordering] trait OrderRepository:
  def save(order: Order): Task[Unit]
  def findById(id: Order.Id): Task[Option[Order]]
  def findByCustomer(customerId: Customer.Id): Task[List[Order]]
```

**Why `Task` and not `IO[DomainError, A]`?** Because a database failure is an infrastructure concern, not a domain concern. The `Live` service converts `Task` results to domain errors where appropriate:

```scala
orderRepo.findById(orderId)
  .orDie                                     // infra failure → defect
  .someOrFail(OrderError.OrderNotFound(id))  // missing → domain error
```

**Why no intermediate record type?** The port's job is to store and retrieve domain entities. How that entity maps to a database row is the adapter's problem. Introducing a `OrderRecord` between the domain and the adapter just adds a translation step with no architectural benefit.

---

## 2. DAO Mapping in the Adapter

The technology adapter — in a sub-package like `impl.postgres` — owns the **bidirectional mapping** between domain entities and its persistence format.

```scala
package com.myco.ordering
package impl
package postgres

// Order, OrderStatus, LineItem, etc. auto-imported via chained clause

private[postgres] final case class OrderDAO(
  id:          String,
  customerId:  String,
  status:      String,
  itemsJson:   String,
  createdAt:   java.time.Instant
):
  def toDomain: Order =
    Order(
      id = Order.Id(id),
      data = Order.Data(
        customerId      = CustomerId(customerId),
        shippingAddress = ???,  // parse from additional columns/tables
        items           = parseItemsJson(itemsJson),
        status          = OrderStatus.valueOf(status)
      )
    )

private[postgres] object OrderDAO:
  def fromDomain(order: Order): OrderDAO =
    OrderDAO(
      id         = order.id.value,
      customerId = order.data.customerId.value,
      status     = order.data.status.toString,
      itemsJson  = serializeItems(order.data.items),
      createdAt  = java.time.Instant.now()
    )
```

The DAO is `private[postgres]` — it never leaks. If you switch from Postgres to DynamoDB, create an `impl.dynamo` package with its own DAO and adapter. The domain code doesn't change.

---

## 3. Adapter Implementation

The adapter implements the repository port, using its DAO for storage:

```scala
package com.myco.ordering
package impl
package postgres

import zio.*

final case class PostgresOrderRepository(
  dataSource: javax.sql.DataSource
) extends OrderRepository:

  override def save(order: Order): Task[Unit] =
    val dao = OrderDAO.fromDomain(order)
    ZIO.attemptBlocking:
      // insert/upsert using quill, doobie, or JDBC
      ???

  override def findById(id: Order.Id): Task[Option[Order]] =
    ZIO.attemptBlocking:
      // SQL select returning Option[OrderDAO]
      ???
    .map(_.map(_.toDomain))

object PostgresOrderRepository:
  val layer: URLayer[javax.sql.DataSource, OrderRepository] =
    ZLayer.fromFunction(PostgresOrderRepository.apply)
```

### Handling meaningful database errors

Most database errors are infrastructure failures and should stay as `Throwable` in the `Task`. But some are domain-meaningful:

```scala
override def save(order: Order): Task[Unit] =
  ZIO.attemptBlocking(db.execute(insertQuery))
    .catchSome:
      case _: DuplicateKeyException =>
        ZIO.fail(new RuntimeException(s"Order ${order.id.value} already exists"))
    // The Live service can catch this and map to a domain error if needed,
    // or it stays as a defect if duplicates are truly unexpected
```

---

## 4. Non-Uniform Models in Practice

Three representations, three owners, three concerns:

| Model | Owner | Optimised for |
|-------|-------|---------------|
| Domain entity (`Order`) | `impl` package in `*-core` | Business rules, correctness, readability |
| DTO (`OrderView`, `CheckoutInput`) | Public package in `*-core` | Serialisation, API stability, consumer needs |
| DAO (`OrderDAO`) | `impl.postgres` in `*-infra` | Database schema, query performance |

Over time these representations **will drift**. The domain model gains new ADT branches. The DAO adds indexes and denormalized columns. The DTO stays stable for backward compatibility. That's the whole point — each can evolve independently because they have explicit conversion boundaries.

### When the DAO drifts far from the domain

This is expected and healthy. A domain `Order` might be a single nested entity. The database might split it across three tables with a JSON column for line items. The DAO and the mapping logic absorb that complexity so the domain stays clean:

```scala
// Domain: one nested entity
final case class Order(id: Order.Id, data: Order.Data)

// DAO: split across tables
private[postgres] final case class OrderHeaderDAO(id: String, customerId: String, status: String)
private[postgres] final case class OrderItemDAO(orderId: String, sku: String, qty: Int, price: BigDecimal)
private[postgres] final case class OrderAddressDAO(orderId: String, country: String, city: String, …)

// Adapter: reassembles them into a domain entity
override def findById(id: Order.Id): Task[Option[Order]] =
  for
    headerOpt <- fetchHeader(id)
    result    <- headerOpt match
      case None => ZIO.succeed(None)
      case Some(header) =>
        for
          items   <- fetchItems(id)
          address <- fetchAddress(id)
        yield Some(assembleDomain(header, items, address))
  yield result
```

---

## 5. In-Memory Implementations

Every repository port gets an in-memory implementation in `*-core`. Since the port works with domain entities directly, the implementation is trivial:

```scala
private[ordering] final case class InMemoryOrderRepository(
  ref: Ref[Map[Order.Id, Order]]
) extends OrderRepository:

  def save(order: Order): UIO[Unit] =
    ref.update(_.updated(order.id, order))

  def findById(id: Order.Id): UIO[Option[Order]] =
    ref.get.map(_.get(id))

  def findByCustomer(customerId: Customer.Id): UIO[List[Order]] =
    ref.get.map(_.values.filter(_.data.customerId == customerId).toList)

object InMemoryOrderRepository:
  val layer: ULayer[OrderRepository] =
    ZLayer(Ref.make(Map.empty[Order.Id, Order]).map(InMemoryOrderRepository(_)))
```

This lets you test all domain logic without a database. See [09 — Testing](09-testing.md).

---

## 6. Transaction Boundaries

Transactions are scoped at the **adapter level**, not the service level. A single `save` call on a repository maps to a single database transaction:

```scala
// In the adapter — one transaction per save
override def save(order: Order): Task[Unit] =
  ZIO.attemptBlocking:
    db.transaction:
      upsertHeader(order)
      upsertItems(order)
      upsertAddress(order)
```

For aggregate-level consistency, ensure each aggregate is persisted atomically. Cross-aggregate consistency should use **eventual consistency** via events, not distributed transactions. See [06 — Bounded Contexts](06-bounded-contexts.md).

---

## 7. Parse, Don't Validate (at the Persistence Boundary)

When loading from the database, the `DAO.toDomain` method is a **parse** step. If the database contains corrupt or legacy data, the parse should fail explicitly:

```scala
def toDomain: Either[String, Order] =
  for
    status <- OrderStatus.values.find(_.toString == this.status)
                .toRight(s"Unknown status: ${this.status}")
    items  <- parseItemsJson(this.itemsJson)
  yield Order(Order.Id(id), Order.Data(…, items, status))
```

In the adapter, decide how to handle parse failures:

```scala
// Option 1: Fail loudly — corrupt data is a defect
override def findById(id: Order.Id): Task[Option[Order]] =
  fetchDAO(id).map(_.map(_.toDomain.fold(
    err => throw new IllegalStateException(s"Corrupt order $id: $err"),
    identity
  )))

// Option 2: Skip and log — for tolerant reads
override def findByCustomer(customerId: Customer.Id): Task[List[Order]] =
  fetchDAOs(customerId).map(_.flatMap(dao =>
    dao.toDomain match
      case Right(order) => Some(order)
      case Left(err) =>
        logger.warn(s"Skipping corrupt order ${dao.id}: $err")
        None
  ))
```

---

## 8. Summary

1. **Repository ports use domain entities.** No intermediate record types.
2. **Adapters own the mapping.** `OrderDAO.fromDomain` / `.toDomain` is the adapter's responsibility.
3. **DAO is adapter-private.** `private[postgres]` — never visible outside.
4. **Three models, three owners.** Domain, DTO, DAO evolve independently.
5. **In-memory repos for tests.** Trivial `Ref[Map[Id, Entity]]` implementations.
6. **Transactions at the adapter level.** One `save` = one transaction.
7. **Parse on load.** `DAO.toDomain` can fail; handle failures explicitly.

---

*See also: [02 — Domain Modeling](02-domain-modeling.md), [04 — Service Design](04-service-design.md), [09 — Testing](09-testing.md)*
