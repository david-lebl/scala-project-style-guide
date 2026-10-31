# Functional Principles

The plain-Scala rules that make code easy to reason about, before any effect
system is involved: referential transparency, totality, immutability,
expression-oriented style, and disciplined use of Scala 3 features. Effect
rules build on these in [Effect Fundamentals](200-effect-fundamentals.md).

## Dependencies

None.

---

## Referential transparency

A function is pure when it returns the same result for the same arguments and
does nothing else. Any call of a pure function can be replaced by its result
without changing the program's behavior. Domain logic in `impl` — parsing,
pricing, state transitions, DTO/domain mapping — MUST be pure. Side effects
(I/O, time, randomness, logging, mutation) are described as effect values and
run only at the edge of the program.

```scala
// Wrong — the result depends on hidden inputs and leaves traces:
def isOverdue(invoice: Invoice): Boolean =
  println(s"checking ${invoice.id}")
  invoice.dueDate.isBefore(LocalDate.now())

// Right — every input is a parameter, the output is the only effect:
def overdueStatus(invoice: Invoice, today: LocalDate): PaymentStatus =
  if invoice.dueDate.isBefore(today) then PaymentStatus.Overdue else PaymentStatus.OnTime
```

## Total functions

A function must return a valid result for every input its type allows. When
some inputs have no answer, change the type instead of throwing: narrow the
input (`NonEmptyChunk`, an opaque type) or widen the output (`Option`,
`Either`).

| Partial (forbidden in main code) | Total replacement |
|----------------------------------|-------------------|
| `opt.get`, `either.toOption.get` | `fold`, `getOrElse`, `match`, `toRight` |
| `list.head`, `.last`, `.max`, `.reduce` | `headOption`, `maxOption`, `reduceOption`, or `NonEmptyChunk` |
| `map(key)` | `map.get(key)` |
| `str.toInt` | `str.toIntOption` |
| `x.asInstanceOf[T]` | pattern match, type class |
| `Enum.valueOf(s)` | `Enum.values.find(_.toString == s)` |
| `null` | `Option` |
| `throw` / `require` / `assert` | `Either` / typed effect failure |

Partial accessors are acceptable in tests, where a crash *is* the failure
report.

## Immutability

- `val` only, `final case class` for data, immutable collections (`List`,
  `Vector`, `Map`, `Set`, `Chunk`). Never `scala.collection.mutable` in main
  code (mutable collections are fine inside test fakes).
- A class never holds a `var`. Shared mutable state in an effectful program
  lives in a `Ref` or behind a repository port (see [Concurrency and
  Streaming](230-concurrency-and-streaming.md)).
- A local `var` is tolerated only inside a function whose observable behavior is
  still pure (a tight numeric loop); prefer a fold.

Model state as an immutable case class and each transition as a pure
`State => State` function:

```scala
final case class Cart(items: Map[CatalogueNumber, Quantity]):
  def add(item: CatalogueNumber, qty: Quantity): Cart =
    copy(items = items.updatedWith(item)(old => Some(old.fold(qty)(_ + qty))))

def replay(events: List[CartEvent]): Cart =
  events.foldLeft(Cart(Map.empty))(applyEvent)
```

`applyEvent` can be tested alone, and no other fiber can observe intermediate
state.

## Expressions, not statements

- Every `if` has an `else`; every `match` is exhaustive; blocks end in a value.
- No `return`, no `while` with flags — use `fold`, `foldLeft`, recursion
  (`@tailrec`), or collection combinators.
- Don't discard values. With `-Wvalue-discard` and `-Wnonunit-statement` the
  compiler flags a dropped non-`Unit` value; fix the code rather than silencing
  the warning.

```scala
// Wrong — statement-oriented, mutable accumulator, early return:
def firstExpensive(items: List[LineItem]): LineItem =
  for item <- items do
    if item.unitPrice > limit then return item
  null

// Right:
def firstExpensive(items: List[LineItem]): Option[LineItem] =
  items.find(_.unitPrice > limit)
```

## Exhaustive pattern matching

Match on sealed types (enums, sealed traits) without a wildcard `case _` when
the cases carry meaning. A wildcard hides the compiler error that would point
you at every handler when a case is added. Compile with `-Wunused:all` and
treat "match may not be exhaustive" as an error (`-Xfatal-warnings`).

## Small functions, one concern

Each function does one logical operation, or is a short orchestration of named
steps. Name the steps; don't nest lambdas three levels deep.

```scala
def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
  for
    items   <- ZIO.fromEither(parseItems(input.items))
    address <- ZIO.fromEither(parseAddress(input.shippingAddress))
    order   <- createOrder(input.customerId, items, address)
    _       <- orderRepo.save(order)
  yield toView(order)
```

Prefer `Option`/`Either` combinators (`map`, `flatMap`, `fold`, `toRight`,
`orElse`, `traverse`-style `ZIO.foreach`) to nested `match` ladders. Use
`scala.util.chaining` `.pipe`/`.tap` to drop a single-use `val` that only feeds
the next line; keep the `val` when its name documents intent.

## Explicit types at boundaries

Every public or `private[<pkg>]` `def`, `val` and `given` declares its result
type. It documents the contract and stops a refactor from silently changing a
signature (e.g. widening `UIO` to `Task`). Local `val`s and private helpers may
rely on inference.

## Scala 3 feature discipline

- **Braceless syntax** everywhere. Use `end` markers only for long definitions.
- **Extension methods** on the type's companion or opaque type, never a
  `FooUtils` object of static helpers.
- **`given`/`using`** for type-class instances (codecs, `Ordering`, `CanEqual`)
  and nothing else. Services and configuration are constructor parameters wired
  by `ZLayer`, not implicits.
- **`derives`** for codecs and type classes where the library supports it.
- **No `Any`, no `AnyRef` parameters**, no structural types, no runtime
  reflection.
- **No implicit conversions.**
- Use `CanEqual` / `-language:strictEquality` when comparing opaque types is
  error-prone.
