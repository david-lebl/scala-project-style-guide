# \[WIP\] 09 — Testing

> In-memory layers, property-based testing, integration tests with containers, and test structure.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **Testing Philosophy**
   - Pure domain functions → pure unit tests, no layers needed
   - Service `Live` logic → unit tests with in-memory layers
   - Persistence adapters → integration tests with test containers
   - HTTP routes → integration tests or contract tests
   - No mocks — in-memory implementations instead

2. **In-Memory Layers**
   - `Ref`-based repositories: `InMemoryOrderRepository`
   - Stub anti-corruption ports: return fixed data or fail predictably
   - Composing a full test layer: `OrderServiceLive.layer ++ InMemoryOrderRepository.layer ++ ...`

3. **Testing Domain Logic**
   - Pure functions tested directly with `assert` / `assertTrue`
   - Smart constructors tested for both success and rejection
   - Exhaustive enum coverage: one test per error case

4. **Testing Services**
   - `.exit` to capture typed errors: `assert(result)(fails(equalTo(OrderError.EmptyItemList)))`
   - Testing side effects: verify repository state after operations
   - Testing error propagation through for-comprehensions

5. **Testing Persistence Adapters**
   - Testcontainers for Postgres / MySQL / etc.
   - Shared container per test suite (not per test) for speed
   - Flyway migrations applied in test setup
   - Round-trip tests: save domain entity → load → assert equality

6. **Property-Based Testing**
   - Generating domain types with `Gen` (zio-test)
   - Invariant properties: "save then findById returns the same entity"
   - Shrinking and failure reporting

7. **Test Structure Conventions**
   - Tests live in `src/test` of the module they test
   - `*-core/src/test` → domain + service unit tests
   - `*-infra/src/test` → persistence + adapter integration tests
   - Shared test utilities in a `test-commons` module if needed

8. **Anti-Patterns**
   - Mocking repository traits with Mockito (use in-memory instead)
   - Testing implementation details instead of behaviour
   - Integration tests for pure domain logic (too slow, too fragile)
   - Skipping error case tests ("it compiles, it works")

---

*See also: [04 — Service Design](04-service-design.md), [07 — Dependency Injection](07-dependency-injection.md)*
