# Scala Project Style Guideline

> Inspired by:
> 1. [Modeling in Scala, part 1](https://kubuszok.com/2024/modeling-in-scala-part-1/) by Mateusz Kubuszok
> 2. [Diamond Architecture](https://github.com/DevInsideYou/diamond-architecture) by DevInsideYou
> 3. [Domain-Driven Design Made Functional](https://share.google/GzSThdgT1UOdJtIWA) by Scott Wlaschin.
---

## 1. Philosophy

Before writing any code — before drawing SQL schemas, picking serialisation formats, or debating microservices — **understand the domain**.
Talk to the domain experts.
Use the same words they use.
Only then translate those words into types.

This guideline codifies how we turn that understanding into a well-structured, strongly-typed Scala 3 + ZIO codebase inside a large monorepo.
The goal is a system where each bounded context is an independent hexagon that can live inside a modulith today and be extracted into a standalone microservice tomorrow — without rewriting its internals.

### The Cardinal Rule

> **The core module's public surface is a stable contract. Everything in the `impl` package is free to change.**
>
> Other teams, other bounded contexts, and infrastructure modules all depend on the core module's public package.
> If you change that public surface, you break everyone.
> But everything behind it — domain models, business rules, repository ports, internal data structures — can be refactored, remodeled, or rewritten at will, because no external code can see it.

### The Independence Rule

> **Core modules never depend on other core modules.**
>
> Each `*-core` module depends only on `commons`. If a bounded context needs data from another context, it defines its own port in its own language (an anti-corruption layer). The second-layer infrastructure module provides the adapter that bridges the two. This keeps every core independently extractable.

---

## 2. Strong Typing as the Foundation

### 2.1 No Primitives in the Domain

Every concept that carries domain meaning gets its own type.
Never pass a raw `String`, `Int`, or `Boolean` across a domain boundary.

```scala
// ✗ Primitive obsession
def createOrder(customerId: String, amount: Double): Order

// ✓ Distinct domain types
def createOrder(customerId: Customer.Id, amount: Money): Order
```

In Scala 3, prefer **opaque types** for zero-cost wrappers and **case classes** when you need pattern matching, equality, or nesting:

```scala
object Customer:
  opaque type Id = UUID
  object Id:
    def apply(value: UUID): Id = value
    extension (id: Id) def value: UUID = id

  opaque type FirstName = String
  object FirstName:
    def apply(value: String): FirstName = value

  opaque type LastName = String
  object LastName:
    def apply(value: String): LastName = value

  final case class BillingName(
    firstName: Customer.FirstName,
    lastName:  Customer.LastName
  )
```

### 2.2 Algebraic Data Types Everywhere

Use **enums** (sum types) and **case classes** (product types) to model data.
The compiler then enforces exhaustive pattern matching and gives you correct `equals`, `hashCode`, `toString`, and derivation for free.

```scala
enum OrderStatus:
  case Unpaid, Paid, InProgress, Shipped, Cancelled

enum PriceHandling:
  case Domestic(discount: Discount)
  case NonDomestic(treatyType: NonDomesticType)
```

### 2.3 Make Illegal States Unrepresentable

Prefer sum types over `Option` fields with runtime assertions.
If two fields are mutually exclusive, model them as branches of an enum so the compiler prevents invalid combinations.

```scala
// ✗ Runtime assertion
final case class UnitPrice(
  vat:      Option[BigDecimal],
  discount: Option[BigDecimal]
):
  assert(vat.isEmpty || discount.isEmpty) // invisible at compile time

// ✓ Compile-time enforcement
enum PriceHandling:
  case Domestic(discount: Discount)      // discount possible, VAT always present
  case NonDomestic(treaty: TreatyType)   // no discount, VAT depends on treaty
```

### 2.4 Smart Constructors

When a type has invariants (e.g. non-negative amount), hide the default constructor and expose a `parse` / `make` method that returns `Either[DomainError, A]`.

```scala
case class Ratio private (asFraction: BigDecimal)
object Ratio:
  def parse(value: BigDecimal): Either[ValidationError, Ratio] =
    Either.cond(value >= 0, Ratio(value), ValidationError.NegativeRatio)
```

### 2.5 Separate Entity Identity from Data

Unflatten entities into `id` + `data` to simplify comparison, deduplication, and pattern matching:

```scala
final case class Order(id: Order.Id, data: Order.Data)
object Order:
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value

  final case class Data(
    billingName:     Customer.BillingName,
    billingAddress:  Address,
    shippingAddress: Address,
    items:           NonEmptyChunk[LineItem],
    status:          OrderStatus
  )
```

### 2.6 Nest Related Types Inside Companion Objects

Types that belong exclusively to one aggregate live in that aggregate's companion.
This makes the ownership obvious and reduces top-level namespace pollution.

```scala
final case class Address(
  country:    Country,
  city:       Address.City,
  postalCode: Address.PostalCode,
  firstLine:  Address.Line,
  secondLine: Address.Line
)
object Address:
  opaque type City       = String
  opaque type PostalCode = String
  opaque type Line       = String
  // factory methods …
```

---

## 3. Non-Uniform Models — Domain ≠ DTO ≠ DAO

A single model shared across all layers (JSON, database, domain logic) is the fast path to an unmaintainable system.
Maintain **three separate representations** and convert between them explicitly.

| Layer | Optimised for | Example type |
|-------|---------------|--------------|
| **Domain** | Correctness, business rules | `ItemPrice(netPrice: Money, vatRate: Vat[Ratio])` |
| **DTO** | Serialisation (JSON / Protobuf) | `ItemPriceDTO(netPrice: MoneyDTO, vatRate: VatDTO[BigDecimal])` |
| **DAO** | Persistence (SQL row, document) | `ItemPriceRow(currency: String, netAmount: BigDecimal, vatRate: Option[BigDecimal])` |

**Where each lives** is critical to the architecture described in Section 4:

- **DTO types** used in the service trait's public signature belong in the **core module's public package** (they are part of the stable contract).
- **Domain types** used inside business logic belong in the **core module's `impl` package** (they are free to change).
- **DAO types** belong in the **infrastructure module** (e.g. `ordering-infra`), in a technology sub-package like `impl.postgres`, next to the persistence code that needs them. The adapter is responsible for mapping domain entities to and from its own DAO representation.

### 3.1 Parse, Don't Validate

Conversion from DTO/DAO → Domain is a **parse** step that returns `Either[ParsingError, DomainType]`.
This means only valid domain objects can ever reach the business logic.

```scala
final case class MoneyDTO(currency: String, amount: BigDecimal):
  def toDomain: Either[ParsingError, Money] =
    Currency.parse(currency).map(c => Money(c, amount))

object MoneyDTO:
  def fromDomain(m: Money): MoneyDTO =
    MoneyDTO(m.currency.entryName, m.amount)
```

### 3.2 Domain Errors as ADTs

Domain errors are **not exceptions**.
They are modeled as sealed enums so the compiler forces every call site to handle them.
Reserve `Throwable` for truly unrecoverable infrastructure failures; those flow through ZIO's defect channel.

```scala
enum OrderError:
  case EmptyItemList
  case InsufficientStock(itemId: CatalogueNumber)
  case PaymentDeclined(reason: String)
```

---

## 4. Module Structure in the Monorepo

### 4.1 The Two-Layer Split per Context

Each bounded context is composed of **two layers of build modules**:

| Layer | Module | Role | Depends on |
|-------|--------|------|------------|
| **1st layer** | `ordering-core` | **Public contract** (service trait, DTOs, errors) + **all domain internals** (domain model, repository ports, service `Live`, anti-corruption ports for external contexts). | `commons` **only** |
| **2nd layer** | `ordering-infra` | **Infrastructure.** Persistence adapters (Postgres, MySQL, …), HTTP routes, anti-corruption adapters bridging to other contexts. | `ordering-core` + other `*-core` modules as needed |

The crucial design constraint: **first-layer modules never depend on each other**. Every `*-core` depends only on `commons`. All cross-context wiring happens in the second layer.

Within the core module, the public contract lives at the root package (`com.myco.ordering`). Everything internal — the rich domain model, the repository port, anti-corruption ports, the `Live` implementation — lives in the `impl` sub-package (`com.myco.ordering.impl`), which is `private[ordering]`.

### 4.2 Chained Package Clauses for Zero-Import Access

Scala allows splitting a package path into **chained `package` declarations**. When you do this, all members of every enclosing package are automatically in scope — no imports required.

```scala
// This file is in com.myco.ordering.impl.postgres
// Written as a CHAINED declaration:

package com.myco.ordering
package impl
package postgres

// Everything defined in com.myco.ordering        is now in scope (OrderService, DTOs, errors)
// Everything defined in com.myco.ordering.impl   is now in scope (Order, OrderRepository, domain types)
// Everything defined in com.myco.ordering.impl.postgres is now in scope (local types)
// → Zero imports needed for any of the above.
```

Compare with the **single-line** form, which does NOT auto-import parent packages:

```scala
// ✗ Single-line form — only com.myco.ordering.impl.postgres is in scope
package com.myco.ordering.impl.postgres

// You would need explicit imports:
import com.myco.ordering.*       // for OrderService, DTOs
import com.myco.ordering.impl.*  // for Order, OrderRepository
```

**Always use chained package clauses** in `impl` sub-packages. This eliminates nearly all cross-package imports within a bounded context and keeps files concise.

### 4.3 Canonical Layout

```
monorepo/
├── build.sbt
├── modules/
│   ├── commons/                             ← shared kernel (Money, Country, etc.)
│   │   └── src/main/scala/com/myco/commons/
│   │
│   ├── ordering-core/                       ← 1ST LAYER — independent
│   │   └── src/main/scala/com/myco/ordering/
│   │       │
│   │       │   # ── public package (stable contract) ──────────
│   │       ├── OrderService.scala             ← public service trait
│   │       ├── CheckoutInput.scala            ← input/output DTOs
│   │       ├── OrderView.scala                ← response/read-model DTO
│   │       ├── OrderError.scala               ← public error enum
│   │       ├── OrderEvent.scala               ← published events
│   │       │
│   │       │   # ── impl package (changeable internals) ───────
│   │       └── impl/
│   │           ├── Order.scala                  ← rich domain entity
│   │           ├── LineItem.scala               ← domain value object
│   │           ├── PriceHandling.scala          ← domain ADT
│   │           ├── Address.scala                ← domain value object
│   │           ├── OrderRepository.scala        ← repository port (domain types)
│   │           ├── OrderServiceLive.scala       ← ZLayer-providing Live impl
│   │           └── InMemoryOrderRepository.scala← test double
│   │
│   ├── ordering-infra/                      ← 2ND LAYER — infrastructure
│   │   └── src/main/scala/com/myco/ordering/
│   │       └── impl/
│   │           └── postgres/                    ← persistence adapter
│   │               ├── PostgresOrderRepository.scala
│   │               └── OrderDAO.scala
│   │
│   ├── shipping-core/                       ← 1ST LAYER — independent
│   │   └── src/main/scala/com/myco/shipping/
│   │       │
│   │       │   # ── public package ────────────────────────────
│   │       ├── ShippingService.scala
│   │       ├── ShipOrderInput.scala
│   │       ├── ShipmentView.scala
│   │       ├── ShippingError.scala
│   │       │
│   │       │   # ── impl package ──────────────────────────────
│   │       └── impl/
│   │           ├── Shipment.scala               ← domain entity
│   │           ├── ShipmentRepository.scala     ← repository port
│   │           ├── OrderingPort.scala           ← ANTI-CORRUPTION PORT (see §6)
│   │           ├── ShippingServiceLive.scala    ← Live impl (uses OrderingPort)
│   │           └── InMemoryShipmentRepository.scala
│   │
│   ├── shipping-infra/                      ← 2ND LAYER — infrastructure + adapters
│   │   └── src/main/scala/com/myco/shipping/
│   │       └── impl/
│   │           ├── postgres/                    ← persistence adapter
│   │           │   ├── PostgresShipmentRepository.scala
│   │           │   └── ShipmentDAO.scala
│   │           └── adapters/                    ← anti-corruption adapters
│   │               └── OrderingAdapter.scala      ← bridges OrderingPort → OrderService
│   │
│   └── app/                                 ← wiring: composes all modules
│       └── src/main/scala/com/myco/app/
│           └── Main.scala
```

### 4.4 Build Definition (sbt)

```scala
// build.sbt

lazy val commons = project.in(file("modules/commons"))

// ── Ordering bounded context ─────────────────────────────────────
lazy val orderingCore = project.in(file("modules/ordering-core"))
  .dependsOn(commons)                   // ONLY commons — never another *-core

lazy val orderingInfra = project.in(file("modules/ordering-infra"))
  .dependsOn(orderingCore)              // 2nd layer depends on its own 1st layer

// ── Shipping bounded context ─────────────────────────────────────
lazy val shippingCore = project.in(file("modules/shipping-core"))
  .dependsOn(commons)                   // ONLY commons — never another *-core

lazy val shippingInfra = project.in(file("modules/shipping-infra"))
  .dependsOn(shippingCore, orderingCore) // 2nd layer may depend on OTHER *-core
                                         // modules to implement anti-corruption adapters

// ── Application ──────────────────────────────────────────────────
lazy val app = project.in(file("modules/app"))
  .dependsOn(orderingInfra, shippingInfra)

lazy val root = project.in(file("."))
  .aggregate(
    commons,
    orderingCore, orderingInfra,
    shippingCore, shippingInfra,
    app
  )
```

**Key dependency rules:**

- **1st layer (`*-core`)** depends only on `commons`. Never on another `*-core`. This is non-negotiable.
- **2nd layer (`*-infra`)** depends on its own `*-core` and may depend on **other `*-core` modules** to implement anti-corruption adapters. It never depends on another `*-infra`.
- **`app`** depends on all `*-infra` modules (which transitively bring in their `*-core`). It wires the layers together.
- No `*-infra` sees another `*-infra`. This is enforced by the build graph.

### 4.5 What Goes Where — Summary Table

| Artefact | Package | Build module | Visibility | Can it change freely? |
|----------|---------|--------------|------------|----------------------|
| Service trait (`OrderService`) | `com.myco.ordering` | `ordering-core` | **Public** | **No** — stable contract |
| Service input/output DTOs | `com.myco.ordering` | `ordering-core` | **Public** | **No** — stable contract |
| Error enum (`OrderError`) | `com.myco.ordering` | `ordering-core` | **Public** | **No** — stable contract |
| Published events | `com.myco.ordering` | `ordering-core` | **Public** | **No** — stable contract |
| Rich domain model (entities, ADTs) | `com.myco.ordering.impl` | `ordering-core` | `private[ordering]` | **Yes** |
| Repository port trait | `com.myco.ordering.impl` | `ordering-core` | `private[ordering]` | **Yes** |
| Anti-corruption port trait | `com.myco.ordering.impl` | `ordering-core` | `private[ordering]` | **Yes** |
| Service `Live` implementation | `com.myco.ordering.impl` | `ordering-core` | `private[ordering]` | **Yes** |
| In-memory repository (tests) | `com.myco.ordering.impl` | `ordering-core` | `private[ordering]` | **Yes** |
| DAO types + domain↔DAO mapping | `…impl.postgres` | `ordering-infra` | Package-private | **Yes** |
| SQL queries, DB migrations | `…impl.postgres` | `ordering-infra` | Package-private | **Yes** |
| Anti-corruption adapters | `…impl.adapters` | `ordering-infra` | Package-private | **Yes** |
| HTTP routes, JSON codecs | `…impl.http` | `ordering-infra` | Package-private | **Yes** |

---

## 5. Service Interfaces and ZIO Layers

### 5.1 Public Service Trait (in the public package)

The service trait is the **stable public contract** of the bounded context.
Its signature uses only types defined in the same public package (or in `commons`).
It knows nothing about HTTP, JSON, databases, or the internal domain model.

```scala
// ordering-core: src/main/scala/com/myco/ordering/OrderService.scala

package com.myco.ordering

import zio.*

trait OrderService:
  def checkout(input: CheckoutInput): IO[OrderError, OrderView]
  def getOrder(id: OrderId): IO[OrderError, OrderView]
  def cancelOrder(id: OrderId): IO[OrderError, Unit]

object OrderService:
  def checkout(input: CheckoutInput): ZIO[OrderService, OrderError, OrderView] =
    ZIO.serviceWithZIO(_.checkout(input))

  def getOrder(id: OrderId): ZIO[OrderService, OrderError, OrderView] =
    ZIO.serviceWithZIO(_.getOrder(id))

  def cancelOrder(id: OrderId): ZIO[OrderService, OrderError, Unit] =
    ZIO.serviceWithZIO(_.cancelOrder(id))
```

The input/output types are **deliberately simple** — shaped for consumers, not the internal domain:

```scala
// ordering-core: src/main/scala/com/myco/ordering/CheckoutInput.scala

package com.myco.ordering

final case class CheckoutInput(
  customerId:      CustomerId,
  shippingAddress: AddressInput,
  items:           List[LineItemInput]
)

final case class LineItemInput(
  catalogueNumber: String,
  quantity:        Int
)

final case class AddressInput(
  country:    String,
  city:       String,
  postalCode: String,
  line1:      String,
  line2:      String
)
```

```scala
// ordering-core: src/main/scala/com/myco/ordering/OrderView.scala

package com.myco.ordering

final case class OrderView(
  id:         OrderId,
  customerId: CustomerId,
  status:     String,
  items:      List[LineItemView],
  total:      BigDecimal,
  currency:   String
)

final case class LineItemView(
  catalogueNumber: String,
  quantity:        Int,
  unitPrice:       BigDecimal,
  lineTotal:       BigDecimal
)
```

### 5.2 Rich Domain Model (flat in the `impl` package)

Behind the stable contract, the `impl` package contains the **real domain model** — with all the nested ADTs, enums, smart constructors, and business rules from Section 2.
Domain types are defined **flat** in `com.myco.ordering.impl` so that infrastructure sub-packages like `impl.postgres` auto-import them via chained package clauses.

```scala
// ordering-core: src/main/scala/com/myco/ordering/impl/Order.scala

package com.myco.ordering
package impl

// OrderService, OrderError, CheckoutInput, etc. from the public
// package are already in scope — no imports needed.

private[ordering] final case class Order(id: Order.Id, data: Order.Data)
private[ordering] object Order:
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value
    extension (id: Id) def value: String = id

  final case class Data(
    customer:        CustomerSnapshot,
    shippingAddress: Address,
    items:           NonEmptyChunk[LineItem],
    status:          OrderStatus
  )
```

```scala
// ordering-core: src/main/scala/com/myco/ordering/impl/OrderStatus.scala

package com.myco.ordering
package impl

private[ordering] enum OrderStatus:
  case Unpaid, Paid, InProgress, Shipped, Cancelled
```

Because these types are `private[ordering]`, you can refactor them aggressively — rename fields, restructure hierarchies, introduce new sum types — without breaking any consumer outside `com.myco.ordering`.

### 5.3 Repository Port (works directly with domain entities)

The repository port is defined in terms of **rich domain types**. The adapter implementation is responsible for mapping domain entities to and from whatever storage format it uses.

```scala
// ordering-core: src/main/scala/com/myco/ordering/impl/OrderRepository.scala

package com.myco.ordering
package impl

import zio.*

private[ordering] trait OrderRepository:
  def save(order: Order): Task[Unit]
  def findById(id: Order.Id): Task[Option[Order]]
  def findByCustomer(customerId: Customer.Id): Task[List[Order]]
```

No intermediate record types. The port speaks the **language of the domain**. How an `Order` becomes a database row is the adapter's problem.

### 5.4 Live Implementation (in the `impl` package, same build module)

The `Live` implementation translates between the public DTOs and the internal domain, and delegates persistence to the repository port.

```scala
// ordering-core: src/main/scala/com/myco/ordering/impl/OrderServiceLive.scala

package com.myco.ordering
package impl

// All public types and all impl types are in scope via chained package clause.

import zio.*

private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository,
  idGen:     IdGenerator
) extends OrderService:

  override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
    for
      items   <- ZIO.fromEither(parseLineItems(input.items))
      address <- ZIO.fromEither(parseAddress(input.shippingAddress))
      orderId <- idGen.generate.orDie
      order    = Order(orderId, Order.Data(/* … */, items, OrderStatus.Unpaid))
      _       <- orderRepo.save(order)
    yield toView(order)

  override def getOrder(id: OrderId): IO[OrderError, OrderView] =
    for
      order <- orderRepo.findById(Order.Id(id.value))
                 .orDie
                 .someOrFail(OrderError.NotFound(id))
    yield toView(order)

  private def parseLineItems(inputs: List[LineItemInput]): Either[OrderError, NonEmptyChunk[LineItem]] = ???
  private def parseAddress(input: AddressInput): Either[OrderError, Address] = ???
  private def toView(order: Order): OrderView = ???

object OrderServiceLive:
  val layer: URLayer[OrderRepository & IdGenerator, OrderService] =
    ZLayer.fromFunction(OrderServiceLive.apply)
```

### 5.5 Persistence Adapter (in `*-infra`, sub-package of `impl`)

The infrastructure module's package nests under `impl`. By using **chained package clauses**, every type from the public package and from `impl` is automatically in scope.

The adapter maps domain entities to and from its own DAO representation:

```scala
// ordering-infra: src/main/scala/com/myco/ordering/impl/postgres/
//                 PostgresOrderRepository.scala

package com.myco.ordering
package impl
package postgres

// ↑ Chained package clause — brings into scope:
//   • com.myco.ordering       → OrderService, OrderId, OrderError, …
//   • com.myco.ordering.impl  → Order, OrderRepository, OrderStatus, …
//   • com.myco.ordering.impl.postgres → OrderDAO, local types

import zio.*
import javax.sql.DataSource

final case class PostgresOrderRepository(
  dataSource: DataSource
) extends OrderRepository:

  override def save(order: Order): Task[Unit] =
    val dao = OrderDAO.fromDomain(order)
    // SQL insert/upsert using quill, doobie, or plain JDBC
    ???

  override def findById(id: Order.Id): Task[Option[Order]] =
    // SQL select, then map: DAO → domain
    ???.map(_.map(_.toDomain))

  override def findByCustomer(customerId: Customer.Id): Task[List[Order]] =
    ???.map(_.map(_.toDomain))

object PostgresOrderRepository:
  val layer: URLayer[DataSource, OrderRepository] =
    ZLayer.fromFunction(PostgresOrderRepository.apply)
```

```scala
// ordering-infra: .../impl/postgres/OrderDAO.scala

package com.myco.ordering
package impl
package postgres

// Order, Order.Id, OrderStatus, LineItem, etc. auto-imported from impl.

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
      data = Order.Data(/* parse fields → domain types */ ???)
    )

private[postgres] object OrderDAO:
  def fromDomain(order: Order): OrderDAO =
    OrderDAO(
      id         = order.id.value,
      customerId = order.data.customer.id.value.toString,
      status     = order.data.status.toString,
      itemsJson  = /* serialise items */ ???,
      createdAt  = java.time.Instant.now()
    )
```

### 5.6 In-Memory Implementation for Tests

The in-memory double lives in `ordering-core`'s `impl` package. Because the repository port works with domain entities directly, the implementation is trivial:

```scala
// ordering-core: src/main/scala/com/myco/ordering/impl/
//                InMemoryOrderRepository.scala

package com.myco.ordering
package impl

import zio.*

private[ordering] final case class InMemoryOrderRepository(
                                                            ref: Ref[Map[Order.Id, Order]]
                                                          ) extends OrderRepository:

  def save(order: Order): UIO[Unit] =
    ref.update(_.updated(order.id, order))

  def findById(id: Order.Id): UIO[Option[Order]] =
    ref.get.map(_.get(id))

  def findByCustomer(customerId: Customer.Id): UIO[List[Order]] =
    ref.get.map(_.values.filter(_.data.customer.id == customerId).toList)

object InMemoryOrderRepository:
  val layer: ULayer[OrderRepository] =
    ZLayer(Ref.make(Map.empty[Order.Id, Order]).map(OrderRepositoryInMemory(_)))
```

---

## 6. Cross-Context Communication — Anti-Corruption Layer

### 6.1 The Problem

Shipping needs to look up order data before creating a shipment. The naïve solution — making `shipping-core` depend on `ordering-core` — breaks the independence rule. If `ordering-core` ever needed something from `shipping-core`, you'd have a circular dependency. Even without cycles, every core-to-core dependency makes extraction harder.

### 6.2 The Solution: Port in Core, Adapter in Infra

The shipping bounded context defines its own **port** — a trait that describes what it needs from the outside world, using **its own types**, in **its own language**. The core module has no idea that an "ordering" context even exists.

```scala
// shipping-core: src/main/scala/com/myco/shipping/impl/OrderingPort.scala

package com.myco.shipping
package impl

import zio.*

// Shipping's own view of an order — only the data shipping cares about.
// This is NOT com.myco.ordering.OrderView. It is shipping's own type.
private[shipping] final case class OrderSnapshot(
  orderId:    String,
  customerId: String,
  items:      List[OrderSnapshot.Item],
  total:      BigDecimal
)
private[shipping] object OrderSnapshot:
  final case class Item(catalogueNumber: String, quantity: Int)

// The port — defined in shipping's language
private[shipping] trait OrderingPort:
  def getOrder(orderId: String): Task[Option[OrderSnapshot]]
```

The `Live` service depends on this port, not on `OrderService`:

```scala
// shipping-core: src/main/scala/com/myco/shipping/impl/ShippingServiceLive.scala

package com.myco.shipping
package impl

import zio.*

private[shipping] final case class ShippingServiceLive(
  orderingPort: OrderingPort,         // ← shipping's own port, not OrderService
  shipmentRepo: ShipmentRepository
) extends ShippingService:

  override def shipOrder(input: ShipOrderInput): IO[ShippingError, ShipmentView] =
    for
      snapshot <- orderingPort.getOrder(input.orderId)
                    .orDie
                    .someOrFail(ShippingError.OrderNotFound(input.orderId))
      shipment <- ZIO.fromEither(createShipment(snapshot, input))
      _        <- shipmentRepo.save(shipment)
    yield toView(shipment)

  private def createShipment(snapshot: OrderSnapshot, input: ShipOrderInput): Either[ShippingError, Shipment] = ???
  private def toView(shipment: Shipment): ShipmentView = ???

object ShippingServiceLive:
  val layer: URLayer[OrderingPort & ShipmentRepository, ShippingService] =
    ZLayer.fromFunction(ShippingServiceLive.apply)
```

The **adapter** lives in `shipping-infra`, which is allowed to depend on `ordering-core`. It bridges the port to the real `OrderService`:

```scala
// shipping-infra: src/main/scala/com/myco/shipping/impl/adapters/
//                 OrderingAdapter.scala

package com.myco.shipping
package impl
package adapters

// Chained clause auto-imports: OrderingPort, OrderSnapshot from shipping.impl

import com.myco.ordering.{OrderService, OrderView}  // from ordering-core's public package
import zio.*

final case class OrderingAdapter(
  orderService: OrderService
) extends OrderingPort:

  override def getOrder(orderId: String): Task[Option[OrderSnapshot]] =
    orderService.getOrder(com.myco.ordering.OrderId(orderId))
      .map(view => Some(toSnapshot(view)))
      .catchSome { case _: com.myco.ordering.OrderError => ZIO.succeed(None) }

  private def toSnapshot(view: OrderView): OrderSnapshot =
    OrderSnapshot(
      orderId    = view.id.value,
      customerId = view.customerId.value,
      items      = view.items.map(i => OrderSnapshot.Item(i.catalogueNumber, i.quantity)),
      total      = view.total
    )

object OrderingAdapter:
  val layer: URLayer[OrderService, OrderingPort] =
    ZLayer.fromFunction(OrderingAdapter.apply)
```

This is a classic **anti-corruption layer**: the adapter translates between two bounded contexts' languages. If ordering's public contract changes, only the adapter needs updating — shipping's domain is untouched.

### 6.3 When Not to Use an Anti-Corruption Layer

If you find yourself writing an anti-corruption port whose types are nearly identical to the other context's public DTOs, and the two services share most of their domain vocabulary, that's a signal: **these services may belong in the same bounded context**.

There's nothing wrong with having **multiple services inside one bounded context**. A single `ordering-core` module can define both `OrderService` and `FulfillmentService` if they share the same domain model and evolve together:

```
ordering-core/
└── src/main/scala/com/myco/ordering/
    ├── OrderService.scala              ← public
    ├── FulfillmentService.scala        ← public
    ├── OrderView.scala                 ← shared public DTOs
    ├── FulfillmentView.scala           ← public
    ├── OrderError.scala                ← public
    ├── FulfillmentError.scala          ← public
    │
    └── impl/
        ├── Order.scala                 ← shared domain entity
        ├── Shipment.scala              ← domain entity
        ├── OrderRepository.scala       ← port
        ├── ShipmentRepository.scala    ← port
        ├── OrderServiceLive.scala      ← Live
        ├── FulfillmentServiceLive.scala← Live (can use OrderRepository directly)
        └── …
```

`FulfillmentServiceLive` can depend on `OrderRepository` directly — they are in the same `impl` package, sharing the same domain model. No anti-corruption layer, no adapter, no translation. This is **much simpler** when the coupling is genuine.

**Use this heuristic:** if two services share more than half their domain vocabulary and their data models evolve in lockstep, merge them into one bounded context with multiple services. If they have distinct vocabularies that merely overlap at the edges, keep them separate and use the anti-corruption layer.

---

## 7. Wiring in the App Module

The `app` module is the only place that sees all infrastructure modules (which transitively pull in their cores).
It wires every layer together:

```scala
// modules/app/.../Main.scala

object Main extends ZIOAppDefault:
  override val run =
    Server.serve(allRoutes).provide(
      // Ordering hexagon
      OrderServiceLive.layer,
      PostgresOrderRepository.layer,
      UUIDIdGenerator.layer,
      // Shipping hexagon
      ShippingServiceLive.layer,
      PostgresShipmentRepository.layer,
      OrderingAdapter.layer,           // ← bridges shipping's port to ordering's service
      // Infrastructure
      dataSourceLayer,
      Server.default
    )
```

### 7.1 Event-Driven Decoupling (Optional)

If you prefer asynchronous coupling, define events in the `*-core` module's **public** package. The consuming context subscribes via its anti-corruption adapter without a compile-time dependency on the publisher's internals.

```scala
// ordering-core: .../com/myco/ordering/OrderEvent.scala
package com.myco.ordering

enum OrderEvent:
  case OrderPlaced(orderId: OrderId, items: List[LineItemView])
  case OrderCancelled(orderId: OrderId)
```

Use ZIO Hub, Kafka, or an in-process event bus — the domain code does not care which.

---

## 8. Extracting a Microservice

The module split is designed so that extraction is mechanical, not architectural:

1. **Publish `ordering-core` as a library artefact** (e.g. to an internal Maven/Ivy repository).
2. **Move `ordering-core` + `ordering-infra` into their own repository/deployable.** They already form a self-contained hexagon.
3. **In the consuming monolith**, replace `OrderingAdapter` (which called `OrderService` in-process) with an `OrderingRemoteAdapter` that calls the extracted service over HTTP/gRPC. The `OrderingPort` trait in `shipping-core` does not change. Neither does `ShippingServiceLive`.

```
BEFORE (modulith)                        AFTER (microservice extracted)
┌─────────────────────────────┐         ┌────────────────────────────┐
│ app                         │         │ ordering-service            │ (own deployable)
│  ├─ ordering-infra          │  ──►    │  ├─ ordering-core           │
│  ├─ shipping-infra          │         │  └─ ordering-infra          │
│  └─ ...                     │         └────────────────────────────┘
└─────────────────────────────┘         ┌────────────────────────────┐
                                        │ main-app                    │
                                        │  ├─ shipping-infra          │
                                        │  │   └─ OrderingRemoteAdapter│ (HTTP/gRPC stub
                                        │  │      implements            │  implementing
                                        │  │      OrderingPort)         │  shipping's port)
                                        │  └─ ...                     │
                                        └────────────────────────────┘
```

Because `ShippingServiceLive` depends on `OrderingPort` (not `OrderService`), **zero domain code changes**. Only the adapter is swapped.

---

## 9. Dependency & Package Graph — Visual Summary

```
                  ┌───────────┐
                  │  commons  │
                  └─────┬─────┘
              ┌─────────┼─────────┐
              ▼                   ▼
      ┌──────────────┐   ┌──────────────┐
      │ordering-core │   │shipping-core │      1ST LAYER
      │              │   │              │      (never depend
      │ PUBLIC:      │   │ PUBLIC:      │       on each other)
      │  Service     │   │  Service     │
      │  DTOs,Errors │   │  DTOs,Errors │
      │              │   │              │
      │ IMPL:        │   │ IMPL:        │
      │  Domain      │   │  Domain      │
      │  Repo port   │   │  Repo port   │
      │  Live        │   │  OrderingPort│ ← anti-corruption port
      │  InMemory    │   │  Live        │
      └──────┬───────┘   │  InMemory    │
             │           └──────┬───────┘
             │                  │
             ▼                  ▼
      ┌──────────────┐   ┌──────────────┐
      │ordering-     │   │shipping-     │      2ND LAYER
      │  infra       │   │  infra       │
      │              │   │              │
      │ postgres/    │   │ postgres/    │
      │  DAO, SQL    │   │  DAO, SQL    │
      │              │   │ adapters/    │
      │              │   │  Ordering    │ ← implements OrderingPort
      │              │   │  Adapter     │   using ordering-core's
      │              │   │              │   public OrderService
      └──────┬───────┘   └──────┬───────┘
             │                  │
             └──────┬───────────┘
                    ▼
          ┌──────────────────────┐
          │         app          │
          │  (wires all layers)  │
          └──────────────────────┘

   ──▶  = depends on / can see
   shipping-infra depends on BOTH shipping-core AND ordering-core
```

---

## 10. Checklist — Adding a New Module

1. **Define the ubiquitous language.** Agree on terminology with domain experts before typing anything.
2. **Create `<context>-core` module.**
   - **Public package** (`com.myco.<context>`):
     - Service trait(s) with public input/output DTOs.
     - Error enums, published events.
     - **Treat this as a published API.** Changes require coordination with all consumers.
   - **`impl` package** (`com.myco.<context>.impl`):
     - Rich domain model (ADTs, entities, smart constructors) — flat in this package.
     - Repository port trait(s) — defined in terms of domain entities.
     - Anti-corruption port traits for any external context this service needs.
     - `Live` service implementation with parse/map logic between public DTOs and domain.
     - In-memory repository for tests.
     - All types scoped `private[<context>]`.
     - Every file uses chained clause `package com.myco.<context>` / `package impl`.
     - **Refactor freely.** As long as the public service contract is met, you can reshape everything here at will.
   - Depends only on `commons`.
3. **Create `<context>-infra` module.**
   - **`impl.postgres`** (or `impl.mysql`, etc.) — persistence adapter:
     - Implement repository port. Map domain entities ↔ DAO internally.
     - Define DAO types, SQL queries, DB migrations — all private to this sub-package.
   - **`impl.adapters`** — anti-corruption adapters:
     - Implement each port by delegating to the other context's public service trait.
     - Translate between the other context's public DTOs and this context's internal snapshot types.
   - **`impl.http`** — HTTP routes, JSON codecs (if applicable).
   - Use chained package clauses for zero-import access to domain types.
   - Depends on own `<context>-core` and any other `*-core` modules needed for adapters.
4. **Wire in `app` module.**
   - Add `dependsOn(<context>Infra)` (which transitively brings in `<context>Core`).
   - Provide the new layers — including anti-corruption adapter layers — in the main ZIO runtime.
5. **Write tests.**
   - Unit-test domain logic in `<context>-core/src/test` using in-memory layers and stub ports — no infrastructure.
   - Integration-test persistence in `<context>-infra/src/test` with a test container.
6. **Consider merging before splitting.** If the new service shares most of its domain vocabulary with an existing context, add it as a **second service in the same bounded context** (see §6.3) rather than creating a separate context with an anti-corruption layer.

---

## 11. Recommended Libraries

| Purpose | Library | Notes |
|---------|---------|-------|
| Effect system | **ZIO 2** | `ZIO[R, E, A]` for typed errors + dependency injection via `ZLayer` |
| HTTP | **zio-http** or **tapir + zio-http** | Tapir gives you OpenAPI docs for free; routes live in `*-infra` |
| JSON | **zio-json** | Derives codecs; keep codecs in `*-infra` next to routes |
| Database | **quill-zio** or **doobie + zio-interop** | Keep SQL in `*-infra`; domain stays persistence-agnostic |
| Config | **zio-config** | Load per-module configs via `ZLayer` |
| Testing | **zio-test** | First-class support for `ZLayer`-based test environments |
| Type transforms | **Chimney** | Reduces boilerplate when mapping between Domain ↔ DTO ↔ DAO |
| Refined types | **iron** (Scala 3) | Compile-time and runtime refinement for smart constructors |
| Non-empty collections | **zio.NonEmptyChunk** | Built into ZIO; use instead of `cats.data.NonEmptyList` |

---

## 12. Summary of Principles

1. **The public package is a stable contract; the `impl` package is free to change.** This is the single most important architectural rule.
2. **Core modules never depend on each other.** Every `*-core` depends only on `commons`. Cross-context communication uses anti-corruption ports and adapters.
3. **Domain first.** Types model the business, not the database or the wire format. But those types live *inside* the `impl` package, not on the public surface.
4. **No naked primitives.** Use opaque types or case classes for every domain concept.
5. **Make illegal states unrepresentable.** Sum types over `Option` + runtime checks.
6. **Parse, don't validate.** Boundary conversions return `Either[Error, DomainType]`.
7. **Non-uniform models.** Public DTOs ≠ internal domain ≠ persistence DAOs. Convert explicitly at each boundary.
8. **Repository ports speak domain language.** The port accepts and returns rich domain entities. Mapping to DAO is the adapter's responsibility.
9. **Anti-corruption ports speak the consumer's language.** The consuming context defines what it needs in its own types. The adapter translates.
10. **Chained package clauses eliminate imports.** Files in `impl.postgres` that declare `package com.myco.ordering` / `package impl` / `package postgres` get every enclosing package's types for free.
11. **Build + package visibility enforce boundaries.** `private[ordering]` prevents other contexts from seeing internals. The build graph prevents cross-infra dependencies.
12. **Prefer merging over thin anti-corruption layers.** If two services share most of their vocabulary and evolve together, put them in the same bounded context as sibling services rather than creating a separate context with an adapter.
13. **Microservice extraction is a deployment decision, not an architecture rewrite.** Move `*-core` + `*-infra` into their own deployable and swap the in-process adapter for a remote one.

---

*References: [Kubuszok — Modeling in Scala, Part 1](https://kubuszok.com/2024/modeling-in-scala-part-1/), [Wlaschin — Domain Modeling Made Functional](https://pragprog.com/titles/swdddf/domain-modeling-made-functional/), [King — Parse, Don't Validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/).*
