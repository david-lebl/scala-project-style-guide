# Quick Start Guide

Get the Worker example running in 5 minutes!

## Prerequisites

Install Mill build tool:

**macOS:**
```bash
brew install mill
```

**Linux:**
```bash
curl -L https://github.com/com-lihaoyi/mill/releases/download/0.11.7/0.11.7 > mill
chmod +x mill
sudo mv mill /usr/local/bin/
```

## Run the Demo

```bash
# From the examples-mill directory
mill worker.04-app.runMain com.mycompany.worker.app.WorkerDemo
```

You should see:
```
=== Worker Management Demo ===

1. Registering workers...
   Registered: worker-1, worker-2, worker-3
   Status: Pending (waiting for first heartbeat)

2. Sending heartbeats for worker-1 and worker-2...
   Heartbeats recorded

3. Running scheduler to activate workers with heartbeats...
   Activated: worker-1, worker-2
   ...
```

## Run the HTTP Server

```bash
mill worker.04-app.runMain com.mycompany.worker.app.WorkerApp
```

In another terminal:

```bash
# Register a worker
curl -X POST http://localhost:8080/workers \
  -H "Content-Type: application/json" \
  -d '{
    "id": "worker-1",
    "region": "us-east-1",
    "capacity": 10
  }'

# Send heartbeat
curl -X POST http://localhost:8080/workers/worker-1/heartbeat

# Wait 11 seconds for scheduler to activate

# Get active workers
curl http://localhost:8080/workers/active
# Should show worker-1 as Active
```

## Explore the Code

Start with these files:

1. **Domain Model**: `worker/01-core/src/com/mycompany/worker/Worker.scala`
2. **Use Cases**: `worker/01-core/src/com/mycompany/worker/WorkerUseCases.scala`
3. **Queries**: `worker/01-core/src/com/mycompany/worker/WorkerQueries.scala`
4. **Scheduler**: `worker/01-core/src/com/mycompany/worker/WorkerScheduler.scala`

## What's Happening?

1. **Register Worker** → Worker created in `Pending` status
2. **Send Heartbeat** → Heartbeat recorded (no state change yet)
3. **Scheduler Runs** (every 10s) → Checks for pending workers with heartbeats → Activates them
4. **No Heartbeat for 2 min** → Scheduler marks as `Offline`
5. **Heartbeat Resumes** → Scheduler reactivates to `Active`

All state transitions happen via scheduled background jobs, not reactively!

## Common Mill Commands

```bash
# Compile everything
mill _.compile

# Compile specific module
mill worker.01-core.compile

# List all modules
mill resolve _

# Show module dependencies
mill show worker.04-app.moduleDeps

# Clean everything
mill clean

# Start REPL with module
mill worker.01-core.repl
```

## Next Steps

Read the full documentation:
- [Worker Module README](./worker/README.md) - Complete documentation
- [Examples README](./README.md) - Overview of all examples
- [Style Guide](../README.md) - Design patterns and principles

## Troubleshooting

**Mill command not found:**
```bash
mill version
# If not found, reinstall Mill
```

**Compilation errors:**
```bash
mill clean
mill _.compile
```

**Port 8080 already in use:**
```bash
# Find and kill process using port 8080
lsof -ti:8080 | xargs kill -9
```

## Full Example Workflow

```bash
# 1. Build everything
mill _.compile

# 2. Run demo to see all features
mill worker.04-app.runMain com.mycompany.worker.app.WorkerDemo

# 3. Start HTTP server in background
mill worker.04-app.runMain com.mycompany.worker.app.WorkerApp &
SERVER_PID=$!

# 4. Register workers
curl -X POST http://localhost:8080/workers \
  -H "Content-Type: application/json" \
  -d '{"id": "worker-1", "region": "us-east-1", "capacity": 10}'

curl -X POST http://localhost:8080/workers \
  -H "Content-Type: application/json" \
  -d '{"id": "worker-2", "region": "eu-west-1", "capacity": 5}'

# 5. Send heartbeats
curl -X POST http://localhost:8080/workers/worker-1/heartbeat
curl -X POST http://localhost:8080/workers/worker-2/heartbeat

# 6. Wait for activation (11 seconds)
sleep 11

# 7. Check active workers
curl http://localhost:8080/workers/active

# 8. Get worker with metadata
curl http://localhost:8080/workers/worker-1/metadata

# 9. Manually trigger scheduler
curl -X POST http://localhost:8080/scheduler/run

# 10. Cleanup
kill $SERVER_PID
```

## Happy Coding! 🎉
