# Examples — Compilable Reference Project

A minimal multi-module sbt project demonstrating the patterns from the style guide.

## Structure

```
examples/
├── build.sbt
├── project/
│   └── build.properties
├── ordering-core/         ← 1st layer: public contract + domain + Live
├── ordering-infra/        ← 2nd layer: persistence adapter (in-memory for demo)
└── app/                   ← wiring
```

## Running

```bash
cd examples
sbt compile        # verify everything compiles
sbt test           # run tests
sbt "app/run"      # start the app (if Main is implemented)
```

## What This Demonstrates

- **Public package** with service trait, DTOs, error enum with codes and messages
- **`impl` package** with rich domain model, repository port, `Live` service, in-memory repo
- **Chained package clauses** for zero-import access in sub-packages
- **`private[ordering]`** scoping on all internal types
- **Error model** with `Code` enum, `httpStatus` hints, structured `message`
- **ZLayer** wiring in the app module
