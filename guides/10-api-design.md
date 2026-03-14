# \[WIP\] 10 — API Design

> HTTP routes, JSON codecs, request/response conventions, versioning, and OpenAPI.

---

> **Status:** Outline — detailed content to be added.

## Table of Contents

1. **Routes Live in the Infra Layer**
   - Package: `com.myco.<context>.impl.http`
   - Build module: `*-infra`
   - Routes depend on the public service trait, not on domain internals

2. **Request / Response Conventions**
   - Requests map to public input DTOs (defined in `*-core`)
   - Responses map to public view DTOs (defined in `*-core`)
   - JSON codecs derived with zio-json, kept in the `impl.http` package
   - Error responses follow the standard `{ "code": "...", "message": "..." }` shape

3. **Error-to-HTTP Mapping**
   - Use `error.code.httpStatus` as the status hint
   - Map in a single `catchAll` block per route group
   - Defects → 500 with generic message, logged with full cause
   - See [03 — Error Model](03-error-model.md) §4.3 for the full pattern

4. **Route Grouping**
   - One `Routes` object per bounded context
   - All routes for a context composed into a single `Routes[<Service>, Nothing]`
   - App module merges all context routes

5. **Tapir Integration (Optional)**
   - Endpoint definitions as documentation-first contracts
   - Automatic OpenAPI / Swagger UI generation
   - Server and client generation from the same endpoint definition
   - Tapir endpoints can live in `*-core`'s public package if used for client generation

6. **Versioning Strategy**
   - URL path versioning (`/v1/orders`) for major breaking changes
   - Additive changes (new optional fields) don't require a new version
   - Deprecated fields: keep, mark, remove in next major version
   - Version-specific DTOs if needed: `CheckoutInputV1`, `CheckoutInputV2`

7. **Authentication / Authorization**
   - Middleware extracts auth context, attaches to ZIO environment or passes as parameter
   - Service methods receive a typed `AuthContext` / `UserId`, not raw tokens
   - Authorization logic lives in the service `Live`, not in routes

8. **Pagination and Filtering**
   - Standard query parameters: `offset`, `limit`, `sort`, `filter`
   - Return `PagedResponse[A]` with `items`, `total`, `offset`, `limit`
   - Keep pagination logic in the repository port, not in the service

---

*See also: [03 — Error Model](03-error-model.md), [04 — Service Design](04-service-design.md)*
