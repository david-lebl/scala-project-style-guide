# Domain Modeling

Turning business concepts into types: no primitives, ADTs, illegal states made
unrepresentable, smart constructors, entities vs values, and three separate
models (domain, DTO, DAO). Plain Scala 3; the only effect-system type used is
`zio.NonEmptyChunk`.

## Dependencies

- `"dev.zio" %% "zio"` — `NonEmptyChunk`
- optional: `"io.github.iltotore" %% "iron"` — refined types
- optional: `"io.scalaland" %% "chimney"` — case-class transformations

---

## Start from the domain, not the database

Ask who needs it, what they call it, what it's made of, and which values must
be rejected. Model as if all data were already in memory; the database shape
is a separate concern for the infra adapter (see [Persistence](310-persistence.md)).

```scala
// Wrong — persistence leaks into the domain:
final case class Customer(
  id:               UUID,
  billingFirstName: String,
  billingLastName:  String,
  billingAddressId: UUID      // a foreign key is not a business concept
)

// Right — models reality:
final case class Customer(id: Customer.Id, data: Customer.Data)
object Customer:
  final case class Data(billingName: BillingName, billingAddress: Address)
```

## No primitives in the domain

Every concept with domain meaning gets its own type — identifiers, quantities,
names, codes, configuration values. Two strings that mean different things are
different types.

```scala
// Wrong — arguments are trivially swapped:
def createOrder(customerId: String, catalogueNumber: String, currency: String): Order

// Right — the compiler rejects misuse:
def createOrder(customerId: Customer.Id, catalogueNumber: CatalogueNumber, currency: Currency): Order
```

**Opaque types** are erased at runtime — no boxing:

```scala
object Customer:
  opaque type Id = UUID
  object Id:
    def apply(value: UUID): Id = value
    extension (id: Id) def value: UUID = id
```

**Case classes** when the value has several fields or needs pattern matching:

```scala
final case class Money(currency: Currency, amount: BigDecimal)
```

| Need | Use |
|------|-----|
| Single wrapper, no invariants | opaque type |
| Single wrapper with invariants | opaque type + smart constructor |
| Several fields / pattern matching | case class |
| Closed set of values | `enum` |

## Algebraic data types

- **Products** — `final case class`: structural equality, `copy`,
  pattern matching, type-class derivation, immutable.
- **Sums** — Scala 3 `enum`: closed set, exhaustive matching.

```scala
enum OrderStatus:
  case Unpaid, Paid, InProgress, Shipped, Cancelled

enum PaymentResult:
  case Success(transactionId: TransactionId)
  case Declined(reason: DeclineReason)
```

## Make illegal states unrepresentable

Encode business rules in the shape of the types so invalid combinations can't
be constructed.

```scala
// Wrong — two Options plus a runtime assertion:
final case class UnitPrice(vat: Option[BigDecimal], discount: Option[BigDecimal]):
  assert(vat.isEmpty || discount.isEmpty)

// Right — a sum type allows only the valid combinations:
enum PriceHandling:
  case Domestic(discount: Discount)
  case NonDomestic(treaty: NonDomesticType)

enum Discount:
  case NoDiscount
  case LargeOrderDiscount(rate: Ratio)
```

Every `match` must now handle each combination, and adding a case breaks
compilation everywhere it's unhandled.

A collection that must not be empty is `NonEmptyChunk`, not a `List` plus a
comment:

```scala
final case class Data(items: NonEmptyChunk[LineItem], status: OrderStatus)
```

## Smart constructors

When an invariant can't be expressed in the type structure, make the
constructor private and expose a parser returning `Either`:

```scala
final case class Ratio private (asFraction: BigDecimal)
object Ratio:
  def parse(value: BigDecimal): Either[ValidationError, Ratio] =
    Either.cond(value >= 0, Ratio(value), ValidationError.NegativeRatio(value))

  val whole: Ratio = Ratio(BigDecimal(1))
```

In Scala 3 a `private` constructor also makes `copy` and `apply` private, so the
check can't be bypassed. The same works for opaque types:

```scala
opaque type Quantity = Int
object Quantity:
  def parse(value: Int): Either[ValidationError, Quantity] =
    Either.cond(value > 0, value, ValidationError.NonPositiveQuantity(value))
  extension (q: Quantity) def value: Int = q
```

> **Important:** never `require`, `assert` or `throw` in a constructor. An
> invalid value is an expected outcome — return `Left` and let the caller lift
> it into the typed error channel with `ZIO.fromEither` (see [Error
> Model](210-error-model.md)).

## Enums instead of `Boolean`

Replace `Boolean` parameters, fields and return values with a two-case enum, so
every call site states what it means:

```scala
// Wrong — the caller must remember what `true` means:
def recordFlush(success: Boolean, durationMs: Long): UIO[Unit]

// Right:
enum FlushOutcome:
  case Succeeded, Failed
def recordFlush(outcome: FlushOutcome, duration: Duration): UIO[Unit]
```

## Entities vs values

**Values** are defined by their fields and have no lifecycle (`Address`,
`Money`). **Entities** keep their identity across changes (`Order`,
`Customer`). Separate an entity's identity from its data:

```scala
final case class Order(id: Order.Id, data: Order.Data)
object Order:
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value
    extension (id: Id) def value: String = id

  final case class Data(
    customerId:      CustomerId,
    shippingAddress: Address,
    items:           NonEmptyChunk[LineItem],
    status:          OrderStatus
  )
```

This allows comparing identity (`a.id == b.id`), comparing data (`a.data ==
b.data`, "did anything change?"), and matching on either half.

## Nesting and namespacing

Types owned by one aggregate live in its companion (`Order.Id`, `Order.Data`,
`Address.City`). Types shared by several entities in the same context sit flat
in the `impl` package, where chained package clauses make them visible
everywhere in the context.

## Three models, not one

| Representation | Optimised for | Lives in |
|----------------|---------------|----------|
| Domain (`Order`) | business rules, correctness | `impl` (core) |
| DTO (`OrderView`, `CheckoutInput`) | API stability, serialisation | public package (core) |
| DAO (`OrderDAO`) | database schema | `impl.<tech>` (infra) |

They will drift apart, and that is the point. Conversions from outside into the
domain are **parse** steps returning `Either[Error, Domain]`; conversions out
of the domain are total functions.

## Helper libraries

- **iron** — refined types: `opaque type Quantity = Int :| Positive`, built with
  `value.refineEither[Positive]`.
- **Chimney** — derive DTO ↔ domain mappings with explicit overrides
  (`order.data.into[OrderView].withFieldConst(...).transform`). Use it in the
  `Live` or adapter, never in the public package.
