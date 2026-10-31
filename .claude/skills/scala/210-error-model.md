# Error Model

How errors are defined, propagated, translated and reported: one error enum per
bounded context, the typed channel for domain errors, the defect channel for
infrastructure failures. Read this before writing any code that can fail.

## Dependencies

- `"dev.zio" %% "zio"` — typed error channel, `Cause`, `Exit`

---

## Principles

1. **Domain errors are values, not exceptions.** "Item out of stock" is an
   expected outcome. It travels in the **typed error channel** `E`.
2. **Infrastructure failures are defects.** A dead connection or a corrupt row
   is not a domain concern. It travels in the **defect channel** and is logged
   and reported, never pattern-matched by domain code.
3. **Each bounded context owns its error enum.** There is no global `AppError`.
   Errors are translated at context boundaries, like data.
4. **Errors carry structure.** A stable code, a human-readable message, and the
   fields needed to diagnose — never a bare `String`.

The decision rule: if the caller can do something meaningful (show a message,
try another path, retry with different input), it's a **typed error**. If all
it can do is give up and report, it's a **defect**.

## One error enum per context

The enum lives in the context's **public package** and is part of the
contract. The nested `Code` enum holds the stable identifier and an HTTP hint:

```scala
package com.myco.ordering

enum OrderError(val code: OrderError.Code, val details: String):
  def message: String = s"[${code.value}] $details"

  case EmptyItemList
    extends OrderError(OrderError.Code.EmptyItemList, "Cannot create an order with no items")

  case InsufficientStock(catalogueNumber: String, requested: Int, available: Int)
    extends OrderError(
      OrderError.Code.InsufficientStock,
      s"Item '$catalogueNumber': requested $requested but only $available available"
    )

  case OrderNotFound(orderId: OrderId)
    extends OrderError(OrderError.Code.OrderNotFound, s"Order '${orderId.value}' not found")

  case OrderAlreadyCancelled(orderId: OrderId)
    extends OrderError(OrderError.Code.OrderAlreadyCancelled, s"Order '${orderId.value}' is already cancelled")

object OrderError:
  enum Code(val value: String, val httpStatus: Int):
    case EmptyItemList         extends Code("ORD-001", 422)
    case InsufficientStock     extends Code("ORD-003", 409)
    case OrderNotFound         extends Code("ORD-005", 404)
    case OrderAlreadyCancelled extends Code("ORD-006", 409)
```

> **Required:** reference codes as `OrderError.Code.X` in the cases' `extends`
> clauses. The unqualified `Code.X` fails with "illegal reference to object
> Code from enum case".

Code conventions:

- Prefix with a short context abbreviation: `ORD-`, `SHP-`, `USR-`.
- Number sequentially; gaps are fine, never reuse a retired number.
- `httpStatus` is a hint for the HTTP layer, which may override it.

Adding a case makes every non-exhaustive `match` fail to compile; API
consumers key on `"ORD-003"`, not on message wording.

## Typed channel vs defect channel

| Channel | Type | Contains | Handled by |
|---------|------|----------|------------|
| typed | `E` in `IO[E, A]` | the context's error enum | `catchSome`, `mapError`, `fold`, pattern match |
| defect | `Throwable` inside `Cause.Die` | infrastructure failures, bugs | logged at the top, 500 at HTTP |

- Service methods return `IO[<Context>Error, A]`, or `UIO[A]` when there is no
  domain failure.
- Ports (repositories, gateways, anti-corruption ports) return `UIO[A]` or
  `IO[<Context>Error, A]` — **never `Task`**. `Throwable` never appears in a
  port signature.
- Adapters convert third-party `Task`s at their own boundary with `.orDie`,
  `.refineOrDie`, or `catchSome` + `.orDie`.

```scala
// Wrong — domain error as an exception:
def checkout(input: CheckoutInput): Task[OrderView] =
  if input.items.isEmpty then ZIO.fail(IllegalArgumentException("empty items")) else ???

// Wrong — infrastructure leaking into the typed channel:
def checkout(input: CheckoutInput): IO[OrderError | SQLException, OrderView] = ???

// Right:
def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
  for
    _ <- ZIO.fail(OrderError.EmptyItemList).when(input.items.isEmpty)
    _ <- orderRepo.save(order)    // UIO — the adapter already called .orDie
  yield view
```

> **Important:** `.orDie` belongs in the adapter, not in the `Live` service. If
> a `Live` needs `.orDie`, the port it calls has the wrong signature.

## Wrapping third-party errors in adapters

```scala
// No domain-meaningful failure → everything is a defect:
override def save(order: Order): UIO[Unit] =
  ZIO.attemptBlocking(insert(OrderDAO.fromDomain(order))).orDie

// One exception means something to the domain → map it, the rest dies:
override def save(order: Order): IO[OrderError, Unit] =
  ZIO.attemptBlocking(insert(OrderDAO.fromDomain(order)))
    .refineOrDie:
      case _: DuplicateKeyException => OrderError.OrderAlreadyExists(order.id)
```

Catch the most specific exception type. Never catch `Throwable` or `Exception`
into a domain error — it turns bugs into "expected" outcomes.

## Composing errors inside a service

**Short-circuit** with a for-comprehension — the first failure stops the
pipeline:

```scala
for
  items   <- ZIO.fromEither(parseItems(input.items))      // EmptyItemList
  address <- ZIO.fromEither(parseAddress(input.address))  // InvalidAddress
  _       <- checkStock(items)                            // InsufficientStock
yield ...
```

**Accumulate** when the caller wants every problem at once (form validation):

```scala
def validateCheckout(input: CheckoutInput): IO[::[OrderError], ValidatedCheckout] =
  ZIO.validatePar(input.items)(validateItem).map(ValidatedCheckout(_))
```

**Not found:** repositories return `UIO[Option[A]]`; the service turns `None`
into its typed error:

```scala
orderRepo.findById(Order.Id(id.value))
  .someOrFail(OrderError.OrderNotFound(id))
```

**Guards:**

```scala
_ <- ZIO.cond(items.size <= config.maxItemsPerOrder, (), OrderError.TooManyItems(items.size))
_ <- ZIO.fail(OrderError.OrderAlreadyCancelled(id)).when(order.data.status == OrderStatus.Cancelled)
```

**Enriching:** use `.mapError` to add context or change the error type between
layers; never swallow information.

## Translating across contexts

A consuming context never exposes another context's error type. Its
anti-corruption adapter translates each foreign error to one of:

- `None` — for "not found" the consumer expects;
- the consumer's own error case — when it maps to one;
- a defect via `.orDie` — for everything else.

See [Bounded Contexts](320-bounded-contexts.md#error-translation).

## Handling: never swallow defects

- `catchAll` / `catchSome` / `mapError` act only on the typed channel. That is
  what you want in domain and route code.
- `catchAllCause`, `catchAllDefect`, `sandbox` see defects too. Use them only
  at the outermost boundary (HTTP middleware, a background loop iteration, the
  `app` entry point) to log and convert to a generic response.
- `.ignore` and `.either` on an effect with real failures hide bugs; only use
  them when the failure is genuinely irrelevant, and say why in a comment.

## Logging errors

Log domain errors once, at the boundary that handles them (route handler,
event consumer) — never inside domain logic, never at each layer:

```scala
.catchAll: (error: OrderError) =>
  ZIO.logWarning(error.message) *> ZIO.succeed(errorResponse(error))
```

Log defects once, at the top, with the full cause:

```scala
.tapErrorCause(cause => ZIO.logErrorCause("Unhandled failure", cause))
```

## Anti-patterns

| Anti-pattern | Instead |
|--------------|---------|
| `throw` / `require` / `assert` in domain code | `Either` or `ZIO.fail(DomainError.Case)` |
| `Task[A]` / `IO[Throwable, A]` service methods | `IO[<Context>Error, A]` |
| `IO[String, A]` | an error enum with codes |
| global `AppError` | one enum per context |
| `.orDie` in the `Live` service | `.orDie` in the adapter; port returns `UIO` |
| `catchAllCause` in domain code | typed `catchSome`; defects go to the top |
| foreign context's error type in your signature | translate in the adapter |
| logging inside domain logic | log at the boundary |
