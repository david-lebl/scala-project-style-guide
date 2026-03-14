# Mill Multi-Module Examples

This directory contains complete, executable examples demonstrating the patterns from the [Scala Project Style Guide](../README.md) using Mill build tool.

## Why Mill?

The main project uses `scala-cli` for simplicity, but multi-module projects require a proper build tool. Mill provides:

- **Multi-module support** with clear dependency management
- **Fast incremental compilation**
- **Simple, intuitive syntax** (compared to sbt)
- **Good IDE integration**

## Examples

### [Worker Module](./worker/) - Use Case Pattern with Scheduled Jobs

A comprehensive worker management system demonstrating:

- ✅ **Use Case Pattern** (CQRS) - Commands and queries as standalone functions
- ✅ **Domain-Driven structure** - Layered modules (01-core, 02-db, 02-http-server, 03-impl, 04-app)
- ✅ **Package-private repositories** - Hidden as implementation details
- ✅ **Typed errors** - `WorkerError` throughout all layers
- ✅ **Scheduled background jobs** - State management without reactive transitions
- ✅ **REST API** - ZIO HTTP endpoints with DTOs
- ✅ **PostgreSQL + In-Memory** - Swappable implementations

**Features:**
- Worker registration/unregistration
- Heartbeat tracking with history
- State management (Pending → Active → Offline)
- Scheduled jobs for state transitions
- Query operations with metadata enrichment

[→ Worker Module Documentation](./worker/README.md)

## Prerequisites

### Install Mill

**macOS (Homebrew):**
```bash
brew install mill
```

**Linux:**
```bash
curl -L https://github.com/com-lihaoyi/mill/releases/download/0.11.7/0.11.7 > mill
chmod +x mill
sudo mv mill /usr/local/bin/
```

**Windows:**
See [Mill installation docs](https://mill-build.org/mill/Intro_to_Mill.html#_installation)

### Verify Installation

```bash
mill version
# Should print: 0.11.7 (or newer)
```

## Building All Examples

```bash
# From examples-mill directory
mill _.compile
```

## Running Examples

### Worker Demo

```bash
mill worker.04-app.runMain com.mycompany.worker.app.WorkerDemo
```

### Worker HTTP Server

```bash
mill worker.04-app.runMain com.mycompany.worker.app.WorkerApp
```

Then test with:
```bash
# Register a worker
curl -X POST http://localhost:8080/workers \
  -H "Content-Type: application/json" \
  -d '{"id": "worker-1", "region": "us-east-1", "capacity": 10}'

# Send heartbeat
curl -X POST http://localhost:8080/workers/worker-1/heartbeat

# Get active workers
curl http://localhost:8080/workers/active
```

## Project Structure

```
examples-mill/
├── build.sc                    # Root Mill build file
├── worker/                     # Worker management example
│   ├── 01-core/               # Domain logic
│   ├── 02-db/                 # PostgreSQL implementations
│   ├── 02-http-server/        # REST API
│   ├── 03-impl/               # Pre-wired bundles
│   ├── 04-app/                # Applications
│   └── README.md              # Worker documentation
└── README.md                   # This file
```

## Mill Commands

### Compile

```bash
# Compile specific module
mill worker.01-core.compile
mill worker.02-db.compile

# Compile all modules
mill _.compile
```

### Run

```bash
# Run main class
mill worker.04-app.runMain com.mycompany.worker.app.WorkerDemo

# With arguments
mill worker.04-app.runMain com.mycompany.worker.app.WorkerApp --port 9090
```

### Test

```bash
# Run tests for specific module
mill worker.01-core.test

# Run all tests
mill _.test
```

### Clean

```bash
# Clean specific module
mill worker.01-core.clean

# Clean everything
mill clean
```

### Show Dependencies

```bash
# Show module dependency tree
mill show worker.04-app.moduleDeps
mill show worker.04-app.ivyDeps
```

### REPL

```bash
# Start REPL with module on classpath
mill worker.01-core.repl
```

## Module Naming Convention

Modules use numbered prefixes for organization (directory names only):

- `01-*` - Domain definition & pure implementation (core, models, errors)
- `02-*` - Infrastructure implementations (db, http-server, http-client)
- `03-*` - Wiring & testing (impl bundles, integration tests)
- `04-*` - Applications

**Note:** Module names in `build.sc` use backticks for numbers:
```scala
object worker extends Module {
  object `01-core` extends ScalaModule { ... }
  object `02-db` extends ScalaModule { ... }
}
```

## IDE Setup

### IntelliJ IDEA

1. Install Mill plugin from JetBrains Marketplace
2. Open `examples-mill/build.sc`
3. IntelliJ will automatically import the project

### VS Code

1. Install "Scala (Metals)" extension
2. Open `examples-mill` directory
3. Metals will prompt to import build
4. Select "Mill" as build tool

### Generate IntelliJ Config

```bash
mill mill.scalalib.GenIdea/idea
```

## Style Guide Patterns Demonstrated

### Worker Example Demonstrates:

1. **Error Modeling**
   - ✅ Single error type per domain (`WorkerError`)
   - ✅ Descriptive messages in each error case
   - ✅ Extends `NoStackTrace` for efficiency
   - ✅ Logical grouping (Registration, Heartbeat, State, Infrastructure)

2. **Monorepo Structure**
   - ✅ Domain-based modules with layer separation
   - ✅ Numbered prefixes (01-core, 02-db, 03-impl, 04-app)
   - ✅ Clear dependency rules (core ← db ← impl ← app)
   - ✅ Public API in top-level, internals in `internal/`

3. **Use Case Pattern**
   - ✅ CQRS separation (WorkerUseCases, WorkerQueries)
   - ✅ Standalone functions with minimal dependencies
   - ✅ Easy composition and testing
   - ✅ Perfect for scheduled jobs

4. **Domain-Driven Design**
   - ✅ Package-private repositories (encapsulation)
   - ✅ Typed errors throughout (no Throwable leaking)
   - ✅ Separate DTOs from domain models
   - ✅ Infrastructure abstraction

## Adding New Examples

To add a new example:

1. Create a new module directory (e.g., `order/`)
2. Add module definition to `build.sc`:

```scala
object order extends Module {
  object `01-core` extends ScalaModule {
    def scalaVersion = "3.7.3"
    def ivyDeps = Agg(ivy"dev.zio::zio:2.1.22")
  }
  // ... more submodules
}
```

3. Create source directories:
```bash
mkdir -p order/01-core/src/com/mycompany/order
```

4. Add README.md with documentation

## Troubleshooting

### Mill Not Found

```bash
# Check installation
mill version

# Reinstall if needed (macOS)
brew reinstall mill
```

### Compilation Errors

```bash
# Clean and recompile
mill clean
mill _.compile
```

### Module Not Found

```bash
# Check module path
mill resolve worker._

# Show all modules
mill resolve _
```

## Resources

- [Mill Documentation](https://mill-build.org/)
- [ZIO Documentation](https://zio.dev/)
- [Scala 3 Documentation](https://docs.scala-lang.org/scala3/)
- [Style Guide](../README.md)

## Contributing

When adding examples:

1. Follow the style guide patterns
2. Use numbered module prefixes (01-core, 02-db, etc.)
3. Include comprehensive README.md
4. Provide both demo and server applications
5. Support both in-memory and persistent storage
6. Add inline documentation and comments

## License

See LICENSE file in repository root.
