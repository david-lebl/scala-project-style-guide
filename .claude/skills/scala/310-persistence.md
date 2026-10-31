# Persistence

Repository ports that speak the domain language, adapters that own the mapping
to storage, in-memory implementations for tests, and parsing data on load.
Read this before adding a repository method, a table, or a storage adapter.

## Dependencies

- `"dev.zio" %% "zio"`
- a JDBC/SQL library of your choice in `*-infra` only (e.g. `"io.getquill" %%
  "quill-jdbc-zio"`, `"org.tpolecat" %% "doobie-core"`, or plain JDBC)

---

## Repository ports speak domain language

The port is a trait in `impl`. It takes and returns **domain entities**, not
records, rows or primitives.

```scala
package com.myco.ordering
package impl

import zio.*

private[ordering] trait OrderRepository:
  def save(order: Order): UIO[Unit]
  def findById(id: Order.Id): UIO[Option[Order]]
  def findByCustomer(customerId: CustomerId, page: Page): UIO[Paged[Order]]
```

- **`UIO`, not `Task`.** The port is a domain contract; a database failure is
  not a domain concern. The adapter turns infrastructure failures into defects
  with `.orDie`, so the `Live` works with clean effects (see [Error
  Model](210-error-model.md)).
- **`IO[<Context>Error, A]`** only when a storage outcome has domain meaning
  (e.g. a unique-key violation means "already exists").
- **`Option` for not found.** The service decides whether absence is an error
  (`someOrFail`).
- **No intermediate record type** between the port and the adapter. How an
  entity becomes rows is the adapter's problem.
- **Pagination belongs in the port** (`Page` / `Paged[A]` from `commons`);
  never load all rows and slice in the service.

## DAO mapping in the adapter

The adapter package (`impl.postgres`, `impl.dynamo`, …) owns a `private[<tech>]`
DAO and the mapping both ways:

```scala
package com.myco.ordering
package impl
package postgres

private[postgres] final case class OrderDAO(
  id:          String,
  customerId:  String,
  status:      String,
  itemsJson:   String,
  addressJson: String
):
  def toDomain: Either[String, Order] =
    for
      status  <- OrderStatus.values.find(_.toString == this.status).toRight(s"unknown status '$status'")
      items   <- parseItems(itemsJson)
      address <- parseAddress(addressJson)
    yield Order(
      Order.Id(id),
      Order.Data(
        customerId      = CustomerId(customerId),
        shippingAddress = address,
        items           = items,
        status          = status
      )
    )

private[postgres] object OrderDAO:
  def fromDomain(order: Order): OrderDAO =
    OrderDAO(
      id          = order.id.value,
      customerId  = order.data.customerId.value,
      status      = order.data.status.toString,
      itemsJson   = serializeItems(order.data.items),
      addressJson = serializeAddress(order.data.shippingAddress)
    )
```

Switching storage means a new adapter package with its own DAO; the domain and
the port don't change.

## Adapter implementation

```scala
final case class PostgresOrderRepository(dataSource: DataSource) extends OrderRepository:

  override def save(order: Order): UIO[Unit] =
    ZIO.attemptBlocking(upsert(OrderDAO.fromDomain(order))).orDie

  override def findById(id: Order.Id): UIO[Option[Order]] =
    ZIO.attemptBlocking(selectById(id.value))
      .orDie
      .flatMap(ZIO.foreach(_)(parseOrDie))

  private def parseOrDie(dao: OrderDAO): UIO[Order] =
    ZIO.fromEither(dao.toDomain)
      .orDieWith(err => IllegalStateException(s"Corrupt order ${dao.id}: $err"))

object PostgresOrderRepository:
  val layer: URLayer[DataSource, OrderRepository] =
    ZLayer.fromFunction(PostgresOrderRepository.apply)
```

- Every JDBC call is wrapped in `ZIO.attemptBlocking`.
- `.orDie` sits in the adapter, at the end of each method.
- The layer exposes the **port**, not the adapter class.

When a database error has domain meaning, the port declares it and the adapter
maps that one exception; everything else still dies:

```scala
override def save(order: Order): IO[OrderError, Unit] =
  ZIO.attemptBlocking(insert(OrderDAO.fromDomain(order)))
    .refineOrDie:
      case _: SQLIntegrityConstraintViolationException => OrderError.OrderAlreadyExists(order.id)
```

If duplicates are truly unexpected, keep `UIO[Unit]` and let `.orDie` handle it.

## Parse, don't validate, on load

`DAO.toDomain` is a **parse** step returning `Either`: legacy or corrupt rows
are a real possibility. Decide per query:

- **Fail loudly** (default) — corrupt data is a defect: `orDieWith`, as above.
- **Skip and log** — for tolerant listings:

```scala
override def findRecent(limit: Int): UIO[List[Order]] =
  ZIO.attemptBlocking(selectRecent(limit)).orDie.flatMap: daos =>
    ZIO.foreach(daos)(dao =>
      dao.toDomain match
        case Right(order) => ZIO.some(order)
        case Left(err)    => ZIO.logWarning(s"Skipping corrupt order ${dao.id}: $err").as(None)
    ).map(_.flatten)
```

Never `OrderStatus.valueOf(row.status)` — it throws on unknown values.

## Non-uniform models

| Model | Owner | Optimised for |
|-------|-------|---------------|
| Domain (`Order`) | `impl`, core | business rules |
| DTO (`OrderView`) | public package, core | API stability |
| DAO (`OrderDAO`) | `impl.postgres`, infra | schema, query performance |

They drift, and that is healthy. One nested domain entity may be three tables
in the database; the adapter reassembles it:

```scala
override def findById(id: Order.Id): UIO[Option[Order]] =
  ZIO.attemptBlocking(fetchHeader(id)).orDie.flatMap:
    case None         => ZIO.none
    case Some(header) =>
      ZIO.attemptBlocking(assemble(header, fetchItems(id), fetchAddress(id))).orDie
        .flatMap(parseOrDie)
        .asSome
```

## In-memory implementations

Every repository port has a `Ref`-backed in-memory implementation in `impl` of
the core module. Because the port speaks domain types, it's trivial:

```scala
private[ordering] final case class InMemoryOrderRepository(ref: Ref[Map[Order.Id, Order]])
    extends OrderRepository:
  override def save(order: Order): UIO[Unit] =
    ref.update(_.updated(order.id, order))

  override def findById(id: Order.Id): UIO[Option[Order]] =
    ref.get.map(_.get(id))

object InMemoryOrderRepository:
  val layer: ULayer[OrderRepository] =
    ZLayer(Ref.make(Map.empty[Order.Id, Order]).map(InMemoryOrderRepository(_)))
```

It is a real implementation, not a mock — tests use it to verify behavior (see
[Testing](400-testing.md)).

## Transaction boundaries

- One port call = one transaction, managed **inside the adapter**. An
  aggregate is saved atomically by a single `save`.
- Services never open transactions and port signatures never carry a
  transaction handle.
- Consistency across aggregates or contexts is eventual, via events (see
  [Bounded Contexts](320-bounded-contexts.md#event-driven-decoupling)), never
  distributed transactions.
- When two writes must be atomic (entity + outbox event), give the port one
  method that does both (`saveWithEvent(order, event)`).
