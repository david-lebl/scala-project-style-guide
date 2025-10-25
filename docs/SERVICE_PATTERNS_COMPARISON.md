# Service Patterns: Service Pattern vs Use Case Pattern

This document compares two different architectural patterns for implementing worker management functionality in Scala with ZIO.

> **📦 Compilable Examples**: All code examples in this guide are available as compilable, executable Scala source files in [`src/examples/servicepatterns/`](../src/examples/servicepatterns/).
>
> Run the comparison examples with:
> ```bash
> scala-cli run ../ --main-class examples.servicepatterns.ServicePatternsComparison
> ```

## Overview

Both implementations provide the same core functionality:
- Worker registration and unregistration
- Heartbeat tracking and monitoring
- Worker state management (Pending, Active, Offline, Failed)
- Stale worker detection
- Worker querying and collection

## Files

- **Service Pattern**: `src/main/scala/complex/multi_hiarchical.scala`
- **Use Case Pattern**: `src/main/scala/complex/worker_usecase_pattern.scala`

---

## Architecture Comparison

### Service Pattern (`multi_hiarchical.scala`)

**Structure:**
```scala
trait WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]
  def unregister(id: Worker.Id): IO[WorkerError, Unit]
  def heartbeat(id: Worker.Id): IO[WorkerError, Unit]
  // ... all methods grouped in one service

final case class WorkerServiceLive(
  workerStore: WorkerStore,
  heartbeatStore: HeartbeatStore
) extends WorkerService:
  // Implementation of all methods
```

**Characteristics:**
- ✅ Methods grouped together in a cohesive service
- ✅ Single point of dependency injection
- ✅ Clear service boundaries
- ✅ Easy to mock entire service for testing
- ✅ Good for related operations that share state
- ❌ Can become large and unwieldy ("God Object")
- ❌ All dependencies required even if only using one method
- ❌ Less flexibility in composing operations

**Dependency Injection:**
```scala
WorkerService.layer: ZLayer[WorkerStore & HeartbeatStore, Nothing, WorkerService]
```

**Usage:**
```scala
for
  worker <- WorkerService.register("worker-1", Map("region" -> "us-east"))
  _ <- WorkerService.heartbeat(worker.id)
  workers <- WorkerService.getActiveWorkers
yield workers
```

---

### Use Case Pattern (`worker_usecase_pattern.scala`)

**Structure:**
```scala
object WorkerUseCases:
  // Each use case is a standalone function

  // Option 1: With explicit Input/Output case classes (more ceremony, better documentation)
  case class RegisterWorkerInput(id: String, config: Map[String, String])
  case class RegisterWorkerOutput(worker: WorkerUC, registeredAt: Instant)

  def registerWorker(input: RegisterWorkerInput): ZIO[WorkerDependencies, WorkerUseCaseError, RegisterWorkerOutput] =
    // Standalone implementation

  // Option 2: Direct parameters (less ceremony, like service pattern)
  def registerWorker(id: String, config: Map[String, String]): ZIO[WorkerDependencies, WorkerUseCaseError, Worker] =
    // Standalone implementation
```

**Characteristics:**
- ✅ Each use case is independent and focused (Single Responsibility)
- ✅ Can provide only needed dependencies per use case
- ✅ Easy to compose use cases together
- ✅ Clear business operations vs technical operations
- ✅ More testable in isolation
- ⚖️ Input/Output case classes are OPTIONAL
  - **With case classes**: More boilerplate, better documentation, easier to evolve
  - **Without case classes**: Less boilerplate, same as service pattern
- ❌ Can lead to code duplication if not careful
- ❌ Requires more up-front design

**Dependency Injection:**
```scala
// Per use case - only what's needed
def registerWorker(...): ZIO[WorkerRepository & HeartbeatRepository, ...]
def listWorkersByStatus(...): ZIO[WorkerRepository, ...]
```

**Usage:**
```scala
// With Input/Output case classes:
for
  result <- WorkerUseCases.registerWorker(RegisterWorkerInput("worker-1", Map("region" -> "us-east")))
  _ <- WorkerUseCases.recordHeartbeat(RecordHeartbeatInput(result.worker.id))
  workers <- WorkerUseCases.listWorkersByStatus(ListWorkersByStatusInput(WorkerUC.Status.Active))
yield workers

// Without Input/Output case classes (simpler):
for
  worker <- WorkerUseCases.registerWorker("worker-1", Map("region" -> "us-east"))
  _ <- WorkerUseCases.recordHeartbeat(worker.id)
  workers <- WorkerUseCases.listWorkersByStatus(WorkerUC.Status.Active)
yield workers
```

---

## Input/Output Case Classes: When to Use Them?

In the Use Case pattern, Input/Output case classes are **optional**. Here's when to use them:

### Use Input/Output Case Classes When:

✅ **Many parameters** (3+): Cleaner than long parameter lists
✅ **Evolution flexibility**: Easy to add fields without breaking existing code
✅ **Documentation**: Self-documenting what the use case requires/returns
✅ **Validation**: Can add validation logic in case class companion object
✅ **Serialization**: If use cases are called over network/API boundaries
✅ **Command pattern**: Following CQRS with explicit commands/queries
✅ **Testing**: Easier to create test fixtures

### Use Direct Parameters When:

✅ **Simple use cases**: 1-2 parameters
✅ **Less ceremony**: Faster to write, less boilerplate
✅ **Similar to service pattern**: Team is used to this style
✅ **Internal use only**: Not exposed via API/network
✅ **Quick prototyping**: Moving fast

### Example Comparison:

```scala
// With case classes (good for complex/evolving use cases)
case class CreateOrderInput(customerId: CustomerId, items: List[OrderItem], shippingAddress: Address, billingAddress: Address, notes: Option[String])
case class CreateOrderOutput(orderId: OrderId, totalAmount: Money, estimatedDelivery: LocalDate)

def createOrder(input: CreateOrderInput): ZIO[OrderDeps, OrderError, CreateOrderOutput]

// Direct parameters (good for simple use cases)
def cancelOrder(orderId: OrderId): ZIO[OrderDeps, OrderError, Unit]
def getOrder(orderId: OrderId): ZIO[OrderDeps, OrderError, Order]
```

**Recommendation**: Start with direct parameters. Add case classes when:
- Parameters exceed 3
- The use case becomes public API
- You need to evolve the signature frequently

---

## Detailed Comparison

| Aspect | Service Pattern | Use Case Pattern |
|--------|----------------|------------------|
| **Organization** | Methods in a trait/class | Standalone functions in object |
| **Dependencies** | Service-level (all dependencies) | Use-case-level (minimal dependencies) |
| **Input/Output** | Method parameters | Method parameters OR case classes (optional) |
| **State** | Shared across methods | Passed explicitly |
| **Composition** | Inheritance/Mixins | Function composition |
| **Testing** | Mock entire service | Test individual functions |
| **Discoverability** | All methods in one place | Grouped by domain concept |
| **Coupling** | Higher (all methods share deps) | Lower (minimal deps per use case) |
| **Boilerplate** | Less | Same (without case classes) or More (with case classes) |
| **Evolution** | Add methods to service | Add new use cases |
| **DDD Alignment** | Service-oriented | Use-case-oriented |
| **CQRS Fit** | Moderate | Excellent |

---

## Code Examples

### Example 1: Simple Operation

**Service Pattern:**
```scala
// Definition
trait WorkerService:
  def getWorker(id: Worker.Id): IO[WorkerError, Worker]

// Usage
workerService.getWorker(workerId)
```

**Use Case Pattern:**
```scala
// Definition
case class GetWorkerInput(id: WorkerUC.Id)
case class GetWorkerOutput(worker: WorkerUC, latestHeartbeat: Option[Instant])

def getWorker(input: GetWorkerInput): ZIO[WorkerDependencies, WorkerUseCaseError, GetWorkerOutput]

// Usage
WorkerUseCases.getWorker(GetWorkerInput(workerId))
```

### Example 2: Complex Operation with Multiple Steps

**Service Pattern:**
```scala
override def cleanupStaleWorkers(timeout: ZDuration): IO[WorkerError, List[Worker.Id]] =
  for
    staleWorkerIds <- heartbeatStore.findStaleWorkers(timeout)
    _ <- ZIO.foreach(staleWorkerIds) { workerId =>
      workerStore.get(workerId).flatMap { worker =>
        if worker.status == Worker.Status.Active || worker.status == Worker.Status.Pending then
          updateStatus(workerId, Worker.Status.Offline, None).unit
        else
          ZIO.unit
      }.catchAll(_ => ZIO.unit)
    }
  yield staleWorkerIds
```

**Use Case Pattern:**
```scala
case class DetectStaleWorkersInput(timeoutDuration: ZDuration)
case class DetectStaleWorkersOutput(staleWorkerIds: List[WorkerUC.Id], markedOffline: Int)

def detectStaleWorkers(input: DetectStaleWorkersInput): ZIO[WorkerDependencies, WorkerUseCaseError, DetectStaleWorkersOutput] =
  for
    workerRepo <- ZIO.service[WorkerRepository]
    heartbeatRepo <- ZIO.service[HeartbeatRepository]

    staleWorkerIds <- heartbeatRepo.findStaleWorkers(input.timeoutDuration)

    markedCount <- ZIO.foldLeft(staleWorkerIds)(0) { (count, workerId) =>
      workerRepo.findById(workerId).flatMap {
        case Some(worker) if worker.status == WorkerUC.Status.Active || worker.status == WorkerUC.Status.Pending =>
          val offlineWorker = worker.copy(status = WorkerUC.Status.Offline)
          workerRepo.save(offlineWorker).as(count + 1)
        case _ =>
          ZIO.succeed(count)
      }.catchAll(_ => ZIO.succeed(count))
    }
  yield DetectStaleWorkersOutput(staleWorkerIds, markedCount)
```

---

## Exposing via REST API: How Each Pattern Handles It

### Service Pattern with REST API

In the Service Pattern, you create **separate DTO (Data Transfer Object) case classes** at the **API/Controller layer**:

```scala
// Domain Layer - Service Pattern
trait WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]
  def getWorker(id: Worker.Id): IO[WorkerError, Worker]

// API Layer - DTOs
case class RegisterWorkerRequest(id: String, config: Map[String, String])
case class RegisterWorkerResponse(id: String, status: String, registeredAt: String)

case class GetWorkerResponse(id: String, status: String, config: Map[String, String])

// API Layer - HTTP Controller (using zio-http, http4s, or similar)
object WorkerController:
  def routes: Routes[WorkerService, Nothing] = Routes(
    Method.POST / "workers" -> handler { (req: Request) =>
      for
        body <- req.body.as[RegisterWorkerRequest]
        worker <- WorkerService.register(body.id, body.config)
        response = RegisterWorkerResponse(
          worker.id.value,
          worker.status.toString,
          worker.registeredAt.toString
        )
      yield Response.json(response.toJson)
    },

    Method.GET / "workers" / string("workerId") -> handler { (workerId: String, req: Request) =>
      for
        worker <- WorkerService.getWorker(Worker.Id(workerId))
        response = GetWorkerResponse(
          worker.id.value,
          worker.status.toString,
          worker.config
        )
      yield Response.json(response.toJson)
    }
  )
```

**Key Points:**
- DTOs are at the **API boundary** (Controller/Routes layer)
- Service methods use **domain types** directly
- **Separation of concerns**: API contracts vs domain logic
- DTOs handle serialization, validation, API versioning

---

### Use Case Pattern with REST API

The Use Case Pattern can work **two ways**:

#### Option 1: Input/Output Case Classes Double as DTOs

If your use case Input/Output case classes are designed for the API:

```scala
// Use Case Layer - Case classes serve as both use case contract AND DTOs
object WorkerUseCases:
  case class RegisterWorkerInput(id: String, config: Map[String, String]) derives JsonCodec
  case class RegisterWorkerOutput(id: String, status: String, registeredAt: String) derives JsonCodec

  def registerWorker(input: RegisterWorkerInput): ZIO[WorkerDeps, WorkerError, RegisterWorkerOutput] = ???

// API Layer - Directly use use case types
object WorkerController:
  def routes: Routes[WorkerDeps, Nothing] = Routes(
    Method.POST / "workers" -> handler { (req: Request) =>
      for
        input <- req.body.as[RegisterWorkerInput]  // Use case input = DTO
        output <- WorkerUseCases.registerWorker(input)
      yield Response.json(output.toJson)  // Use case output = DTO
    }
  )
```

**Pros:**
- Less code (no separate DTO layer)
- Use case contract IS the API contract

**Cons:**
- Tight coupling between use cases and API format
- Hard to version APIs independently
- Domain logic coupled to serialization concerns

---

#### Option 2: Separate DTOs + Use Case Types (Recommended)

Keep use cases pure, add DTOs at API layer:

```scala
// Domain Layer - Use Case Pattern with domain types
object WorkerUseCases:
  // Use case returns domain types
  def registerWorker(id: String, config: Map[String, String]): ZIO[WorkerDeps, WorkerError, Worker] = ???

// API Layer - Separate DTOs
case class RegisterWorkerRequest(id: String, config: Map[String, String]) derives JsonCodec
case class RegisterWorkerResponse(id: String, status: String, registeredAt: String) derives JsonCodec

// API Layer - Controller
object WorkerController:
  def routes: Routes[WorkerDeps, Nothing] = Routes(
    Method.POST / "workers" -> handler { (req: Request) =>
      for
        dto <- req.body.as[RegisterWorkerRequest]
        worker <- WorkerUseCases.registerWorker(dto.id, dto.config)  // Call use case
        response = RegisterWorkerResponse(  // Convert to DTO
          worker.id.value,
          worker.status.toString,
          worker.registeredAt.toString
        )
      yield Response.json(response.toJson)
    }
  )
```

**Pros:**
- Clean separation: API contracts vs use case logic
- Can version APIs independently
- Use cases remain pure, focused on domain logic

**Cons:**
- More code (mapping layer)

---

### Comparison: Service vs Use Case for REST APIs

| Aspect | Service Pattern | Use Case Pattern |
|--------|----------------|------------------|
| **DTO Location** | API/Controller layer (always separate) | API layer OR use case layer (flexible) |
| **Separation** | Always separated | Can choose to separate or not |
| **API Versioning** | Easy (DTOs independent of service) | Easy if DTOs separate, hard if reusing use case types |
| **Coupling** | Low (API independent of domain) | Low (if DTOs separate) or High (if reusing use case types) |
| **Boilerplate** | Moderate (DTOs + mapping) | Low (if reusing) or Moderate (if separate DTOs) |
| **Best Practice** | Use separate DTOs | Use separate DTOs (same as service pattern) |

---

### Recommended Approach for Both Patterns

**Best Practice**: Always use **separate DTO layer** for REST APIs, regardless of pattern:

```scala
// ✅ Good: Separate concerns
// API Layer
case class WorkerApiRequest(...)  derives JsonCodec
case class WorkerApiResponse(...) derives JsonCodec

// Domain Layer (Service or Use Case)
trait WorkerService: // or object WorkerUseCases:
  def operation(...): ZIO[Deps, Error, DomainType]

// Controller maps DTOs <-> Domain
```

**Why?**
- API contracts evolve differently from domain logic
- Multiple API versions can coexist (v1, v2)
- Domain types don't need JSON/serialization concerns
- Easier to test domain logic without API concerns

---

## When to Use Each Pattern

### Use Service Pattern When:

1. **Related Operations**: Operations are highly related and share significant state
2. **Simple Domain**: Domain is straightforward without complex business rules
3. **CRUD Focus**: Mostly CRUD operations without complex workflows
4. **Team Preference**: Team prefers OOP-style organization
5. **Quick Development**: Need to move fast with less ceremony
6. **Small Scope**: Service won't grow too large

**Example Scenarios:**
- Simple REST API endpoints
- Database access layers
- Infrastructure services (logging, metrics)
- Utility/helper services

### Use Use Case Pattern When:

1. **Complex Business Logic**: Each operation has distinct business rules
2. **Domain-Driven Design**: Following DDD principles
3. **CQRS/Event Sourcing**: Commands and queries are separated
4. **Explicit Contracts**: Need clear input/output contracts
5. **Independent Operations**: Use cases can be tested/deployed independently
6. **Large Scale**: System will grow significantly
7. **Documentation**: Self-documenting code is important

**Example Scenarios:**
- E-commerce order processing
- Financial transactions
- Healthcare systems
- Complex workflow engines
- Microservices with clear bounded contexts

---

## Hybrid Approach

You can combine both patterns:

```scala
// Use cases for complex business operations
object OrderUseCases:
  def processPayment(input: ProcessPaymentInput): ZIO[...]
  def cancelOrder(input: CancelOrderInput): ZIO[...]

// Services for infrastructure/technical concerns
trait OrderRepository:
  def save(order: Order): IO[...]
  def findById(id: OrderId): IO[...]

// Or: Use cases can call services
object ComplexUseCases:
  def complexWorkflow(input: Input): ZIO[OrderService & PaymentService, Error, Output] =
    for
      order <- OrderService.createOrder(...)
      payment <- PaymentService.processPayment(...)
    yield Output(order, payment)
```

---

## Migration Path

### From Service to Use Case:
1. Extract each service method to a use case function
2. Add explicit input/output case classes
3. Make dependencies explicit per use case
4. Group related use cases in objects

### From Use Case to Service:
1. Group related use cases
2. Create service trait with method signatures
3. Combine dependencies at service level
4. Simplify input/output to method parameters

---

## Performance Considerations

Both patterns have similar runtime performance. The main differences:

- **Service Pattern**: Slightly less object allocation (no input/output classes)
- **Use Case Pattern**: More explicit, may enable better optimization by compiler

In practice, the performance difference is negligible compared to I/O operations.

---

## Conclusion

- **Service Pattern** is simpler and faster to implement for straightforward scenarios
- **Use Case Pattern** provides better organization and scalability for complex domains
- Choose based on:
  - Domain complexity
  - Team expertise
  - Long-term maintainability needs
  - Testing requirements
  - CQRS/DDD alignment

Both patterns are valid and well-supported by ZIO's effect system!

---

## Working Example Code

All the patterns demonstrated in this guide are implemented as **compilable, executable Scala code** in the [`src/examples/servicepatterns/`](../src/examples/servicepatterns/) directory.

### Source Files

**Service Pattern:**
- [`WorkerServicePattern.scala`](../src/examples/servicepatterns/servicepattern/WorkerServicePattern.scala) - Complete Service Pattern implementation
  - Trait-based service with all methods grouped together
  - Single dependency injection point
  - Live implementation with shared dependencies

**Use Case Pattern (without DTOs):**
- [`WorkerUseCases.scala`](../src/examples/servicepatterns/usecasepattern/WorkerUseCases.scala) - Use Case Pattern with direct parameters
  - Standalone functions per use case
  - Minimal dependencies per function
  - No Input/Output case classes (simpler)

**Use Case Pattern (with DTOs):**
- [`WorkerUseCasesWithDTO.scala`](../src/examples/servicepatterns/usecasepattern/WorkerUseCasesWithDTO.scala) - Use Case Pattern with Input/Output DTOs
  - Explicit Input/Output case classes
  - Self-documenting contracts
  - Good for CQRS and API boundaries

**Comparison Examples:**
- [`ServicePatternsComparison.scala`](../src/examples/servicepatterns/ServicePatternsComparison.scala) - Side-by-side comparison demonstrating:
  - Service Pattern usage
  - Use Case Pattern usage (without DTOs)
  - Use Case Pattern usage (with DTOs)
  - Dependency comparison
  - Complex operations in both patterns
  - Error handling in both patterns

### Running the Examples

Compile all examples:
```bash
scala-cli compile ../
```

Run the comparison examples:
```bash
scala-cli run ../ --main-class examples.servicepatterns.ServicePatternsComparison
```

Expected output:
```
╔══════════════════════════════════════════════════╗
║  Service Patterns Comparison                     ║
║  From: SERVICE_PATTERNS_COMPARISON.md            ║
╚══════════════════════════════════════════════════╝

=== Example 1: Service Pattern ===
✓ Service Pattern: Registered worker-1, status: Pending
✓ Service Pattern: Heartbeat recorded
✓ Service Pattern: Active workers: 1

=== Example 2: Use Case Pattern (no DTOs) ===
✓ Use Case Pattern: Registered worker-2, status: Pending
✓ Use Case Pattern: Heartbeat recorded
✓ Use Case Pattern: Active workers: 2

=== Example 3: Use Case Pattern (with DTOs) ===
✓ Use Case with DTO: Registered worker-3, status: Pending
✓ Use Case with DTO: Heartbeat recorded at 2025-..., status: Active
✓ Use Case with DTO: Active workers: 3

=== Example 4: Dependency Comparison ===
Service Pattern: Requires ALL dependencies at service level
  - WorkerRepository & HeartbeatRepository
  - Even if you only call one method

Use Case Pattern: Requires only needed dependencies
  - registerWorker: only WorkerRepository
  - recordHeartbeat: WorkerRepository & HeartbeatRepository
  - getActiveWorkers: only WorkerRepository

=== Example 5: Complex Operation (Stale Workers) ===
✓ Service Pattern: Found 0 stale workers
✓ Use Case Pattern: Found 0 stale workers

=== Example 6: Error Handling ===
✓ Service Pattern error caught: Worker with ID 'duplicate' already exists
✓ Use Case Pattern error caught: Worker with ID 'duplicate2' already exists

✓ All comparison examples completed!
```

### Key Differences Demonstrated

1. **Service Pattern** ([WorkerServicePattern.scala](../src/examples/servicepatterns/servicepattern/WorkerServicePattern.scala))
   - All methods in `trait WorkerService`
   - Dependencies injected at service level (lines 53-55)
   - Traditional OOP-style organization

2. **Use Case Pattern (Simple)** ([WorkerUseCases.scala](../src/examples/servicepatterns/usecasepattern/WorkerUseCases.scala))
   - Functions in `object WorkerUseCases`
   - Dependencies per function (minimal requirements)
   - Direct parameters, no DTOs

3. **Use Case Pattern (with DTOs)** ([WorkerUseCasesWithDTO.scala](../src/examples/servicepatterns/usecasepattern/WorkerUseCasesWithDTO.scala))
   - Input/Output case classes for each use case
   - Self-documenting contracts
   - Better for API boundaries and CQRS

4. **Comparison Examples** ([ServicePatternsComparison.scala](../src/examples/servicepatterns/ServicePatternsComparison.scala))
   - Shows all three patterns in action
   - Demonstrates dependency differences
   - Shows error handling in both patterns

---

This working code serves as a reference implementation that you can copy, modify, and adapt for your own projects!