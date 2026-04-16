---
name: scaladoc-check
description: Verify that the public API of *-core modules (com.myco.<ctx>, not .impl) has up-to-date scaladoc. Use when the user asks about docs, missing scaladoc, out-of-date doc comments, or before publishing. Reports missing / stale / orphaned scaladoc; only rewrites when the user asks.
argument-hint: [module-or-path?]
allowed-tools: Read Glob Grep Bash(git log *) Bash(git diff *)
---

Audit scaladoc on the public API surface. The public API is everything in `com.myco.<ctx>` (not `com.myco.<ctx>.impl`). That is the stable contract per CLAUDE.md's Cardinal Rule.

## Pre-flight

1. Read `CLAUDE.md` to confirm the package boundary: public = `com.myco.<ctx>`, internal = `com.myco.<ctx>.impl`.
2. Default scope if `$ARGUMENTS` is empty: every `*-core` module in the project. Find them with `Glob **/*-core/src/main/scala`.
3. Otherwise treat `$ARGUMENTS` as a module name or a path.

## Inputs

`$ARGUMENTS` — optional. Module name (`orderingCore`), relative path (`examples/ordering-core/...`), or blank.

## What counts as "public API"

- Public `trait`, `class`, `object`, `enum`, `case class`, opaque type, extension method, given, val, def — at the **top level** of a file under the public package (not inside `impl`).
- Nested `Code` enums inside error ADTs (each case needs a comment line for the `Code.value`).
- **Not** counted: types in `impl/`, types marked `private` / `private[ctx]`, companion-object internals that are not re-exported.

## Checklist

1. Resolve scope.
2. For each candidate source file under `*-core/src/main/scala/com/myco/<ctx>/` (exclude `impl/`):
   a. Parse the package clause — confirm it is the public package.
   b. Walk top-level definitions (`trait`, `class`, `object`, `enum`, `case class`, `opaque type`, `def`, `val`).
   c. For each definition, check that it is immediately preceded by a `/** ... */` block.
3. For each error ADT in scope (file contains `enum .*Error` with a nested `Code`):
   a. Check every case has a one-line doc comment explaining the failure condition.
   b. Check the `Code` value matches the case name convention used in the rest of the project (look at siblings).
4. For each defined `def` or `val` with scaladoc:
   a. If the current signature differs from what the scaladoc describes (e.g. param names, return type), flag as "stale".
   b. If scaladoc references a parameter that no longer exists, flag as "orphaned".
5. Optionally compare against `git log -p` to see whether scaladoc was last touched in the same commit as the signature. If not, it is suspicious — mark as "possibly stale".

## Invariants to enforce

- Every top-level public definition has scaladoc. No exceptions for "obvious" getters.
- Public DTOs (Input / View types) must document what each field means from the caller's perspective, not how it is stored.
- Error cases have one-line doc describing the *domain* condition (e.g. "raised when the ordered item is not in the catalogue"), not the technical one ("thrown when repo returns None").
- This skill **does not rewrite scaladoc** unless the user explicitly asks. It reports and stops.

## Output format

```
## scaladoc-check — <scope>

### Missing (N)
- <file>:<line> — <type/def name>

### Stale (N)
- <file>:<line> — <name> — <what drifted>

### Orphaned parameter refs (N)
- <file>:<line> — <name> — @param <x> no longer exists

### Verdict
<N public symbols, M documented, K missing, J stale>
```

## Common mistakes

- Flagging `impl/` files as missing scaladoc. They are internal — leave them alone.
- Demanding scaladoc on `case class` synthetic members (`copy`, `apply`) — only the primary declaration needs it.
- Silently rewriting docs. Never do that here; say "would rewrite X" and let the user approve.
- Missing doc on `enum` cases of an error ADT. They count. Check each case.