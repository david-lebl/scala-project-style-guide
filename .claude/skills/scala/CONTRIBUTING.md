# Contributing to the Scala Skill

## Structure

- `SKILL.md` — skill definition: short rules grouped by topic, and the chapter
  index (Use-Case Guide) at the bottom.
- One markdown file per chapter, numbered by group (`100-*.md`, `110-*.md`,
  `200-*.md`, etc.). Groups start at 100, 200, 300, etc.; chapters within a
  group increment by 10.

| Group | Topic |
|-------|-------|
| 100 | Foundations — structure, organization, domain modeling, FP principles |
| 200 | Effects — fundamentals, errors, resources, concurrency |
| 300 | Application architecture — services, persistence, contexts, DI, config, API, extraction |
| 400 | Testing |

Each chapter covers a single use-case and follows this format:

1. **Title** — the use-case name, followed by a paragraph saying what the
   chapter covers and when to read it.
2. **Dependencies** — sbt coordinates (`"group" %% "artifact"`) without
   versions, each with a short explanation; `None.` if there are none.
3. `---`, then **content** — `##` sections with prose and code examples.

## Sources

Chapters are the self-contained form of this repository's style guide. Read
the source before changing a chapter, and update the chapter when its source
changes:

| Chapter | Source |
|---------|--------|
| 100-project-structure | `guides/01-project-structure.md` |
| 110-code-organization | `guides/01-project-structure.md` §5 + Scala visibility/file rules |
| 120-domain-modeling | `guides/02-domain-modeling.md` |
| 130-functional-principles | skill-only (pure Scala principles) |
| 200-effect-fundamentals | skill-only, consistent with `guides/03`, `guides/04` |
| 210-error-model | `guides/03-error-model.md` |
| 220-resources-and-lifecycle | skill-only (ZIO `Scope`, `ZLayer.scoped`) |
| 230-concurrency-and-streaming | skill-only (ZIO concurrency, zio-streams) |
| 300-service-design | `guides/04-service-design.md` |
| 310-persistence | `guides/05-persistence.md` |
| 320-bounded-contexts | `guides/06-bounded-contexts.md`, `decisions/002-*.md` |
| 330-dependency-injection | `guides/07-dependency-injection.md` |
| 340-configuration | `guides/08-configuration.md` |
| 350-api-design | `guides/10-api-design.md` |
| 360-extraction | `guides/11-extraction.md` |
| 400-testing | `guides/09-testing.md` |

Code should match the compilable project in `examples/` where an equivalent
exists. Library APIs: [ZIO](https://zio.dev) and the ZIO ecosystem
documentation.

## Instructions for adding a new chapter

1. **Read the sources.** The matching guide, `examples/`, and the library
   documentation. Don't guess at APIs — check signatures (e.g. with `cellar`)
   against the ZIO version in `examples/build.sbt`.
2. **Identify the use-case.** One focused topic per chapter. Don't combine
   unrelated concerns.
3. **Create the chapter file** as `NNN-slug.md` with the next number in the
   right group and a short kebab-case slug.
4. **Write the chapter** in the format above.
5. **Update `SKILL.md`** — add the chapter to the index, and add or adjust any
   one-line rule it introduces.
6. **Update the source table** above.

### Writing guidelines

- **Effect style only.** Examples use ZIO 2. No direct-style code and no
  frameworks outside the ZIO ecosystem and the libraries the guides name
  (no Ox, sttp, Tapir, Akka/Pekko, Cats Effect or Kyo code). Cats Effect and
  Kyo are mentioned only as "same principles" notes in `SKILL.md`.
- **Self-contained.** Chapters link only to other chapters of this skill,
  never to `guides/`, so the skill works when copied elsewhere.
- **Use the running example.** The `com.myco.ordering` context (and
  `shipping` for cross-context examples), the same as in the guides.
- **Base code on real sources** and keep it compilable in spirit: real ZIO
  APIs, braceless Scala 3, explicit result types on public members. Use `???`
  only for bodies irrelevant to the point.
- **Explain the why, not just the what.** Why does the port return `UIO`? Why
  is `.forkDaemon` forbidden? Why a snapshot type instead of the foreign DTO?
- **Keep it direct.** No filler or motivational paragraphs.
- **Dependencies are coordinates only.** No version numbers — they go stale.
- **Show only the essence.** Each example illustrates exactly one pattern.
  Prefer wrong/right pairs for rules.
- **Use `>` callouts for pitfalls and requirements** (`> **Warning:**`,
  `> **Important:**`, `> **Required:**`) — only for what is critical, easy to
  miss, or affects correctness. General explanation stays as prose.
- **Don't duplicate content across chapters.** Link to the chapter that owns
  the topic instead.
