# Service Pattern with Command/Event Handler

This document demonstrates how to design services using **Commands and Events** with a **Handler pattern** in Scala with ZIO. This approach combines the benefits of the Service and Use Case patterns with CQRS (Command Query Responsibility Segregation) principles.

## Table of Contents

- [Overview](#overview)
- [Core Concepts](#core-concepts)
- [Pattern Variations](#pattern-variations)
- [Complete Examples](#complete-examples)
- [Handler Pattern Benefits](#handler-pattern-benefits)
- [Best Practices](#best-practices)
- [When to Use This Pattern](#when-to-use-this-pattern)

---

## Overview

The **Handler Pattern** adds an abstraction layer over service methods by:
1. **Commands** (Input): Represent operations to perform (persistable, serializable)
2. **Events** (Output): Represent what happened as a result (domain events)
3. **Handler**: Processes commands and produces events

This pattern is especially useful for:
- Event sourcing and CQRS systems
- Audit logging and command replay
- Decoupling command execution from business logic
- Testing and mocking at the command level

---

## Core Concepts

### Commands vs Direct Method Calls

**Traditional Service Pattern:**
```scala
trait WorkerService:
  def registerWorker(id: WorkerId): IO[Error, Worker]
  def unregisterWorker(id: WorkerId): IO[Error, Unit]
```

**Handler Pattern with Commands:**
```scala
enum WorkerCommand[+E]:
  case RegisterWorker(id: WorkerId) extends WorkerCommand[WorkerRegistered]
  case UnregisterWorker(id: WorkerId) extends WorkerCommand[WorkerUnregistered]

trait CommandHandler:
  def handle[E](command: WorkerCommand[E]): IO[Error, E]
```

### Events: Capturing What Happened

Events represent **facts** about what occurred in the system:

```scala
enum WorkerEvent:
  case WorkerRegistered(id: WorkerId, registeredAt: Instant)
  case WorkerUnregistered(id: WorkerId, reason: String, unregisteredAt: Instant)
  case HeartbeatReceived(id: WorkerId, receivedAt: Instant)
  case WorkerFailed(id: WorkerId, error: String, failedAt: Instant)
```

**Key Properties:**
- Events are **immutable facts** (past tense naming)
- Events can be **persisted** to an event store
- Events can trigger **downstream reactions** (sagas, notifications)
- Events provide an **audit trail**

---

## Pattern Variations

### Variation 1: Service Pattern with Handler

Combines traditional service methods with a handler interface.

```scala
object WorkerService:
  type WorkerId = String

  // Events (Output)
  enum Event:
    case WorkerRegistered(id: WorkerId, registeredAt: Instant)
    case WorkerUnregistered(id: WorkerId, unregisteredAt: Instant)
    case HeartbeatRecorded(id: WorkerId, recordedAt: Instant)

  // Commands (Input) - GADT (Generalized Algebraic Data Type)
  enum Command[+E]:
    case RegisterWorker(id: WorkerId, config: Map[String, String]) extends Command[Event.WorkerRegistered]
    case UnregisterWorker(id: WorkerId) extends Command[Event.WorkerUnregistered]
    case RecordHeartbeat(id: WorkerId) extends Command[Event.HeartbeatRecorded]

  // Errors
  enum Error:
    case WorkerNotFound(id: WorkerId)
    case WorkerAlreadyRegistered(id: WorkerId)
    case InvalidConfiguration(message: String)

  // Traditional Service Trait
  trait Service:
    def registerWorker(id: WorkerId, config: Map[String, String]): IO[Error, Event.WorkerRegistered]
    def unregisterWorker(id: WorkerId): IO[Error, Event.WorkerUnregistered]
    def recordHeartbeat(id: WorkerId): IO[Error, Event.HeartbeatRecorded]

  // Handler Trait
  trait Handler:
    def handle[E](command: Command[E]): IO[Error, E]

  // Dependencies
  trait Dependencies:
    def workerStore: WorkerStore
    def heartbeatStore: HeartbeatStore
    def eventPublisher: EventPublisher

  // Implementation - combines both Service and Handler
  final class Live(deps: Dependencies) extends Service with Handler:

    // Handler implementation - delegates to service methods
    def handle[E](command: Command[E]): IO[Error, E] =
      command match
        case Command.RegisterWorker(id, config) => registerWorker(id, config)
        case Command.UnregisterWorker(id) => unregisterWorker(id)
        case Command.RecordHeartbeat(id) => recordHeartbeat(id)

    // Service method implementations
    def registerWorker(id: WorkerId, config: Map[String, String]): IO[Error, Event.WorkerRegistered] =
      for
        _ <- deps.workerStore.exists(id).flatMap {
          case true => ZIO.fail(Error.WorkerAlreadyRegistered(id))
          case false => ZIO.unit
        }
        now <- Clock.instant
        worker = Worker(id, config, Worker.Status.Pending, now)
        _ <- deps.workerStore.save(worker)
        event = Event.WorkerRegistered(id, now)
        _ <- deps.eventPublisher.publish(event)
      yield event

    def unregisterWorker(id: WorkerId): IO[Error, Event.WorkerUnregistered] =
      for
        worker <- deps.workerStore.get(id).someOrFail(Error.WorkerNotFound(id))
        _ <- deps.workerStore.delete(id)
        now <- Clock.instant
        event = Event.WorkerUnregistered(id, now)
        _ <- deps.eventPublisher.publish(event)
      yield event

    def recordHeartbeat(id: WorkerId): IO[Error, Event.HeartbeatRecorded] =
      for
        _ <- deps.workerStore.get(id).someOrFail(Error.WorkerNotFound(id))
        now <- Clock.instant
        _ <- deps.heartbeatStore.record(id, now)
        event = Event.HeartbeatRecorded(id, now)
        _ <- deps.eventPublisher.publish(event)
      yield event

  // Layer construction
  val layer: ZLayer[Dependencies, Nothing, Service & Handler] =
    ZLayer.fromFunction(Live.apply)
```

**Usage:**
```scala
// As a Service
val result = WorkerService.registerWorker("worker-1", Map("region" -> "us-east"))

// As a Handler
val command = WorkerService.Command.RegisterWorker("worker-1", Map("region" -> "us-east"))
val result = WorkerService.handle(command)
```

---

### Variation 2: Use Case Pattern with Handler

Each use case is a standalone function, with a separate handler for command routing.

```scala
object WorkerUseCases:
  type WorkerId = String

  // Events
  enum Event:
    case WorkerRegistered(id: WorkerId, registeredAt: Instant)
    case WorkerUnregistered(id: WorkerId, unregisteredAt: Instant)
    case HeartbeatRecorded(id: WorkerId, recordedAt: Instant)
    case StaleWorkersDetected(workerIds: List[WorkerId], detectedAt: Instant)

  // Commands (GADT)
  enum Command[+E]:
    case RegisterWorker(id: WorkerId, config: Map[String, String]) extends Command[Event.WorkerRegistered]
    case UnregisterWorker(id: WorkerId) extends Command[Event.WorkerUnregistered]
    case RecordHeartbeat(id: WorkerId) extends Command[Event.HeartbeatRecorded]
    case DetectStaleWorkers(timeout: Duration) extends Command[Event.StaleWorkersDetected]

  // Errors
  enum Error:
    case WorkerNotFound(id: WorkerId)
    case WorkerAlreadyRegistered(id: WorkerId)
    case InvalidConfiguration(message: String)

  // Dependencies
  trait Dependencies:
    def workerRepository: WorkerRepository
    def heartbeatRepository: HeartbeatRepository
    def eventPublisher: EventPublisher

  // Use Case Functions (standalone)
  def registerWorker(id: WorkerId, config: Map[String, String]): ZIO[Dependencies, Error, Event.WorkerRegistered] =
    for
      deps <- ZIO.service[Dependencies]
      exists <- deps.workerRepository.exists(id)
      _ <- ZIO.when(exists)(ZIO.fail(Error.WorkerAlreadyRegistered(id)))
      now <- Clock.instant
      worker = Worker(id, config, Worker.Status.Pending, now)
      _ <- deps.workerRepository.save(worker)
      event = Event.WorkerRegistered(id, now)
      _ <- deps.eventPublisher.publish(event)
    yield event

  def unregisterWorker(id: WorkerId): ZIO[Dependencies, Error, Event.WorkerUnregistered] =
    for
      deps <- ZIO.service[Dependencies]
      worker <- deps.workerRepository.findById(id).someOrFail(Error.WorkerNotFound(id))
      _ <- deps.workerRepository.delete(id)
      now <- Clock.instant
      event = Event.WorkerUnregistered(id, now)
      _ <- deps.eventPublisher.publish(event)
    yield event

  def recordHeartbeat(id: WorkerId): ZIO[Dependencies, Error, Event.HeartbeatRecorded] =
    for
      deps <- ZIO.service[Dependencies]
      _ <- deps.workerRepository.findById(id).someOrFail(Error.WorkerNotFound(id))
      now <- Clock.instant
      _ <- deps.heartbeatRepository.record(id, now)
      event = Event.HeartbeatRecorded(id, now)
      _ <- deps.eventPublisher.publish(event)
    yield event

  def detectStaleWorkers(timeout: Duration): ZIO[Dependencies, Error, Event.StaleWorkersDetected] =
    for
      deps <- ZIO.service[Dependencies]
      staleIds <- deps.heartbeatRepository.findStaleWorkers(timeout)
      now <- Clock.instant
      event = Event.StaleWorkersDetected(staleIds, now)
      _ <- deps.eventPublisher.publish(event)
    yield event

  // Handler for Commands
  object Handler:
    def handle[E](command: Command[E]): ZIO[Dependencies, Error, E] =
      command match
        case Command.RegisterWorker(id, config) => registerWorker(id, config)
        case Command.UnregisterWorker(id) => unregisterWorker(id)
        case Command.RecordHeartbeat(id) => recordHeartbeat(id)
        case Command.DetectStaleWorkers(timeout) => detectStaleWorkers(timeout)
```

**Usage:**
```scala
// Direct use case call
val result = WorkerUseCases.registerWorker("worker-1", Map("region" -> "us-east"))

// Via handler
val command = WorkerUseCases.Command.RegisterWorker("worker-1", Map("region" -> "us-east"))
val result = WorkerUseCases.Handler.handle(command)
```

---

### Variation 3: Pure Handler Pattern (No Service Trait)

Handler is the primary interface, business logic is in private methods or objects.

```scala
object WorkerCommandHandler:
  type WorkerId = String

  // Events
  enum Event:
    case WorkerRegistered(id: WorkerId, registeredAt: Instant)
    case WorkerUnregistered(id: WorkerId, unregisteredAt: Instant)

  // Commands (GADT)
  enum Command[+E]:
    case RegisterWorker(id: WorkerId, config: Map[String, String]) extends Command[Event.WorkerRegistered]
    case UnregisterWorker(id: WorkerId) extends Command[Event.WorkerUnregistered]

  // Errors
  enum Error:
    case WorkerNotFound(id: WorkerId)
    case WorkerAlreadyRegistered(id: WorkerId)

  // Handler Trait
  trait Handler:
    def execute[E](command: Command[E]): IO[Error, E]

  // Dependencies
  trait Dependencies:
    def workerRepository: WorkerRepository
    def eventStore: EventStore

  // Implementation
  final class Live(deps: Dependencies) extends Handler:

    def execute[E](command: Command[E]): IO[Error, E] =
      command match
        case Command.RegisterWorker(id, config) =>
          handleRegisterWorker(id, config)
        case Command.UnregisterWorker(id) =>
          handleUnregisterWorker(id)

    // Private handlers
    private def handleRegisterWorker(id: WorkerId, config: Map[String, String]): IO[Error, Event.WorkerRegistered] =
      for
        exists <- deps.workerRepository.exists(id)
        _ <- ZIO.when(exists)(ZIO.fail(Error.WorkerAlreadyRegistered(id)))
        now <- Clock.instant
        event = Event.WorkerRegistered(id, now)
        _ <- deps.eventStore.append(event)
        _ <- deps.workerRepository.save(Worker(id, config, Worker.Status.Active, now))
      yield event

    private def handleUnregisterWorker(id: WorkerId): IO[Error, Event.WorkerUnregistered] =
      for
        _ <- deps.workerRepository.get(id).someOrFail(Error.WorkerNotFound(id))
        now <- Clock.instant
        event = Event.WorkerUnregistered(id, now)
        _ <- deps.eventStore.append(event)
        _ <- deps.workerRepository.delete(id)
      yield event

  val layer: ZLayer[Dependencies, Nothing, Handler] =
    ZLayer.fromFunction(Live.apply)
```

**Usage:**
```scala
val command = WorkerCommandHandler.Command.RegisterWorker("worker-1", Map("region" -> "us-east"))
val result = WorkerCommandHandler.execute(command)
```

---

## Complete Examples

### Example 1: Order Processing with Commands and Events

```scala
object OrderService:

  opaque type OrderId = String
  object OrderId:
    def apply(value: String): OrderId = value
    extension (id: OrderId) def value: String = id

  opaque type CustomerId = String
  object CustomerId:
    def apply(value: String): CustomerId = value

  // Domain Model
  case class Order(
    id: OrderId,
    customerId: CustomerId,
    items: List[OrderItem],
    status: OrderStatus,
    totalAmount: BigDecimal,
    createdAt: Instant
  )

  enum OrderStatus:
    case Pending, Confirmed, Shipped, Delivered, Cancelled

  case class OrderItem(productId: String, quantity: Int, price: BigDecimal)

  // Events
  enum Event:
    case OrderCreated(orderId: OrderId, customerId: CustomerId, totalAmount: BigDecimal, createdAt: Instant)
    case OrderConfirmed(orderId: OrderId, confirmedAt: Instant)
    case OrderShipped(orderId: OrderId, trackingNumber: String, shippedAt: Instant)
    case OrderDelivered(orderId: OrderId, deliveredAt: Instant)
    case OrderCancelled(orderId: OrderId, reason: String, cancelledAt: Instant)
    case PaymentProcessed(orderId: OrderId, amount: BigDecimal, processedAt: Instant)

  // Commands (GADT)
  enum Command[+E]:
    case CreateOrder(customerId: CustomerId, items: List[OrderItem]) extends Command[Event.OrderCreated]
    case ConfirmOrder(orderId: OrderId) extends Command[Event.OrderConfirmed]
    case ShipOrder(orderId: OrderId, trackingNumber: String) extends Command[Event.OrderShipped]
    case DeliverOrder(orderId: OrderId) extends Command[Event.OrderDelivered]
    case CancelOrder(orderId: OrderId, reason: String) extends Command[Event.OrderCancelled]
    case ProcessPayment(orderId: OrderId, amount: BigDecimal) extends Command[Event.PaymentProcessed]

  // Errors
  enum Error:
    case OrderNotFound(orderId: OrderId)
    case InvalidOrderStatus(orderId: OrderId, expected: OrderStatus, actual: OrderStatus)
    case PaymentFailed(orderId: OrderId, reason: String)
    case InsufficientInventory(productId: String, requested: Int, available: Int)
    case InvalidOrderData(message: String)

  // Dependencies
  trait Dependencies:
    def orderRepository: OrderRepository
    def paymentService: PaymentService
    def inventoryService: InventoryService
    def eventStore: EventStore

  // Handler Trait
  trait Handler:
    def handle[E](command: Command[E]): IO[Error, E]

  // Service Trait (optional - for direct method access)
  trait Service:
    def createOrder(customerId: CustomerId, items: List[OrderItem]): IO[Error, Event.OrderCreated]
    def confirmOrder(orderId: OrderId): IO[Error, Event.OrderConfirmed]
    def shipOrder(orderId: OrderId, trackingNumber: String): IO[Error, Event.OrderShipped]
    def cancelOrder(orderId: OrderId, reason: String): IO[Error, Event.OrderCancelled]

  // Implementation
  final class Live(deps: Dependencies) extends Service with Handler:

    // Handler implementation
    def handle[E](command: Command[E]): IO[Error, E] =
      command match
        case Command.CreateOrder(customerId, items) => createOrder(customerId, items)
        case Command.ConfirmOrder(orderId) => confirmOrder(orderId)
        case Command.ShipOrder(orderId, trackingNumber) => shipOrder(orderId, trackingNumber)
        case Command.DeliverOrder(orderId) => deliverOrder(orderId)
        case Command.CancelOrder(orderId, reason) => cancelOrder(orderId, reason)
        case Command.ProcessPayment(orderId, amount) => processPayment(orderId, amount)

    // Service implementations
    def createOrder(customerId: CustomerId, items: List[OrderItem]): IO[Error, Event.OrderCreated] =
      for
        _ <- ZIO.when(items.isEmpty)(ZIO.fail(Error.InvalidOrderData("Order must have at least one item")))

        // Check inventory
        _ <- ZIO.foreachDiscard(items) { item =>
          deps.inventoryService.checkAvailability(item.productId, item.quantity).flatMap {
            case false => ZIO.fail(Error.InsufficientInventory(item.productId, item.quantity, 0))
            case true => ZIO.unit
          }
        }

        // Calculate total
        totalAmount = items.map(item => item.price * item.quantity).sum

        // Create order
        orderId = OrderId(java.util.UUID.randomUUID.toString)
        now <- Clock.instant
        order = Order(orderId, customerId, items, OrderStatus.Pending, totalAmount, now)

        // Save and publish event
        _ <- deps.orderRepository.save(order)
        event = Event.OrderCreated(orderId, customerId, totalAmount, now)
        _ <- deps.eventStore.append(event)
      yield event

    def confirmOrder(orderId: OrderId): IO[Error, Event.OrderConfirmed] =
      for
        order <- deps.orderRepository.findById(orderId).someOrFail(Error.OrderNotFound(orderId))
        _ <- validateStatus(order, OrderStatus.Pending)
        now <- Clock.instant
        updatedOrder = order.copy(status = OrderStatus.Confirmed)
        _ <- deps.orderRepository.save(updatedOrder)
        event = Event.OrderConfirmed(orderId, now)
        _ <- deps.eventStore.append(event)
      yield event

    def shipOrder(orderId: OrderId, trackingNumber: String): IO[Error, Event.OrderShipped] =
      for
        order <- deps.orderRepository.findById(orderId).someOrFail(Error.OrderNotFound(orderId))
        _ <- validateStatus(order, OrderStatus.Confirmed)
        now <- Clock.instant
        updatedOrder = order.copy(status = OrderStatus.Shipped)
        _ <- deps.orderRepository.save(updatedOrder)
        event = Event.OrderShipped(orderId, trackingNumber, now)
        _ <- deps.eventStore.append(event)
      yield event

    def deliverOrder(orderId: OrderId): IO[Error, Event.OrderDelivered] =
      for
        order <- deps.orderRepository.findById(orderId).someOrFail(Error.OrderNotFound(orderId))
        _ <- validateStatus(order, OrderStatus.Shipped)
        now <- Clock.instant
        updatedOrder = order.copy(status = OrderStatus.Delivered)
        _ <- deps.orderRepository.save(updatedOrder)
        event = Event.OrderDelivered(orderId, now)
        _ <- deps.eventStore.append(event)
      yield event

    def cancelOrder(orderId: OrderId, reason: String): IO[Error, Event.OrderCancelled] =
      for
        order <- deps.orderRepository.findById(orderId).someOrFail(Error.OrderNotFound(orderId))
        _ <- ZIO.when(order.status == OrderStatus.Delivered)(
          ZIO.fail(Error.InvalidOrderStatus(orderId, OrderStatus.Pending, order.status))
        )
        now <- Clock.instant
        updatedOrder = order.copy(status = OrderStatus.Cancelled)
        _ <- deps.orderRepository.save(updatedOrder)
        event = Event.OrderCancelled(orderId, reason, now)
        _ <- deps.eventStore.append(event)
      yield event

    def processPayment(orderId: OrderId, amount: BigDecimal): IO[Error, Event.PaymentProcessed] =
      for
        order <- deps.orderRepository.findById(orderId).someOrFail(Error.OrderNotFound(orderId))
        result <- deps.paymentService.processPayment(order.customerId.toString, amount)
          .mapError(err => Error.PaymentFailed(orderId, err.getMessage))
        now <- Clock.instant
        event = Event.PaymentProcessed(orderId, amount, now)
        _ <- deps.eventStore.append(event)
      yield event

    // Helper methods
    private def validateStatus(order: Order, expected: OrderStatus): IO[Error, Unit] =
      ZIO.when(order.status != expected)(
        ZIO.fail(Error.InvalidOrderStatus(order.id, expected, order.status))
      )

  val layer: ZLayer[Dependencies, Nothing, Service & Handler] =
    ZLayer.fromFunction(Live.apply)
```

**Usage Example:**
```scala
// Direct service usage
for
  created <- OrderService.createOrder(
    CustomerId("customer-123"),
    List(OrderItem("product-1", 2, BigDecimal(29.99)))
  )
  confirmed <- OrderService.confirmOrder(created.orderId)
  shipped <- OrderService.shipOrder(created.orderId, "TRACK123")
yield shipped

// Via handler (useful for command sourcing, replay, etc.)
val commands = List(
  OrderService.Command.CreateOrder(CustomerId("customer-123"), items),
  OrderService.Command.ProcessPayment(orderId, amount),
  OrderService.Command.ConfirmOrder(orderId),
  OrderService.Command.ShipOrder(orderId, "TRACK123")
)

ZIO.foreach(commands)(OrderService.handle)
```

---

### Example 2: Event Sourcing with Command Replay

```scala
object EventSourcingExample:

  // Command log for replay
  trait CommandLog:
    def append[E](command: OrderService.Command[E]): IO[Nothing, Unit]
    def read: IO[Nothing, List[OrderService.Command[?]]]

  // Replay all commands to rebuild state
  def replayCommands(log: CommandLog): ZIO[OrderService.Handler, OrderService.Error, List[OrderService.Event]] =
    for
      commands <- log.read
      events <- ZIO.foreach(commands) { command =>
        // Type-safe handling via GADT
        OrderService.handle(command)
      }
    yield events

  // Example: Audit trail
  def auditOrderHistory(orderId: OrderService.OrderId): ZIO[OrderService.Dependencies, OrderService.Error, List[OrderService.Event]] =
    for
      deps <- ZIO.service[OrderService.Dependencies]
      events <- deps.eventStore.getEvents(orderId)
    yield events
```

---

## Handler Pattern Benefits

### 1. Command Persistence and Replay

Commands can be persisted and replayed:

```scala
// Persist command before execution
for
  command <- ZIO.succeed(OrderService.Command.CreateOrder(customerId, items))
  _ <- commandLog.append(command)  // Persist first
  event <- OrderService.handle(command)  // Then execute
yield event

// Replay commands to rebuild state
for
  commands <- commandLog.read
  events <- ZIO.foreach(commands)(OrderService.handle)
yield events
```

### 2. Event Sourcing

Events provide complete audit trail:

```scala
// All state changes are events
val events = List(
  Event.OrderCreated(orderId, customerId, amount, now),
  Event.PaymentProcessed(orderId, amount, now),
  Event.OrderConfirmed(orderId, now),
  Event.OrderShipped(orderId, tracking, now)
)

// Rebuild order state from events
def rebuildOrder(events: List[Event]): Order =
  events.foldLeft(initialState) { (order, event) =>
    applyEvent(order, event)
  }
```

### 3. Type-Safe Command Handling

GADTs ensure type safety:

```scala
// Compiler knows Command.RegisterWorker produces Event.WorkerRegistered
val command: Command[Event.WorkerRegistered] = Command.RegisterWorker("worker-1", config)
val event: IO[Error, Event.WorkerRegistered] = handler.handle(command)

// Cannot misuse:
// val wrongEvent: IO[Error, Event.WorkerUnregistered] = handler.handle(command) // ❌ Type error!
```

### 4. Testing and Mocking

Easy to test with command fixtures:

```scala
val testCommands = List(
  Command.RegisterWorker("worker-1", Map()),
  Command.RecordHeartbeat("worker-1"),
  Command.UnregisterWorker("worker-1")
)

// Test by verifying events
val testHandler = new TestHandler
val events = testCommands.traverse(testHandler.handle)

events should contain inOrder(
  Event.WorkerRegistered("worker-1", *),
  Event.HeartbeatRecorded("worker-1", *),
  Event.WorkerUnregistered("worker-1", *)
)
```

### 5. Middleware and Cross-Cutting Concerns

Handlers can be wrapped with middleware:

```scala
trait LoggingHandler(underlying: Handler) extends Handler:
  def handle[E](command: Command[E]): IO[Error, E] =
    for
      _ <- ZIO.logInfo(s"Executing command: $command")
      event <- underlying.handle(command)
      _ <- ZIO.logInfo(s"Produced event: $event")
    yield event

trait MetricsHandler(underlying: Handler, metrics: Metrics) extends Handler:
  def handle[E](command: Command[E]): IO[Error, E] =
    metrics.time(s"command.${command.getClass.getSimpleName}") {
      underlying.handle(command)
    }
```

---

## Best Practices

### 1. Use GADTs for Type-Safe Commands

```scala
// ✅ Good: GADT ensures type safety
enum Command[+E]:
  case RegisterWorker(id: WorkerId) extends Command[Event.WorkerRegistered]
  case UnregisterWorker(id: WorkerId) extends Command[Event.WorkerUnregistered]

// ❌ Bad: No type safety
enum Command:
  case RegisterWorker(id: WorkerId)
  case UnregisterWorker(id: WorkerId)
```

### 2. Events Should Be Immutable Facts

```scala
// ✅ Good: Past tense, immutable facts
case class OrderCreated(orderId: OrderId, createdAt: Instant)
case class PaymentProcessed(orderId: OrderId, amount: BigDecimal, processedAt: Instant)

// ❌ Bad: Present tense, implies ongoing action
case class CreateOrder(orderId: OrderId)
case class ProcessingPayment(orderId: OrderId)
```

### 3. Commands Should Be Serializable

```scala
// ✅ Good: Simple, serializable data
case class RegisterWorker(id: String, config: Map[String, String]) extends Command[Event.WorkerRegistered]

// ❌ Bad: Contains non-serializable types
case class RegisterWorker(id: String, config: Map[String, String], callback: () => Unit) extends Command[Event.WorkerRegistered]
```

### 4. Keep Handler Logic Thin

```scala
// ✅ Good: Handler delegates to business logic
def handle[E](command: Command[E]): IO[Error, E] =
  command match
    case Command.CreateOrder(customerId, items) =>
      createOrder(customerId, items)  // Delegate to service method

// ❌ Bad: Handler contains business logic
def handle[E](command: Command[E]): IO[Error, E] =
  command match
    case Command.CreateOrder(customerId, items) =>
      for
        // ... 50 lines of business logic ...
      yield event
```

### 5. Publish Events After State Changes

```scala
// ✅ Good: Save state, then publish event
for
  _ <- repository.save(order)
  event = Event.OrderCreated(orderId, now)
  _ <- eventStore.append(event)
yield event

// ❌ Bad: Publish event before state is saved (inconsistency risk)
for
  event = Event.OrderCreated(orderId, now)
  _ <- eventStore.append(event)
  _ <- repository.save(order)  // Could fail after event is published!
yield event
```

---

## When to Use This Pattern

### Use Command/Event Handler Pattern When:

✅ **Event Sourcing**: Need complete audit trail of all changes
✅ **CQRS**: Separating commands (writes) from queries (reads)
✅ **Command Replay**: Need to replay commands for debugging/testing
✅ **Saga Orchestration**: Complex workflows with compensating transactions
✅ **Distributed Systems**: Commands/events cross service boundaries
✅ **Compliance**: Regulatory requirements for audit logs
✅ **Temporal Decoupling**: Commands executed now, effects happen later

### Don't Use This Pattern When:

❌ **Simple CRUD**: Basic create/read/update/delete operations
❌ **Low Complexity**: Domain logic is straightforward
❌ **Performance Critical**: Extra abstraction adds overhead
❌ **Small Team**: Team unfamiliar with CQRS/Event Sourcing patterns
❌ **Prototyping**: Need to move fast with less ceremony

---

## Comparison with Other Patterns

| Aspect | Traditional Service | Use Case Pattern | Handler Pattern |
|--------|---------------------|------------------|-----------------|
| **Abstraction Level** | Method calls | Function calls | Command execution |
| **Input Representation** | Parameters | Parameters or case classes | Command objects (GADT) |
| **Output Representation** | Domain types | Domain types or output DTOs | Event objects |
| **Persistability** | No | No | Yes (commands & events) |
| **Replay Support** | No | No | Yes |
| **Audit Trail** | Manual | Manual | Automatic (events) |
| **Type Safety** | Method signatures | Function signatures | GADT type safety |
| **CQRS Fit** | Moderate | Good | Excellent |
| **Event Sourcing Fit** | Poor | Moderate | Excellent |
| **Complexity** | Low | Low-Medium | Medium-High |
| **Boilerplate** | Low | Low-Medium | Medium-High |

---

## Conclusion

The **Handler Pattern with Commands and Events** provides:

- **Type-safe command processing** via GADTs
- **Complete audit trail** via event persistence
- **Command replay** for debugging and testing
- **Clean separation** between commands (what to do) and events (what happened)
- **Excellent fit** for CQRS and Event Sourcing architectures

**Recommendation:**
- Start with **Service Pattern** for simple domains
- Evolve to **Use Case Pattern** for moderate complexity
- Add **Handler Pattern** when you need event sourcing, CQRS, or audit trails

All three patterns can coexist in the same codebase, applied where they fit best!