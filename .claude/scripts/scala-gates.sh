#!/usr/bin/env bash
# Quality gates for the Scala workflow: style lint, scalafmt, compile (main + test), tests.
#
# Usage: scala-gates.sh [--base <ref>] [--fix] [--skip-tests] [module ...]
#   --base <ref>   files changed since <ref> (plus untracked files) are lint- and format-checked.
#                  Defaults to the active run's baseCommit, else HEAD.
#   --fix          format the changed files with scalafmt before checking.
#   --skip-tests   stop after compilation.
#   module ...     sbt project ids to scope compile/test to (default: the whole build).
#
# Exit codes: 0 all gates pass, 1 a gate failed, 2 usage or environment error.
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

base=""
fix=0
skip_tests=0
modules=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="${2:-}"; shift 2 ;;
    --fix) fix=1; shift ;;
    --skip-tests) skip_tests=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) modules+=("$1"); shift ;;
  esac
done

[[ -f "$SBT_DIR/build.sbt" ]] || { echo "GATES: ERROR no build.sbt in $SBT_DIR (check SBT_ROOT in .claude/scala-workflow.env)" >&2; exit 2; }
[[ -z "$base" ]] && base="$(active_run_base)"
[[ -z "$base" ]] && base="HEAD"
git -C "$REPO_ROOT" rev-parse --verify --quiet "$base^{commit}" >/dev/null || { echo "GATES: ERROR unknown base ref '$base'" >&2; exit 2; }

log_dir="$SBT_DIR/target/scala-gates"
mkdir -p "$log_dir"
log="$log_dir/last.log"

scoped() { # scoped <task> -> "<task>" or "m1/<task> m2/<task>"
  if [[ ${#modules[@]} -eq 0 ]]; then
    printf '%s\n' "$1"
  else
    printf '%s\n' "${modules[@]/%//$1}"
  fi
}

# ── Lint ────────────────────────────────────────────────────────
lint_out="$("$SCRIPT_DIR/style-lint.sh" --base "$base" 2>&1)"
lint_status=$?
lint_result=PASS
[[ $lint_status -eq 1 ]] && lint_result=FAIL
[[ $lint_status -ge 2 ]] && lint_result=ERROR

# ── sbt: format, compile, test ──────────────────────────────────
sbt_args=()
fmt_enabled=0
if [[ -f "$SBT_DIR/.scalafmt.conf" ]]; then
  fmt_enabled=1
  fmt_task=scalafmtCheckAll
  [[ $fix -eq 1 ]] && fmt_task=scalafmtAll
  for filter in "diff-ref=$base" "diff-dirty"; do
    sbt_args+=("set ThisBuild / scalafmtFilter := \"$filter\"")
    while IFS= read -r t; do sbt_args+=("$t"); done < <(scoped "$fmt_task")
  done
fi
while IFS= read -r t; do sbt_args+=("$t"); done < <(scoped "Test/compile")
if [[ $skip_tests -eq 0 ]]; then
  while IFS= read -r t; do sbt_args+=("$t"); done < <(scoped "test")
fi

read -r -a sbt_cmd <<<"$SBT_CMD"
(cd "$SBT_DIR" && "${sbt_cmd[@]}" "${sbt_args[@]}") 2>&1 | strip_ansi >"$log"
sbt_status=${PIPESTATUS[0]}

fmt_result=SKIPPED; compile_result=NOT_RUN; test_result=NOT_RUN
[[ $fmt_enabled -eq 1 ]] && fmt_result=PASS
[[ $skip_tests -eq 1 ]] && test_result=SKIPPED
details=""

if [[ $sbt_status -eq 0 ]]; then
  compile_result=PASS
  if [[ $skip_tests -eq 0 ]]; then
    test_result=PASS
    executed="$(grep -Eo '([0-9]+) tests passed|Passed: Total [0-9]+|Tests: succeeded [0-9]+' "$log" | grep -Eo '[0-9]+' | awk '{s+=$1} END {print s+0}')"
    if [[ "$REQUIRE_TESTS" == "1" && "$executed" -eq 0 ]]; then
      test_result=FAIL
      details="No tests were executed. Is the test framework (e.g. zio-test-sbt) on the Test classpath, and do the touched modules have tests?"
    fi
  fi
else
  failed_task="$(grep -Eo '^\[error\] \([^)]*\)' "$log" | head -1)"
  case "$failed_task" in
    *scalafmt*) fmt_result=FAIL ;;
    *compile*|*Compile*) compile_result=FAIL ;;
    *executeTests*|*test*|*Test*) compile_result=PASS; test_result=FAIL ;;
    *) compile_result=FAIL ;;
  esac
  [[ $fmt_result == FAIL ]] && details="Unformatted files (run: .claude/scripts/scala-gates.sh --fix):"$'\n'
  details+="$(grep -E '\[error\]|\[warn\]|^ +- |tests? failed|^ +(✗|×)' "$log" \
    | grep -vE '^\[error\] +at |Total time|^\[error\] \(.*\) .*$|stack trace is suppressed|^\[warn\] +$' | head -120)"
fi

overall=PASS
for r in "$lint_result" "$fmt_result" "$compile_result" "$test_result"; do
  [[ "$r" == FAIL || "$r" == ERROR ]] && overall=FAIL
done

echo "GATES: $overall (base ${base:0:12}${modules[*]:+, modules: ${modules[*]}})"
printf '  %-8s %s\n' lint "$lint_result" format "$fmt_result" compile "$compile_result" test "$test_result"
if [[ "$lint_result" != PASS ]]; then
  echo "--- lint ---"
  printf '%s\n' "$lint_out"
fi
if [[ -n "$details" ]]; then
  echo "--- sbt ---"
  printf '%s\n' "$details"
fi
echo "Full sbt log: ${log#"$REPO_ROOT"/}"

[[ "$overall" == PASS ]]
