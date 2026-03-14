# 02 — Domain Modeling

> ADTs, strong types, opaque types, smart constructors, entities vs values — how to model a domain in Scala 3.

---

## 1. Start from the Domain, Not the Database

When you sit down with new functionality, resist the urge to draw SQL schemas. Instead, ask questions: Who needs this? What do they call it? What's it made of? What values should be rejected?

Your models should try to reflect reality. You won't achieve that if you see models only as representations of SQL table rows. Be **persistence-agnostic**: imagine all data is already in memory. Database representation comes later, in an entirely separate layer.

```scala
// ✗ Database-driven — leaks persistence into the domain
final case class Customer(
  id:               UUID,
  billingFirstName: String,
  billingLastName:  String,
  billingAddressId: UUID    // foreign key has no business in domain
)
)

// ✓ Domain-driven — models reality
final case class Customer(
  id:   Customer.Id,
  data: Customer.Data
)
object Customer:
  final case class Data(
    billingName:    BillingName,
    billingAddress: Address     // embedded, not a reference
  )
```

---

## 2. No Primitives in the Domain

Every concept that carries domain meaning gets its own type. Never pass raw `String`, `Int`, `Boolean` across a domain boundary. Even if two concepts are both strings, they are different types.

```scala
// ✗ Primitive obsession — easy to swap arguments
def createOrder(customerId: String, catalogueNumber: String, currency: String): Order

// ✓ Distinct types — compiler catches misuse
def createOrder(customerId: Customer.Id, catalogueNumber: CatalogueNumber, currency: Currency): Order
```

### Opaque types for zero-cost wrappers

Opaque types are erased at runtime — no boxing, no allocation. Use them for simple wrappers:

```scala
object Customer:
  opaque type Id = UUID
  object Id:
    def apply(value: UUID): Id = value
    extension (id: Id) def value: UUID = id
```

### Case classes for composite or structured values

Use case classes when the wrapper needs multiple fields, pattern matching, or automatic derivation:

```scala
final case class Money(currency: Currency, amount: BigDecimal):
  def +(other: Money): Money =
    require(currency == other.currency, "Cannot add different currencies")
    copy(amount = amount + other.amount)
```

### When to choose which

| Need | Use |
|------|-----|
| Single primitive wrapper, no invariants | Opaque type |
| Single primitive with invariants | Opaque type + smart constructor |
| Multiple fields | Case class |
| Pattern matching needed | Case class |
| JSON derivation needed | Case class (easier) or opaque with manual codec |

---

## 3. Algebraic Data Types

ADTs — the combination of product types (case classes) and sum types (enums) — are the fundamental building blocks.

### Product types (case classes)

A product type carries all its fields simultaneously. Use `final case class` for:

- Correct `equals`, `hashCode`, `toString` out of the box.
- `unapply` for pattern matching symmetrical to `apply`.
- `.copy` for creating modified instances.
- Immutability — safe in multithreaded environments.
- Type class derivation (JSON codecs, schema, etc.).

```scala
final case class Address(
  country:    Address.Country,
  city:       String,
  postalCode: String,
  line1:      String,
  line2:      String
)
```

### Sum types (enums)

A sum type is one of several possible shapes. Use Scala 3 `enum` for:

- Exhaustive pattern matching — the compiler warns about unhandled cases.
- Closed set of possibilities — no surprise subclasses at runtime.
- Clean syntax for both simple (case objects) and parameterised cases.

```scala
enum OrderStatus:
  case Unpaid, Paid, InProgress, Shipped, Cancelled

enum PaymentResult:
  case Success(transactionId: String)
  case Declined(reason: String)
  case GatewayError(cause: Throwable)
```

---

## 4. Make Illegal States Unrepresentable

The most powerful modeling technique: encode business rules in the type structure so invalid states **cannot be constructed**.

### Option fields hide intent

```scala
// ✗ Two Options with a runtime assertion — invisible at compile time
final case class UnitPrice(
  vat:      Option[BigDecimal],
  discount: Option[BigDecimal]
):
  assert(vat.isEmpty || discount.isEmpty) // runtime bomb
```

### Sum types encode constraints

```scala
// ✓ Impossible to have discount + no VAT — the type won't let you
enum PriceHandling:
  case Domestic(discount: Discount)
  case NonDomestic(treaty: NonDomesticType)

enum Discount:
  case NoDiscount
  case LargeOrderDiscount(rate: Ratio)

enum NonDomesticType:
  case NoTreaties
  case DoubleTaxAvoiding
```

Now pattern matching forces every handler to deal with each combination:

```scala
def calculatePrice(unitPrice: UnitPrice, quantity: Quantity): ItemPrice =
  unitPrice.priceHandling match
    case PriceHandling.Domestic(Discount.NoDiscount) =>
      ItemPrice(unitPrice.base * quantity, standardVatRate)
    case PriceHandling.Domestic(Discount.LargeOrderDiscount(rate)) =>
      ItemPrice(unitPrice.base * quantity * (Ratio.whole - rate), standardVatRate)
    case PriceHandling.NonDomestic(NonDomesticType.NoTreaties) =>
      ItemPrice(unitPrice.base * quantity, standardVatRate)
    case PriceHandling.NonDomestic(NonDomesticType.DoubleTaxAvoiding) =>
      ItemPrice(unitPrice.base * quantity, Vat.NotCollected)
```

Add a new case to the enum → the compiler fails in every place that forgot to handle it.

### Non-empty collections

If a list must have at least one element, use `NonEmptyChunk` (ZIO) or `NonEmptyList` (Cats):

```scala
final case class Order(
  id:    Order.Id,
  items: NonEmptyChunk[LineItem]  // impossible to create an order with no items
)
```

---

## 5. Smart Constructors

When a type has invariants that can't be expressed in the type structure itself, hide the default constructor and expose a factory that validates:

```scala
case class Ratio private (asFraction: BigDecimal)
object Ratio:
  def parse(value: BigDecimal): Either[ValidationError, Ratio] =
    Either.cond(value >= 0, Ratio(value), ValidationError.NegativeRatio)

  // Unsafe constructor for cases where the value is known-safe (e.g. constants)
  def unsafeFrom(value: BigDecimal): Ratio =
    parse(value).fold(e => throw new IllegalArgumentException(e.toString), identity)

  val whole: Ratio = Ratio(BigDecimal(1))  // private constructor accessible in companion
```

In Scala 3, making the constructor `private` also makes `.copy` and `new` private, preventing bypass.

---

## 6. Entities vs Values

**Values** are defined entirely by their fields. Two `Address` instances with the same data are interchangeable. They have no lifecycle.

**Entities** have an identity that persists through changes. A `Customer` who changes their name is still the same customer. They have a lifecycle.

### Separate identity from data

```scala
final case class Order(id: Order.Id, data: Order.Data)
object Order:
  opaque type Id = String
  // …
  final case class Data(
    shippingAddress: Address,
    items:           NonEmptyChunk[LineItem],
    status:          OrderStatus
  )
```

This gives you:

- **Compare identity:** `order1.id == order2.id`
- **Compare data:** `order1.data == order2.data` (did anything change?)
- **Compare whole:** `order1 == order2` (same identity AND same data?)
- **Pattern match selectively:** `case Order(id, _) =>` or `case Order(_, data) =>`

---

## 7. Nesting and Namespacing

Types that belong exclusively to one aggregate live in its companion object:

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
  object City:
    def apply(value: String): City = value
  // …
```

Benefits: clear ownership, reduced namespace pollution, easy to find definitions (look in the companion).

For domain types shared across multiple entities within the same bounded context, define them flat in the `impl` package so all files can access them via the chained package clause.

---

## 8. Non-Uniform Models

A domain entity, a JSON DTO, and a database row are **three different things** with different optimal structures:

| Representation | Optimised for | Example |
|---------------|--------------|---------|
| Domain | Business rules, correctness | `ItemPrice(netPrice: Money, vatRate: Vat[Ratio])` |
| DTO | Serialisation compatibility | `ItemPriceDTO(netPrice: MoneyDTO, vatRate: VatDTO[BigDecimal])` |
| DAO | Database schema compatibility | `ItemPriceRow(currency: String, netAmount: BigDecimal, vatRate: Option[BigDecimal])` |

Conversion between them is a **parse** step: `DTO.toDomain: Either[Error, Domain]`. See [05 — Persistence](05-persistence.md) for the full pattern.

---

## 9. Quality of Life Libraries

| Library | Purpose |
|---------|---------|
| **iron** | Refined types — compile-time and runtime constraints (`type PositiveInt = Int :| Positive`) |
| **Chimney** | Case class transformations — auto-maps matching fields, explicit overrides for the rest |
| **zio.NonEmptyChunk** | Non-empty collections built into ZIO |
| **zio-json** | Derive JSON codecs from case classes and enums |

### Example with iron

```scala
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

opaque type Quantity = Int :| Positive
object Quantity:
  def parse(value: Int): Either[String, Quantity] =
    value.refineEither[Positive]
```

### Example with Chimney

```scala
import io.scalaland.chimney.dsl.*

// Domain → DTO with automatic field mapping + explicit overrides
def toView(order: Order): OrderView =
  order.data
    .into[OrderView]
    .withFieldConst(_.id, OrderId(order.id.value))
    .withFieldComputed(_.total, d => d.items.map(_.lineTotal).sum)
    .transform
```

---

## 10. Summary

1. **Start from the domain, not the database.** Be persistence-agnostic.
2. **No primitives.** Use opaque types or case classes for every concept.
3. **Use ADTs.** Case classes for products, enums for sums.
4. **Make illegal states unrepresentable.** Sum types over `Option` + assertions.
5. **Use smart constructors** when invariants can't be encoded in types.
6. **Separate entity identity from data** with `case class Entity(id: Id, data: Data)`.
7. **Nest owned types** in companion objects.
8. **Keep three separate models.** Domain ≠ DTO ≠ DAO. Parse at boundaries.

---

*See also: [03 — Error Model](03-error-model.md), [05 — Persistence](05-persistence.md), [04 — Service Design](04-service-design.md)*
