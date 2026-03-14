# 06 — Bounded Contexts

> Anti-corruption layers, cross-context communication, merging heuristic, and event-driven decoupling.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **The Independence Rule**
   - Core modules never depend on other core modules
   - Why: circular dependency prevention, independent extraction, clean domain language
   - The only cross-context path: port in core → adapter in infra

2. **Anti-Corruption Layer — Full Pattern**
   - The consuming context defines a port in its own language (`OrderingPort`)
   - The port uses the consumer's own snapshot types (`OrderSnapshot`), not the producer's DTOs
   - The adapter in `*-infra/impl/adapters/` bridges the port to the other context's public service
   - Error translation in the adapter: foreign errors → `Option.None` / local errors / defects

3. **Snapshot Types vs Reusing Public DTOs**
   - When to define your own snapshot: different vocabulary, different fields needed
   - When it's OK to reuse a commons type: truly universal concepts (Money, Country)
   - Never reuse another context's public DTO directly — even if fields match today

4. **Multiple Services in One Bounded Context**
   - Heuristic: if two services share >50% domain vocabulary and evolve together → same context
   - Each service gets its own public trait and DTOs but shares the `impl` domain model
   - Example: `OrderService` + `FulfillmentService` in one `ordering-core`
   - This is simpler than a thin anti-corruption layer that adds no real translation

5. **Event-Driven Decoupling**
   - Publishing events in the `*-core` public package
   - Event types as `enum` — same structure principles as domain types
   - Consumers subscribe via their anti-corruption adapter
   - In-process event bus (ZIO Hub) vs external (Kafka, RabbitMQ)
   - Choosing sync (direct call) vs async (events): consistency vs coupling trade-offs

6. **Cross-Context Data Flow Diagram**
   - Visual: core A → public event / service → adapter in infra B → port in core B → Live B
   - No arrows between core A and core B

7. **When to Split vs When to Merge**
   - Split signals: different domain experts, different change cadences, different deployment needs
   - Merge signals: shared entities, shared vocabulary, changes always touch both
   - Refactoring path: extracting a service from a context into its own context

---

*See also: [01 — Project Structure](01-project-structure.md), [11 — Extraction](11-extraction.md)*
