# API Design

HTTP routes as an infrastructure adapter: where routes live, how requests and
responses map to DTOs, how errors and defects become status codes, URL and
versioning conventions, authentication and pagination. Read this before adding
or changing an endpoint.

## Dependencies

- `"dev.zio" %% "zio-http"` — `Routes`, `Handler`, `Server`
- `"dev.zio" %% "zio-json"` — JSON codecs

---

## Routes live in infra

Routes are infrastructure. They live in `*-infra` under `impl.http`, next to
their codecs and error mapping:

```
ordering-infra/src/main/scala/com/myco/ordering/impl/http/
├── OrderRoutes.scala
├── ErrorMapping.scala
└── JsonCodecs.scala
```

Routes depend only on the **public service trait** and public DTOs, never on
domain internals. A handler does three things: decode the request into an input
DTO, call the service, encode the view or the error. No business logic.

```scala
package com.myco.ordering
package impl
package http

import zio.*
import zio.http.*
import zio.json.*

object OrderRoutes:
  val routes: Routes[OrderService, Nothing] =
    Routes(
      Method.POST / "v1" / "orders" -> handler: (req: Request) =>
        (for
          input <- req.body.asString.orDie.flatMap(decode[CheckoutInput])
          view  <- OrderService.checkout(input).mapError(toResponse)
        yield Response.json(view.toJson).status(Status.Created)).merge,

      Method.GET / "v1" / "orders" / string("id") -> handler: (id: String, _: Request) =>
        OrderService.getOrder(OrderId(id))
          .mapBoth(toResponse, view => Response.json(view.toJson))
          .merge
    )

  private def decode[A: JsonDecoder](body: String): IO[Response, A] =
    ZIO.fromEither(body.fromJson[A]).mapError(badRequest)
```

One `Routes` value per context; `app` concatenates them with `++`.

## JSON codecs

Codecs for public DTOs live in `impl.http`, not on the DTOs themselves, so the
public package stays free of serialisation libraries:

```scala
package com.myco.ordering
package impl
package http

import zio.json.*

given JsonCodec[OrderId]       = JsonCodec.string.transform(OrderId(_), _.value)
given JsonCodec[CustomerId]    = JsonCodec.string.transform(CustomerId(_), _.value)
given JsonDecoder[LineItemInput] = DeriveJsonDecoder.gen
given JsonDecoder[CheckoutInput] = DeriveJsonDecoder.gen
given JsonEncoder[LineItemView]  = DeriveJsonEncoder.gen
given JsonEncoder[OrderView]     = DeriveJsonEncoder.gen
```

Opaque types need explicit codecs; enums are encoded as their name string.
A decoding failure is a 400 with the standard error body — never a defect.

## Errors to HTTP

The mapping lives in the route layer, never in the core. The error's `Code`
carries the status hint and the stable identifier:

```scala
final case class ErrorResponse(code: String, message: String) derives JsonEncoder

private[http] def toResponse(error: OrderError): Response =
  Response
    .json(ErrorResponse(error.code.value, error.message).toJson)
    .status(Status.fromInt(error.code.httpStatus))

private[http] def badRequest(reason: String): Response =
  Response.json(ErrorResponse("BAD_REQUEST", reason).toJson).status(Status.BadRequest)
```

Standard error body, identical across all contexts:

```json
{ "code": "ORD-003", "message": "[ORD-003] Item 'SKU-1234': requested 10 but only 3 available" }
```

Accumulated validation errors add an `errors` array of the same shape.

### Defects → 500

Defects become a generic 500 with no internal details, and are logged once
with the full cause. Install this once for all routes in `app` (or as a
`Middleware`), not per handler:

```scala
val allRoutes: Routes[OrderService & ShippingService, Nothing] =
  (OrderRoutes.routes ++ ShippingRoutes.routes)
    .handleErrorCauseZIO: cause =>
      ZIO.logErrorCause("Unhandled defect", cause).as(
        Response
          .json(ErrorResponse("INTERNAL", "Internal server error").toJson)
          .status(Status.InternalServerError)
      )
```

> **Warning:** never put an exception message, stack trace or SQL text in a
> response body.

Log domain errors once per request at `warning` (in `toResponse` or a
middleware); log defects at `error` with `ZIO.logErrorCause`.

## URL conventions

| Operation | Example |
|-----------|---------|
| create | `POST /v1/orders` |
| get by id | `GET /v1/orders/{id}` |
| list with filters | `GET /v1/orders?customerId=…&status=…` |
| update | `PUT` / `PATCH /v1/orders/{id}` |
| domain action | `POST /v1/orders/{id}/cancel` |

Nouns for resources; a verb sub-resource for actions that aren't CRUD.

## Versioning

- Path versioning (`/v1`, `/v2`) for breaking changes only.
- Adding an endpoint, an optional request field, or a response field is not
  breaking — no new version.
- Deprecation: add the new form beside the old, document the old as deprecated,
  remove it in the next major version.
- When versions need incompatible shapes, define version-specific DTOs in
  `impl.http` (`OrderViewV1`, `OrderViewV2`) and map both from the same service
  output. The service doesn't know about versions.

## Authentication and authorization

- **Authentication** is middleware: it validates the token or session and
  produces a typed `AuthContext(userId: UserId, roles: Set[Role])`. Handlers
  never parse tokens.
- The handler passes the identity to the service as a typed parameter
  (`checkout(input, userId)`).
- **Authorization** ("only the owner may cancel") is a domain rule enforced in
  the `Live`, failing with a typed error:

```scala
override def cancelOrder(id: OrderId, userId: UserId): IO[OrderError, Unit] =
  for
    order <- findOrder(id)
    _     <- ZIO.cond(order.data.customerId.belongsTo(userId), (), OrderError.NotAuthorized(id))
    _     <- orderRepo.save(order.cancel)
  yield ()
```

## Pagination

```
GET /v1/orders?customerId=cust-1&offset=0&limit=20&sort=createdAt:desc
```

- Parse query parameters into a `Page(offset, limit)` value with a maximum
  `limit`; reject invalid values with 400.
- Return a `Paged[A](items, total, offset, limit)` shape from `commons`.
- Paging is implemented in the repository port (see
  [Persistence](310-persistence.md)), never by slicing a full list in memory.
