# Shared helpers for the Scala workflow scripts and hooks. Source, don't execute.

repo_root() {
  if [[ -n "${CLAUDE_PROJECT_DIR:-}" ]]; then
    printf '%s\n' "$CLAUDE_PROJECT_DIR"
  else
    git rev-parse --show-toplevel
  fi
}

REPO_ROOT="$(repo_root)"

# Defaults, overridden by .claude/scala-workflow.env.
SBT_ROOT=.
SBT_CMD="sbt -batch -no-colors"
APP_DIRS="app"
REQUIRE_TESTS=1

if [[ -f "$REPO_ROOT/.claude/scala-workflow.env" ]]; then
  # shellcheck source=/dev/null
  source "$REPO_ROOT/.claude/scala-workflow.env"
fi

SBT_DIR="$REPO_ROOT/$SBT_ROOT"
STATE_DIR="$REPO_ROOT/.claude/state"
ACTIVE_RUN_FILE="$STATE_DIR/active-run.json"

# Prints the tasks.json path of the active run, or nothing.
active_run_tasks_file() {
  [[ -f "$ACTIVE_RUN_FILE" ]] || return 0
  local slug_dir
  slug_dir="$(jq -r '.changeDir // empty' "$ACTIVE_RUN_FILE" 2>/dev/null)"
  [[ -n "$slug_dir" && -f "$REPO_ROOT/$slug_dir/tasks.json" ]] && printf '%s\n' "$REPO_ROOT/$slug_dir/tasks.json"
  return 0
}

# Prints the base commit of the active run, or nothing.
active_run_base() {
  local tasks
  tasks="$(active_run_tasks_file)"
  [[ -n "$tasks" ]] && jq -r '.baseCommit // empty' "$tasks"
  return 0
}

strip_ansi() {
  sed -E $'s/\x1b\\[[0-9;]*[mK]//g'
}
