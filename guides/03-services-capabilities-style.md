# Caoabilities servicce pattern

## Service definition

```scala
trait MyService:
  def doSomething(input: String): IO[MyError, String]

object MyService:
    def doSomething(input: String)(using MyService): IO[MyError, String]
```

## Live implementation

```scala
final case class MyServiceLive(using
  dep1: Dep1,
  dep2: Dep2
) extends MyService:
  def doSomething(input: String): IO[MyError, String] = 
    for
      res1 <- Dep1.dep1Method(...)
      res2 <- Dep2.dep2Method(...)
    yield ...
```

## GAtwate / Output ports

```scala
private trait Dep1:
    def dep1Method(...): IO[Dep1Error, Dep1Result]

object Dep1Live:
    // accessors for Dep1 methods, e.g.
    def dep1Method(...)(using Dep1): IO[Dep1Error, Dep1Result] =
      summon[Dep1].dep1Method(...)

private trait Dep2:
    def dep2Method(...): IO[Dep2Error, Dep2Result]

object Dep2Live:
    // accessors for Dep2 methods, e.g.
    def dep2Method(...)(using Dep2): IO[Dep2Error, Dep2Result] =
      summon[Dep2].dep2Method(...)
```