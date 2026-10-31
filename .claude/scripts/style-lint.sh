#!/usr/bin/env bash
# Deterministic checks for the style guide's hard rules (see .claude/skills/scala/SKILL.md).
# Judgment rules (domain modeling, naming, error design) are left to the reviewer agents.
#
# Usage: style-lint.sh [--base <ref> | --all]
#   --base <ref>  lint main sources changed since <ref> plus untracked ones (default: HEAD).
#   --all         lint every main source file.
# Suppress a finding on one line with a trailing comment:  // style-lint:ignore RULE reason
#
# Exit codes: 0 clean, 1 violations found, 2 usage or environment error.
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

base="HEAD"
all=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="${2:-}"; shift 2 ;;
    --all) all=1; shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

cd "$REPO_ROOT" || exit 2
sbt_prefix="${SBT_ROOT%/}"
[[ "$sbt_prefix" == "." ]] && sbt_prefix=""

if [[ $all -eq 1 ]]; then
  files="$(git ls-files --cached --others --exclude-standard -- "${sbt_prefix:-.}" | grep -E '/src/main/.*\.scala$')"
else
  git rev-parse --verify --quiet "$base^{commit}" >/dev/null || { echo "unknown base ref '$base'" >&2; exit 2; }
  files="$( { git diff --name-only --diff-filter=d "$base" -- "${sbt_prefix:-.}"; git ls-files --others --exclude-standard -- "${sbt_prefix:-.}"; } \
    | grep -E '/src/main/.*\.scala$' | sort -u)"
fi

violations=0

report() { # report <file> <line> <rule> <message> <code>
  printf '%s:%s: %s: %s | %s\n' "$1" "$2" "$3" "$4" "$(printf '%s' "$5" | sed -E 's/^[[:space:]]+//' | cut -c1-140)"
  violations=$((violations + 1))
}

is_app_file() {
  local f="$1" dir
  for dir in $APP_DIRS; do
    [[ "$f" == "${sbt_prefix:+$sbt_prefix/}$dir/"* ]] && return 0
  done
  return 1
}

check() { # check <file> <rule> <regex> <message>
  local f="$1" rule="$2" regex="$3" msg="$4" hit lineno code
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    lineno="${hit%%:*}"
    code="${hit#*:}"
    [[ "$code" =~ ^[[:space:]]*(//|\*|/\*) ]] && continue
    [[ "$code" == *"style-lint:ignore $rule"* ]] && continue
    if [[ "$rule" == IMPL_VISIBILITY && "$code" =~ ^object\ +([A-Za-z0-9_]+) ]]; then
      # The companion of a private[...] Live class stays public so `app` can reach its `layer`.
      grep -qE "private\[[^]]*\].*(class|trait|enum) +${BASH_REMATCH[1]}([^A-Za-z0-9_]|$)" "$f" && continue
    fi
    report "$f" "$lineno" "$rule" "$msg" "$code"
  done < <(grep -nE "$regex" "$f")
}

while IFS= read -r f; do
  [[ -z "$f" || ! -f "$f" ]] && continue

  if ! is_app_file "$f"; then
    check "$f" EFFECT_BOUNDARY 'unsafeRun|Unsafe\.unsafe|Await\.(result|ready)|scala\.concurrent\.Future|(^|[^A-Za-z])Future\[|\.forkDaemon' \
      "effects run only in app's ZIOAppDefault; use structured concurrency (forkScoped)"
  fi
  check "$f" NONDETERMINISM 'Instant\.now|LocalDate(Time)?\.now|ZonedDateTime\.now|System\.(currentTimeMillis|nanoTime)|UUID\.randomUUID|Thread\.sleep|scala\.util\.Random|new Random([^A-Za-z]|$)' \
    "time, randomness and IDs come from Clock / Random / an injected IdGenerator port"
  check "$f" MUTABILITY '(^|[^A-Za-z0-9_.])var +[A-Za-z_]|scala\.collection\.mutable|Atomic(Reference|Integer|Long|Boolean)|[^A-Za-z]synchronized([^A-Za-z]|$)' \
    "no var or shared mutable state; use Ref or immutable collections"
  check "$f" PARTIALITY 'asInstanceOf|[^A-Za-z]null([^A-Za-z]|$)|(^|[^A-Za-z])throw [^ ]|(^|[^A-Za-z.])require\(|Enum\.valueOf|\.valueOf\(' \
    "no casts, null, throw, require or valueOf in main code; return Either / Option / typed errors"
  if [[ "$f" == *-core/src/main/* ]]; then
    check "$f" TASK_IN_SIGNATURE 'def [^=]*:[[:space:]]*(Task|RIO)\[' \
      "service and port signatures use UIO / IO[DomainError, A], never Task (infrastructure failures are defects)"
  fi
  if [[ "$f" == */impl/* ]]; then
    check "$f" IMPL_VISIBILITY '^((final|sealed|abstract|case|open|implicit|inline|transparent|opaque) +)*(class|trait|object|enum|def|val|type|given|extension)[[:space:](]' \
      "top-level declarations under impl must be private[<context>] (or narrower)"
  fi
done <<<"$files"

# Independence Rule: a *-core project never dependsOn another *-core project.
build="${sbt_prefix:+$sbt_prefix/}build.sbt"
if [[ -f "$build" ]]; then
  while IFS=$'\t' read -r lineno proj deps; do
    report "$build" "$lineno" INDEPENDENCE "core project '$proj' depends on another core project; use an anti-corruption port + adapter" "dependsOn($deps)"
  done < <(awk '
    function flush() {
      if (name != "" && is_core[name]) {
        n = split(deps, parts, /[^A-Za-z0-9_]+/)
        for (i = 1; i <= n; i++) if (parts[i] != name && parts[i] ~ /Core$/) { printf "%d\t%s\t%s\n", dep_line, name, deps; break }
      }
      name = ""; deps = ""; dep_line = 0
    }
    /^lazy val [A-Za-z0-9_]+ *= *project/ { flush(); name = $3; if (name ~ /Core$/) is_core[name] = 1; next }
    /^lazy val / { flush() }
    name != "" && /file\("[^"]*-core"\)/ { is_core[name] = 1 }
    name != "" && /dependsOn\(/ { s = $0; sub(/.*dependsOn\(/, "", s); sub(/\).*/, "", s); deps = deps " " s; if (!dep_line) dep_line = NR }
    END { flush() }
  ' "$build")
fi

if [[ $violations -gt 0 ]]; then
  echo "style-lint: $violations violation(s)"
  exit 1
fi
echo "style-lint: clean ($(printf '%s' "$files" | grep -c . | tr -d ' ') file(s) checked)"
