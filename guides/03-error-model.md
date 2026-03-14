# \[WIP\] 03 — Error Model

> How to define, structure, propagate, compose, and map errors in a Scala 3 + ZIO backend.

---

## 1. Guiding Principles

1. **Domain errors are not exceptions.** They are expected outcomes — "item out of stock", "invalid address" — that must be handled explicitly. They travel through ZIO's **typed error channel**.
2. **Infrastructure failures are defects.** A dead database connection or corrupt payload is not a domain concern. These travel through ZIO's **defect channel** and are logged and reported, not pattern-matched.
3. **Each bounded context owns its error enum.** There is no global `AppError`. Errors are translated at context boundaries, just like data.
4. **Errors carry structure, not just strings.** Every error has a **code** (stable, machine-readable), a **message** (human-readable, interpolated), and enough context to diagnose the issue without looking at logs.

---

## 2. Error Enum per Bounded Context

Each bounded context defines a single `enum` in its **public package**. This enum is part of the stable contract — other contexts (and HTTP layers) depend on it.

```scala
// ordering-core: src/main/scala/com/myco/ordering/OrderError.scala

package com.myco.ordering

enum OrderError(val code: OrderError.Code, val details: String):

  /** Computed human-readable message. */
  def message: String = s"[${code.value}] $details"

  // ── domain cases ──────────────────────────────────────

  case EmptyItemList
    extends OrderError(Code.EmptyItemList, "Cannot create an order with no items")

  case ItemNotFound(catalogueNumber: String)
    extends OrderError(Code.ItemNotFound, s"Item '$catalogueNumber' does not exist in the catalogue")

  case InsufficientStock(catalogueNumber: String, requested: Int, available: Int)
    extends OrderError(
      Code.InsufficientStock,
      s"Item '$catalogueNumber': requested $requested but only $available available"
    )

  case InvalidAddress(reason: String)
    extends OrderError(Code.InvalidAddress, s"Shipping address is invalid: $reason")

  case OrderNotFound(orderId: OrderId)
    extends OrderError(Code.OrderNotFound, s"Order '${orderId.value}' not found")

  case OrderAlreadyCancelled(orderId: OrderId)
    extends OrderError(Code.OrderAlreadyCancelled, s"Order '${orderId.value}' is already cancelled")

  case PaymentDeclined(reason: String)
    extends OrderError(Code.PaymentDeclined, s"Payment was declined: $reason")

end OrderError
```

### 2.1 Error Codes

Each error case maps to a **stable code** with a string identifier and an HTTP status. The code is a nested enum — it never changes even if the message wording evolves.

```scala
// Same file, continued

object OrderError:

  enum Code(val value: String, val httpStatus: Int):
    case EmptyItemList       extends Code("ORD-001", 422)
    case ItemNotFound        extends Code("ORD-002", 404)
    case InsufficientStock   extends Code("ORD-003", 409)
    case InvalidAddress      extends Code("ORD-004", 422)
    case OrderNotFound       extends Code("ORD-005", 404)
    case OrderAlreadyCancelled extends Code("ORD-006", 409)
    case PaymentDeclined     extends Code("ORD-007", 402)

end OrderError
```

**Code conventions:**

- Prefix with a short context abbreviation: `ORD-`, `SHP-`, `USR-`, etc.
- Number sequentially within the context. Gaps are fine — never reuse a retired number.
- `httpStatus` is a **hint** for the HTTP layer, not a hard rule. The HTTP mapper may override it contextually.

### 2.2 Why This Structure?

- **Exhaustive pattern matching.** Add a new `case` → the compiler fails everywhere it's unhandled.
- **Stable codes for API consumers.** Front-end teams key on `"ORD-003"`, not on message text.
- **Structured context.** `InsufficientStock` carries `catalogueNumber`, `requested`, `available` — enough to render a precise UI message or populate a monitoring dashboard without parsing strings.
- **Single source of truth.** The code, the message template, and the HTTP status hint live together. No separate mapping files to keep in sync.

---

## 3. ZIO Error Channel Strategy

ZIO's `IO[E, A]` has **two** failure paths:

| Channel | Type | Purpose | Recovery |
|---------|------|---------|----------|
| **Typed error** | `E` in `IO[E, A]` | Domain errors — expected, handleable outcomes | Pattern match, `.catchSome`, `mapError` |
| **Defect** | `Throwable` (hidden) | Infrastructure failures — unexpected, unrecoverable | `.orDie`, logged at the top, crash or retry |

### 3.1 Domain Errors → Typed Channel

Every service method returns `IO[<Context>Error, A]`:

```scala
trait OrderService:
  def checkout(input: CheckoutInput): IO[OrderError, OrderView]
```

Inside the `Live` implementation, domain errors are constructed explicitly:

```scala
private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository,
  idGen:     IdGenerator
) extends OrderService:

  override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
    for
      items <- ZIO.fromEither(parseItems(input.items))       // Either[OrderError, …] → typed channel
      _     <- ZIO.cond(items.nonEmpty, (), OrderError.EmptyItemList)  // direct fail
      // …
    yield view
```

### 3.2 Infrastructure Failures → Defect Channel

Repository ports return `Task[A]` (alias for `IO[Throwable, A]`). The `Live` service converts these to defects because a database failure is not a domain concern:

```scala
override def getOrder(id: OrderId): IO[OrderError, OrderView] =
  for
    order <- orderRepo.findById(Order.Id(id.value))
               .orDie                                    // Task → UIO, Throwable becomes defect
               .someOrFail(OrderError.OrderNotFound(id)) // Option → typed error
  yield toView(order)
```

**Rule of thumb:** if the caller can do something meaningful about the failure (show a message, retry with different input, take an alternative path), it's a **typed error**. If all the caller can do is give up and report, it's a **defect**.

### 3.3 Never Mix the Two

```scala
// ✗ Wrong — domain error thrown as exception
def checkout(input: CheckoutInput): Task[OrderView] =
  if input.items.isEmpty then ZIO.fail(new IllegalArgumentException("empty items"))
  else ???

// ✗ Wrong — infrastructure failure in the typed channel
def checkout(input: CheckoutInput): IO[OrderError | SQLException, OrderView] = ???

// ✓ Correct — clean separation
def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
  for
    _     <- ZIO.cond(input.items.nonEmpty, (), OrderError.EmptyItemList) // domain
    order <- orderRepo.save(order).orDie                                  // infra → defect
  yield view
```

---

## 4. Error Composition Across Layers

### 4.1 Within a Service

A single service method may call multiple operations that produce different flavours of validation failure. Compose them naturally with for-comprehensions — they short-circuit on the first `Left` / `ZIO.fail`:

```scala
override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
  for
    items   <- ZIO.fromEither(parseItems(input.items))     // may fail with EmptyItemList
    address <- ZIO.fromEither(parseAddress(input.address)) // may fail with InvalidAddress
    stock   <- checkStock(items)                           // may fail with InsufficientStock
    // all validated — proceed
    orderId <- idGen.generate.orDie
    order    = buildOrder(orderId, items, address, stock)
    _       <- orderRepo.save(order).orDie
  yield toView(order)
```

If you need to **accumulate** multiple validation errors instead of short-circuiting (e.g. form validation), use `ZIO.validate` or model a `NonEmptyChunk[OrderError]` as your error type for that specific operation:

```scala
def validateCheckout(input: CheckoutInput): IO[NonEmptyChunk[OrderError], ValidatedCheckout] =
  ZIO.validate(
    parseItems(input.items),
    parseAddress(input.address),
    checkCustomer(input.customerId)
  )(ValidatedCheckout.apply)
```

### 4.2 Across Bounded Contexts (Anti-Corruption Layer)

When a service calls another context through an anti-corruption port, the adapter translates errors:

```scala
// shipping-infra: .../impl/adapters/OrderingAdapter.scala

package com.myco.shipping
package impl
package adapters

import com.myco.ordering.{OrderService, OrderError as ExtOrderError}
import zio.*

final case class OrderingAdapter(
  orderService: OrderService
) extends OrderingPort:

  override def getOrder(orderId: String): Task[Option[OrderSnapshot]] =
    orderService.getOrder(com.myco.ordering.OrderId(orderId))
      .map(view => Some(toSnapshot(view)))
      .catchSome:
        case ExtOrderError.OrderNotFound(_) => ZIO.none  // expected → Option.None
      .mapError: extErr =>
        // Unexpected ordering errors become infrastructure failures in shipping's world.
        // They'll surface as defects when ShippingServiceLive calls .orDie on the Task.
        new RuntimeException(s"Ordering service failed: ${extErr.message}")
```

**The consuming context never exposes another context's error type.** It translates to either its own domain error, `Option`/`None` for "not found" cases, or a `Throwable` defect.

### 4.3 Across the HTTP Boundary

Error-to-HTTP mapping happens in the **infra layer** — never in the core. The error's `Code` carries an `httpStatus` hint that the mapper uses:

```scala
// ordering-infra: .../impl/http/ErrorMapper.scala

package com.myco.ordering
package impl
package http

import zio.json.*

// JSON error response body
final case class ErrorResponse(code: String, message: String)
object ErrorResponse:
  given JsonEncoder[ErrorResponse] = DeriveJsonEncoder.gen

def mapOrderError(error: OrderError): (Int, ErrorResponse) =
  val status   = error.code.httpStatus
  val response = ErrorResponse(error.code.value, error.message)
  (status, response)
```

Used in a route handler:

```scala
// ordering-infra: .../impl/http/OrderRoutes.scala

package com.myco.ordering
package impl
package http

import zio.*
import zio.http.*

def checkoutRoute: Route[OrderService, Nothing] =
  Method.POST / "orders" -> handler: (req: Request) =>
    (for
      input <- req.body.to[CheckoutInput].orDie
      view  <- OrderService.checkout(input)
    yield Response.json(view.toJson).status(Status.Created))
      .catchAll: (error: OrderError) =>
        val (status, body) = mapOrderError(error)
        ZIO.succeed(Response.json(body.toJson).status(Status.fromCode(status)))
```

### 4.4 Error Response JSON Format

Standardise the error response shape across all contexts:

```json
{
  "code": "ORD-003",
  "message": "[ORD-003] Item 'SKU-1234': requested 10 but only 3 available"
}
```

For accumulated validation errors:

```json
{
  "code": "ORD-100",
  "message": "[ORD-100] Checkout validation failed",
  "errors": [
    { "code": "ORD-001", "message": "[ORD-001] Cannot create an order with no items" },
    { "code": "ORD-004", "message": "[ORD-004] Shipping address is invalid: missing postal code" }
  ]
}
```

---

## 5. Patterns and Recipes

### 5.1 Wrapping External Library Errors

When calling an external library that throws or returns its own error type, convert at the boundary:

```scala
// In the persistence adapter
override def save(order: Order): Task[Unit] =
  ZIO.attemptBlocking:      // catches any Throwable → Task
    db.execute(insertQuery)
```

If the external error is **meaningful** to the domain (e.g. a unique constraint violation means "order already exists"), catch it specifically:

```scala
override def save(order: Order): IO[OrderError, Unit] =
  ZIO.attemptBlocking(db.execute(insertQuery))
    .catchSome:
      case _: DuplicateKeyException =>
        ZIO.fail(OrderError.OrderAlreadyExists(order.id))
    .orDie  // all other SQL exceptions → defect
```

### 5.2 "Not Found" Convention

Use `Option` at the repository level and convert to a typed error in the service:

```scala
// Repository port — returns Option
trait OrderRepository:
  def findById(id: Order.Id): Task[Option[Order]]

// Live service — converts Option → typed error
override def getOrder(id: OrderId): IO[OrderError, OrderView] =
  orderRepo.findById(Order.Id(id.value))
    .orDie                                      // Task → UIO
    .someOrFail(OrderError.OrderNotFound(id))   // Option → IO[OrderError, Order]
    .map(toView)
```

### 5.3 Conditional / Guard Errors

For simple precondition checks, use `ZIO.cond` or `ZIO.when`:

```scala
_ <- ZIO.cond(
       items.nonEmpty,
       (),
       OrderError.EmptyItemList
     )

_ <- ZIO.when(order.data.status == OrderStatus.Cancelled):
       ZIO.fail(OrderError.OrderAlreadyCancelled(order.id))
```

### 5.4 Enriching Errors with Context

Sometimes a lower-level error needs extra context. Use `.mapError`:

```scala
def processItem(item: LineItemInput): IO[OrderError, LineItem] =
  catalogue.lookup(item.catalogueNumber)
    .orDie
    .someOrFail(OrderError.ItemNotFound(item.catalogueNumber))

def processAllItems(items: List[LineItemInput]): IO[OrderError, NonEmptyChunk[LineItem]] =
  ZIO.foreach(items)(processItem)           // fails on first bad item
    .map(NonEmptyChunk.fromIterableOption)
    .someOrFail(OrderError.EmptyItemList)
```

### 5.5 Logging Errors

Log domain errors at the **service boundary** (in the HTTP layer or event handler), not inside the domain logic. This keeps domain code pure and avoids duplicate logging:

```scala
// In the HTTP route
.catchAll: (error: OrderError) =>
  ZIO.logWarning(s"Order operation failed: ${error.message}") *>
    ZIO.succeed(errorResponse(error))
```

Log defects at the **top level** using ZIO's built-in defect reporting, or with a global error handler:

```scala
// In Main or a middleware
override val run =
  Server.serve(allRoutes)
    .tapDefect(cause => ZIO.logErrorCause("Unhandled defect", cause))
    .provide(/* layers */)
```

---

## 6. Testing Errors

### 6.1 Testing Domain Error Cases

Since errors are values (not exceptions), testing them is straightforward:

```scala
import zio.test.*
import zio.test.Assertion.*

suite("OrderServiceLive.checkout")(
  test("fails with EmptyItemList when items are empty"):
    val input = CheckoutInput(customerId, address, items = List.empty)
    for
      result <- OrderService.checkout(input).exit
    yield assert(result)(fails(equalTo(OrderError.EmptyItemList))),

  test("fails with ItemNotFound for unknown catalogue number"):
    val input = CheckoutInput(customerId, address, items = List(unknownItem))
    for
      result <- OrderService.checkout(input).exit
    yield assert(result)(fails(isSubtype[OrderError.ItemNotFound](anything))),

  test("fails with InsufficientStock when quantity exceeds available"):
    val input = CheckoutInput(customerId, address, items = List(tooManyItems))
    for
      result <- OrderService.checkout(input).exit
    yield assert(result)(fails(isSubtype[OrderError.InsufficientStock](
      hasField("available", _.available, equalTo(3))
    )))
).provide(
  OrderServiceLive.layer,
  InMemoryOrderRepository.layer,
  InMemoryCatalogue.layer,
  UUIDIdGenerator.layer
)
```

### 6.2 Testing Error Codes and Messages

Verify the error carries the right code and message for API consumer expectations:

```scala
test("EmptyItemList has correct code"):
  val error = OrderError.EmptyItemList
  assertTrue(
    error.code == OrderError.Code.EmptyItemList,
    error.code.value == "ORD-001",
    error.code.httpStatus == 422,
    error.message.contains("no items")
  )
```

### 6.3 Testing HTTP Error Mapping

```scala
test("EmptyItemList maps to 422 with correct body"):
  val (status, body) = mapOrderError(OrderError.EmptyItemList)
  assertTrue(
    status == 422,
    body.code == "ORD-001"
  )
```

---

## 7. Anti-Patterns to Avoid

| Anti-pattern | Problem | Solution |
|-------------|---------|----------|
| `throw new Exception(msg)` in domain code | Bypasses typed channel, invisible to callers | Return `ZIO.fail(DomainError.Case)` |
| `IO[Throwable, A]` for service methods | No distinction between domain and infra errors | Use `IO[DomainError, A]` |
| String-only errors (`IO[String, A]`) | Can't pattern match, no structure, no codes | Use an `enum` with codes |
| Global `AppError` enum | All contexts coupled to one type, can't evolve independently | One enum per bounded context |
| Logging inside domain logic | Duplicated logs, impure domain | Log at the HTTP/event boundary |
| Catching domain errors as exceptions | Loses type safety, hides expected outcomes | Let them flow through the typed channel |
| Exposing another context's error type | Couples contexts at the error level | Translate in the anti-corruption adapter |
| Runtime `assert` for domain rules | Throws exceptions, no typed feedback | Use `ZIO.cond` / `ZIO.fail` / `Either` |

---

## 8. Summary

1. **One error `enum` per bounded context** with a nested `Code` enum for stable, machine-readable identifiers.
2. **Domain errors → typed channel** (`IO[OrderError, A]`). **Infrastructure failures → defect channel** (`.orDie`).
3. **Errors carry structure**: code (string ID + HTTP hint), human-readable message, and contextual fields.
4. **Map errors to HTTP** in the infra layer using the code's `httpStatus` hint. Never in the core.
5. **Translate errors at context boundaries** via anti-corruption adapters. Never expose foreign error types.
6. **Accumulate** with `ZIO.validate` when you need multiple validation errors. **Short-circuit** with for-comprehensions when one failure should stop the pipeline.
7. **Test errors as values** — assert on specific enum cases, codes, and messages.
