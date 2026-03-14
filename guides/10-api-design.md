# \[WIP\] 10 — API Design

> HTTP routes, JSON codecs, request/response conventions, versioning, and OpenAPI.

---

## 1. Routes Live in the Infra Layer

HTTP routes are **infrastructure**, not domain logic. They live in the `*-infra` module under `impl.http`:

```
ordering-infra/
└── src/main/scala/com/myco/ordering/
    └── impl/
        └── http/
            ├── OrderRoutes.scala
            ├── ErrorMapper.scala
            └── JsonCodecs.scala
```

Routes depend on the **public service trait**, not on domain internals. They receive public DTOs, call the service, and return public views:

```scala
package com.myco.ordering
package impl
package http

// Chained clause auto-imports: OrderService, CheckoutInput, OrderView, OrderError, etc.

import zio.*
import zio.http.*

object OrderRoutes:
  def routes: Routes[OrderService, Nothing] =
    Routes(
      checkoutRoute,
      getOrderRoute,
      cancelOrderRoute
    )
```

---

## 2. Request / Response Conventions

### Requests map to public input DTOs

The route parses the HTTP request body into the public input DTO. JSON decoding happens here, not in the service:

```scala
private val checkoutRoute =
  Method.POST / "orders" -> handler: (req: Request) =>
    (for
      input <- req.body.to[CheckoutInput].mapError(…)
      view  <- OrderService.checkout(input)
    yield Response.json(view.toJson).status(Status.Created))
      .catchAll(handleError)
```

### Responses map to public view DTOs

The service returns a view DTO. The route serialises it to JSON:

```scala
// OrderView → JSON (codec derived or defined in JsonCodecs.scala)
Response.json(view.toJson).status(Status.Ok)
```

### JSON codecs

Keep codecs in the `impl.http` package, co-located with the routes:

```scala
package com.myco.ordering
package impl
package http

import zio.json.*

// Derive codecs for public DTOs
given JsonEncoder[OrderView]      = DeriveJsonEncoder.gen
given JsonDecoder[CheckoutInput]  = DeriveJsonDecoder.gen
given JsonEncoder[LineItemView]   = DeriveJsonEncoder.gen
given JsonDecoder[LineItemInput]  = DeriveJsonDecoder.gen
given JsonDecoder[AddressInput]   = DeriveJsonDecoder.gen

// OrderId and CustomerId need manual codecs for opaque types
given JsonEncoder[OrderId]    = JsonEncoder.string.contramap(_.value)
given JsonDecoder[OrderId]    = JsonDecoder.string.map(OrderId(_))
given JsonEncoder[CustomerId] = JsonEncoder.string.contramap(_.value)
given JsonDecoder[CustomerId] = JsonDecoder.string.map(CustomerId(_))
```

---

## 3. Error-to-HTTP Mapping

Error mapping happens **in the route handler**, never in the service. The error's `Code` carries an `httpStatus` hint:

```scala
package com.myco.ordering
package impl
package http

import zio.json.*

final case class ErrorResponse(code: String, message: String) derives JsonEncoder

private def handleError(error: OrderError): UIO[Response] =
  val status   = Status.fromInt(error.code.httpStatus)
  val body     = ErrorResponse(error.code.value, error.message)
  ZIO.logWarning(s"Order error: ${error.message}") *>
    ZIO.succeed(Response.json(body.toJson).status(status))
```

### Standard error response shape

All contexts use the same JSON structure for errors:

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

### Defects → 500

Unhandled defects (infrastructure failures) should produce a generic 500 response without leaking internal details:

```scala
// Global middleware or route-level handler
.catchAllDefect: cause =>
  ZIO.logErrorCause("Unhandled defect", Cause.die(cause)) *>
    ZIO.succeed(Response.json("""{"code":"INTERNAL","message":"Internal server error"}""")
      .status(Status.InternalServerError))
```

---

## 4. Route Grouping

One `Routes` object per bounded context. The `app` module merges all context routes:

```scala
// ordering-infra
object OrderRoutes:
  def routes: Routes[OrderService, Nothing] = Routes(
    Method.POST / "orders"            -> handler(checkout),
    Method.GET  / "orders" / string("id") -> handler(getOrder),
    Method.POST / "orders" / string("id") / "cancel" -> handler(cancel)
  )

// shipping-infra
object ShippingRoutes:
  def routes: Routes[ShippingService, Nothing] = Routes(…)

// app
object Main extends ZIOAppDefault:
  val allRoutes = OrderRoutes.routes ++ ShippingRoutes.routes
  override val run = Server.serve(allRoutes).provide(…)
```

### URL conventions

| Pattern | Example | Method |
|---------|---------|--------|
| Create resource | `POST /orders` | Create |
| Get by ID | `GET /orders/:id` | Read |
| List with filters | `GET /orders?customerId=…&status=…` | List |
| Update | `PUT /orders/:id` or `PATCH /orders/:id` | Update |
| Action on resource | `POST /orders/:id/cancel` | Command |

Use **nouns for resources**, **verbs for actions** that don't map cleanly to CRUD.

---

## 5. Tapir Integration (Optional)

Tapir provides endpoint definitions as documentation-first contracts, with automatic OpenAPI generation.

```scala
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.ztapir.*

val checkoutEndpoint =
  endpoint
    .post
    .in("orders")
    .in(jsonBody[CheckoutInput])
    .out(jsonBody[OrderView].and(statusCode(StatusCode.Created)))
    .errorOut(
      oneOf[ErrorResponse](
        oneOfVariant(StatusCode.UnprocessableEntity, jsonBody[ErrorResponse]),
        oneOfVariant(StatusCode.NotFound, jsonBody[ErrorResponse]),
        oneOfVariant(StatusCode.Conflict, jsonBody[ErrorResponse])
      )
    )
    .description("Create a new order from the customer's basket")

// Server implementation
val checkoutServerEndpoint =
  checkoutEndpoint.zServerLogic: input =>
    OrderService.checkout(input).mapError(toErrorResponse)

// Generate OpenAPI docs
val docs = OpenAPIDocsInterpreter().toOpenAPI(List(checkoutEndpoint), "Ordering API", "1.0")
```

Tapir endpoint definitions can live in the `*-core` public package if you want to generate client libraries from them. Otherwise, keep them in `*-infra/impl.http`.

---

## 6. Versioning Strategy

### URL path versioning

Use path-based versioning for **major breaking changes**:

```
/v1/orders
/v2/orders    ← new version with incompatible changes
```

### Additive changes don't require a new version

Adding a new optional field to a response, adding a new endpoint, or adding a new optional query parameter are **backward compatible**. Don't bump the version for these.

### Deprecation path

1. Add the new field/endpoint alongside the old one.
2. Mark the old one as deprecated in documentation.
3. Remove in the next major version.

### Version-specific DTOs (when needed)

If a version change requires incompatible DTOs, create version-specific types:

```scala
// v1 — original
final case class OrderViewV1(id: OrderId, status: String, total: BigDecimal)

// v2 — breaking change: total split into subtotal + tax
final case class OrderViewV2(id: OrderId, status: String, subtotal: BigDecimal, tax: BigDecimal)
```

Both versions can delegate to the same service — the mapping happens in the route handler.

---

## 7. Authentication and Authorization

### Middleware extracts auth context

Authentication is a middleware concern. It extracts a typed `AuthContext` and makes it available to route handlers:

```scala
final case class AuthContext(userId: UserId, roles: Set[Role])

def authMiddleware: HandlerAspect[Any, AuthContext] = ???
// Extracts JWT/session, validates, produces AuthContext
```

### Service receives typed context

The service method receives the authenticated user as a parameter, not a raw token:

```scala
trait OrderService:
  def checkout(input: CheckoutInput, userId: UserId): IO[OrderError, OrderView]
```

Or, if auth context is request-scoped, use ZIO's `FiberRef`:

```scala
val authContextRef: FiberRef[AuthContext] = ???
```

### Authorization in the service

Authorization logic (e.g. "only the order's owner can cancel it") belongs in the `Live` service, not in routes:

```scala
override def cancelOrder(id: OrderId, userId: UserId): IO[OrderError, Unit] =
  for
    order <- findOrder(id)
    _     <- ZIO.cond(order.data.customerId == userId, (), OrderError.NotAuthorized)
    _     <- doCancellation(order)
  yield ()
```

---

## 8. Pagination and Filtering

### Standard query parameters

```
GET /orders?customerId=cust-1&status=Unpaid&offset=0&limit=20&sort=createdAt:desc
```

### Paged response type

Define a generic paged response in `commons`:

```scala
final case class PagedResponse[A](
  items:  List[A],
  total:  Long,
  offset: Int,
  limit:  Int
)
```

### Pagination in the repository

Keep pagination logic in the repository port, not in the service:

```scala
private[ordering] trait OrderRepository:
  def save(order: Order): Task[Unit]
  def findById(id: Order.Id): Task[Option[Order]]
  def findByCustomer(
    customerId: Customer.Id,
    offset:     Int,
    limit:      Int
  ): Task[(List[Order], Long)]   // (items, totalCount)
```

---

## 9. Summary

1. **Routes live in `*-infra/impl.http`.** They depend on the public service trait, not internals.
2. **JSON codecs next to routes.** Derived with zio-json, co-located in the `http` package.
3. **Map errors to HTTP in the route handler.** Use `error.code.httpStatus` as the hint.
4. **Standard error shape.** `{ "code": "…", "message": "…" }` across all contexts.
5. **One `Routes` object per context.** Merged in the `app` module.
6. **URL path versioning** for breaking changes only. Additive changes are backward compatible.
7. **Auth in middleware, authorization in the service.** Typed `AuthContext`, not raw tokens.

---

*See also: [03 — Error Model](03-error-model.md), [04 — Service Design](04-service-design.md), [01 — Project Structure](01-project-structure.md)*
