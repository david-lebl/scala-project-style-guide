# Worker Module - Use Case Pattern Example

A comprehensive example demonstrating the **Use Case Pattern** with **scheduled background jobs** for worker lifecycle management.

## Overview

This module implements a worker management system following the style guide's recommendations:

- **Use Case Pattern** (CQRS) for commands and queries
- **Domain-Driven structure** with layered modules
- **Package-private repositories** hidden as implementation details
- **Typed errors** (`WorkerError`) throughout all layers
- **Scheduled background jobs** for state management (no reactive state transitions)

## Features

### 1. Worker Registration & Unregistration
- Register workers with configuration (region, capacity, capabilities, metadata)
- Workers start in `Pending` status
- Unregister removes worker and all heartbeat history

### 2. Heartbeat Tracking
- Record heartbeat with timestamp
- Track heartbeat history (last 100 per worker)
- Query last heartbeat and history

### 3. State Management via Scheduled Jobs
- **Activation Job**: Pending → Active (when heartbeat exists)
- **Stale Detection Job**: Active → Offline (heartbeat timeout)
- **Reactivation Job**: Offline → Active (recent heartbeat)
- All transitions happen periodically, not reactively

### 4. Query Operations
- Get worker by ID
- Get all active workers
- Get workers by status
- Get workers with metadata (enriched with heartbeat data)

## Module Structure

```
worker/
├── 01-core/                    # Domain logic (no infrastructure)
│   └── src/com/mycompany/worker/
│       ├── Worker.scala                      # Domain model
│       ├── WorkerError.scala                 # Domain-wide errors
│       ├── WorkerUseCases.scala             # Command operations (CQRS)
│       ├── WorkerQueries.scala              # Query operations (CQRS)
│       ├── WorkerScheduler.scala            # Background jobs
│       └── internal/                         # Package-private
│           ├── WorkerRepository.scala           # Repository interface
│           ├── HeartbeatStore.scala             # Heartbeat storage interface
│           ├── InMemoryWorkerRepository.scala   # In-memory implementation
│           └── InMemoryHeartbeatStore.scala     # In-memory implementation
│
├── 02-db/                      # Database implementations
│   └── src/com/mycompany/worker/db/
│       ├── PostgresWorkerRepository.scala
│       └── PostgresHeartbeatStore.scala
│
├── 02-http-server/             # REST API
│   └── src/com/mycompany/worker/http/
│       ├── WorkerRoutes.scala               # HTTP endpoints
│       └── dto/
│           └── WorkerDTOs.scala             # Request/Response DTOs
│
├── 03-impl/                    # Pre-wired bundles
│   └── src/com/mycompany/worker/impl/
│       └── WorkerLayers.scala               # Layer composition
│
└── 04-app/                     # Applications
    └── src/com/mycompany/worker/app/
        ├── WorkerApp.scala                  # HTTP server application
        └── WorkerDemo.scala                 # Demo application
```

## Worker State Machine

```
┌─────────┐
│ Pending │  (Registered, no heartbeat yet)
└────┬────┘
     │ Activation Job (heartbeat exists)
     ▼
┌─────────┐ ◄──────────────────┐
│  Active │                    │ Reactivation Job
└────┬────┘                    │ (recent heartbeat)
     │ Stale Detection Job     │
     │ (heartbeat timeout)     │
     ▼                         │
┌─────────┐                    │
│ Offline │────────────────────┘
└────┬────┘
     │ Manual
     ▼
┌─────────┐
│ Failed  │  (Terminal state)
└─────────┘
```

## Building and Running

### Prerequisites

- JDK 11 or higher
- [Mill](https://mill-build.org/) build tool

### Build

```bash
# Build all modules
mill worker.01-core.compile
mill worker.02-db.compile
mill worker.02-http-server.compile
mill worker.03-impl.compile
mill worker.04-app.compile
```

### Run Demo

```bash
# Run the interactive demo
mill worker.04-app.runMain com.mycompany.worker.app.WorkerDemo
```

Expected output:
```
=== Worker Management Demo ===

1. Registering workers...
   Registered: worker-1, worker-2, worker-3
   Status: Pending (waiting for first heartbeat)

2. Sending heartbeats for worker-1 and worker-2...
   Heartbeats recorded

3. Running scheduler to activate workers with heartbeats...
   Activated: worker-1, worker-2
   (worker-3 still Pending - no heartbeat yet)

4. Querying active workers...
   Active workers: worker-1, worker-2

5. Getting worker-1 with metadata...
   worker-1: Active, region=us-east-1, capacity=10, last heartbeat=2025-10-25T17:00:00Z
   worker-2: Active, region=eu-west-1, capacity=5, last heartbeat=2025-10-25T17:00:00Z

6. Simulating heartbeat timeout (waiting 2 minutes)...
   (Using short timeout for demo: 3 seconds)
   Marked offline: worker-1, worker-2

7. Sending heartbeat for worker-1 to reactivate...
   Reactivated: worker-1

8. Final worker states:
   worker-1: Active
   worker-2: Offline
   worker-3: Pending

9. Unregistering worker-3...
   Remaining workers: worker-1, worker-2

=== Demo Complete ===
```

### Run HTTP Server

```bash
# Start HTTP server on port 8080
mill worker.04-app.runMain com.mycompany.worker.app.WorkerApp
```

## REST API

### Register Worker
```bash
curl -X POST http://localhost:8080/workers \
  -H "Content-Type: application/json" \
  -d '{
    "id": "worker-1",
    "region": "us-east-1",
    "capacity": 10,
    "capabilities": ["compute", "storage"],
    "metadata": {"datacenter": "dc-1"}
  }'
```

### Record Heartbeat
```bash
curl -X POST http://localhost:8080/workers/worker-1/heartbeat
```

### Get Worker
```bash
curl http://localhost:8080/workers/worker-1
```

### Get Active Workers
```bash
curl http://localhost:8080/workers/active
```

### Get All Workers (with optional status filter)
```bash
# All workers
curl http://localhost:8080/workers

# Filter by status
curl http://localhost:8080/workers?status=Active
```

### Get Worker with Metadata
```bash
curl http://localhost:8080/workers/worker-1/metadata
```

### Update Worker Status
```bash
curl -X PUT http://localhost:8080/workers/worker-1/status \
  -H "Content-Type: application/json" \
  -d '{
    "status": "Failed",
    "expectedStatus": "Active"
  }'
```

### Manually Trigger Scheduler
```bash
curl -X POST http://localhost:8080/scheduler/run
```

### Unregister Worker
```bash
curl -X DELETE http://localhost:8080/workers/worker-1
```

## Key Design Patterns

### 1. Use Case Pattern (CQRS)

**Commands** (WorkerUseCases.scala):
- `registerWorker` - Create new worker
- `unregisterWorker` - Remove worker
- `recordHeartbeat` - Record heartbeat
- `activatePendingWorkers` - Scheduler job
- `markStaleWorkersOffline` - Scheduler job
- `reactivateOfflineWorkers` - Scheduler job
- `updateWorkerStatus` - Manual state change

**Queries** (WorkerQueries.scala):
- `getWorker` - Get by ID
- `getActiveWorkers` - Get all active
- `getWorkersByStatus` - Filter by status
- `getAllWorkers` - Get all
- `getLastHeartbeat` - Get last heartbeat
- `getHeartbeatHistory` - Get heartbeat history
- `getWorkersWithMetadata` - Enriched data

### 2. Package-Private Repositories

```scala
// Only accessible within worker package
private[worker] trait WorkerRepository:
  def save(worker: Worker): IO[WorkerError, Unit]
  // ...

// Users interact via use cases
WorkerUseCases.registerWorker("worker-1", config)
```

### 3. Typed Repository Errors

```scala
// Repository returns domain errors, not Throwable
def save(worker: Worker): IO[WorkerError, Unit]

// Implementation maps technical errors
ZIO.attemptBlocking { /* DB operation */ }
  .mapError(e => WorkerError.RepositoryError("save", e.getMessage))
```

### 4. Scheduled Background Jobs

```scala
// No reactive state transitions in recordHeartbeat
def recordHeartbeat(id: Worker.Id): ZIO[...] =
  for
    _ <- verifyWorkerExists(id)
    now <- Clock.instant
    _ <- HeartbeatStore.record(id, now)
    // No state changes here!
  yield ()

// State transitions happen in scheduler
def activationJob: ZIO[...] =
  WorkerUseCases.activatePendingWorkers()
    .schedule(Schedule.fixed(10.seconds))
```

## Configuration

Scheduler configuration in `WorkerScheduler.Config`:

```scala
case class Config(
  activationCheckInterval: Duration = Duration.ofSeconds(10),
  staleCheckInterval: Duration = Duration.ofSeconds(30),
  reactivationCheckInterval: Duration = Duration.ofSeconds(30),
  heartbeatTimeout: Duration = Duration.ofMinutes(2)
)
```

## Database Schema (PostgreSQL)

```sql
-- Workers table
CREATE TABLE workers (
  id TEXT PRIMARY KEY,
  region TEXT NOT NULL,
  capacity INT NOT NULL,
  capabilities TEXT[],
  metadata JSONB,
  status TEXT NOT NULL,
  registered_at TIMESTAMP NOT NULL,
  last_heartbeat_at TIMESTAMP
);

-- Heartbeats table
CREATE TABLE worker_heartbeats (
  worker_id TEXT NOT NULL,
  timestamp TIMESTAMP NOT NULL,
  PRIMARY KEY (worker_id, timestamp)
);

CREATE INDEX idx_worker_heartbeats_timestamp
  ON worker_heartbeats(timestamp DESC);
```

## Testing

### In-Memory Implementation

The `01-core` module includes in-memory implementations for testing:

```scala
val testProgram = for
  worker <- WorkerUseCases.registerWorker("test-1", config)
  _ <- WorkerUseCases.recordHeartbeat(worker.id)
  result <- WorkerQueries.getWorker(worker.id)
yield result

testProgram.provide(WorkerLayers.inMemory)
```

### Switching to PostgreSQL

```scala
// Development: In-memory
app.provide(WorkerLayers.inMemory)

// Production: PostgreSQL
app.provide(
  WorkerLayers.production,
  DataSourceLayer // Provide DataSource
)
```

## Comparison: Use Case vs Service Pattern

| Aspect | Use Case Pattern | Service Pattern |
|--------|------------------|-----------------|
| **Organization** | Standalone functions | Methods in trait |
| **Dependencies** | Minimal per use case | All deps at service level |
| **Composition** | Easy (just function calls) | Via service interface |
| **CQRS Fit** | Excellent (natural split) | Moderate |
| **Boilerplate** | Low-Medium | Low |
| **Best For** | Complex domains, CQRS | Simple to medium domains |

## Design Decisions

### Why Use Case Pattern?

1. **Clear separation of concerns** - Commands vs Queries (CQRS)
2. **Minimal dependencies** - Each use case only depends on what it needs
3. **Easy composition** - Combine use cases for complex workflows
4. **Scheduler-friendly** - Use cases are perfect for scheduled jobs

### Why Package-Private Repositories?

1. **Encapsulation** - Users don't need to know about repositories
2. **Flexibility** - Can change storage without breaking API
3. **Single entry point** - Use cases are the public API

### Why Scheduled Jobs Instead of Reactive?

1. **Predictable** - State transitions happen at known intervals
2. **Efficient** - Batch processing of state checks
3. **Decoupled** - Heartbeat recording doesn't trigger state changes
4. **Testable** - Easy to test scheduler logic independently

## Benefits

✅ **Type Safety** - Opaque types, enums, typed errors
✅ **Encapsulation** - Repositories hidden, use cases as public API
✅ **Composability** - Use cases easily combined
✅ **Testability** - Each use case testable independently
✅ **Scalability** - Scheduled jobs scale better than reactive
✅ **Maintainability** - Clear separation of concerns (CQRS)
✅ **Flexibility** - Easy to swap implementations (in-memory, PostgreSQL)

## License

See LICENSE file in repository root.
