# 02 — Domain Modeling

> ADTs, strong types, opaque types, smart constructors, entities vs values — how to model a domain in Scala 3.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **Start from the Domain, Not the Database**
   - Persistence-agnostic modeling
   - Discovery process: talking to domain experts
   - Functions for actions, values for data

2. **No Primitives in the Domain**
   - Why `String`, `Int`, `Boolean` are dangerous in domain signatures
   - Opaque types for zero-cost wrappers
   - Case classes for composite value objects
   - When to use opaque vs case class

3. **Algebraic Data Types**
   - Product types (case classes) — why and how
   - Sum types (enums) — why and how
   - Benefits: equality, pattern matching, exhaustiveness, derivation, immutability

4. **Enums for Closed Domains**
   - Scala 3 `enum` syntax
   - Parameterised enum cases
   - Pattern matching and exhaustiveness guarantees

5. **Make Illegal States Unrepresentable**
   - Sum types over `Option` fields with runtime assertions
   - Encoding business rules in the type structure
   - Real-world example: VAT / discount / domestic vs non-domestic pricing

6. **Smart Constructors**
   - `private` constructor + `parse` / `make` returning `Either`
   - Scala 3 `case class private` behavior (`.copy`, `new`, `apply` all private)
   - When to use smart constructors vs plain case classes

7. **Entities vs Values**
   - Identity vs structural equality
   - Separating `id` from `data`: `case class Order(id: Order.Id, data: Order.Data)`
   - How this simplifies comparison, deduplication, and pattern matching

8. **Nesting and Namespacing**
   - Companion object as namespace for exclusively-owned types
   - Flat `impl` package for cross-cutting domain types
   - Naming conventions: `Address.City` vs `City`

9. **Non-Uniform Models**
   - Domain ≠ DTO ≠ DAO — why three representations
   - Where each representation lives (core public, core impl, infra)
   - Parse-don't-validate: `DTO.toDomain: Either[Error, Domain]`

10. **Quality of Life**
    - Chimney for case class transformations
    - Iron for refined types
    - zio.NonEmptyChunk for non-empty collections
    - Deriving JSON codecs, equality, ordering

---

*See also: [03 — Error Model](03-error-model.md), [05 — Persistence](05-persistence.md)*
