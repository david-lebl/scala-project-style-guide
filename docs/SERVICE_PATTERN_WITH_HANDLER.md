# Service / use case pattern with handler

```scala

object WorkerService:

  type WorkerId = String

  enum Output: // or Event
    case WorkerRegistered()
    case WorkerUnregistered()

  enum Input[+Output]: // or Command (persistable)
    case RegisterWorker(id: WorkerId extends Input[WorkerRegistered]
    case UnregisterWorker(id: WorkerId) extends Input[WorkerUnregistered]

  enum Error:
    case WorkerNotFound(id: WorkerId)
    case WorkerAlreadyRegistered(id: WorkerId)

  trait Service:
    def registerWorker(id: WorkerId): IO[Error, WorkerRegistered]
    def unregisterWorker(id: WorkerId): IO[Error, WorkerUnregistered]

  trait Handler[-Command[_]]:
    def handle[Event](input: Input[Event]): IO[Error, Event]

  // implementation - service pattern

  trait Dependencies
  final class LiveService(dep: Dependencies) extends Service with Handler[Input[Output]]:
    def handle[Output](input: Input[Output]): IO[Error, Output] = 
      imput match
        case RegisterWorker(id) => registerWorker(id)
        case UnregisterWorker(id) => unregisterWorker(id)

    def registerWorker(id: WorkerId): IO[Error, WorkerRegistered] = ???
    def unregisterWorker(id: WorkerId): IO[Error, WorkerUnregistered] = ???

  // implementation - use case pattern v1
  object UseCase:
    def registerWorker(id: WorkerId): ZIO[Dependencies, Error, WorkerRegistered] = ???
    def unregisterWorker(id: WorkerId): ZIO[Dependencies, Error, WorkerUnregistered] = ???
  
```