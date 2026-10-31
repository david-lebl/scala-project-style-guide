#!/usr/bin/env bash
# Stop hook (main session): while /scala-implement owns this session and its run is still in an
# active phase, the controller may not end its turn. Ending is allowed once the run is `done` or
# `blocked`, or in any other session.
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../scripts/lib.sh
source "$SCRIPT_DIR/../scripts/lib.sh"

input="$(cat)"
[[ -f "$ACTIVE_RUN_FILE" ]] || exit 0

session_id="$(printf '%s' "$input" | jq -r '.session_id // empty')"
owner="$(jq -r '.sessionId // empty' "$ACTIVE_RUN_FILE" 2>/dev/null)"
[[ -n "$owner" && "$owner" == "$session_id" ]] || exit 0

tasks="$(active_run_tasks_file)"
[[ -n "$tasks" ]] || exit 0

phase="$(jq -r '.phase // empty' "$tasks")"
case "$phase" in
  implementing|final-review|docs) ;;
  *) exit 0 ;;
esac

next="$(jq -r '[.tasks[] | select(.status != "done")][0] | if . == null then "" else "\(.id) \(.title) (\(.status))" end' "$tasks")"
reason="The /scala-implement run in ${tasks#"$REPO_ROOT"/} is in phase '$phase'"
[[ -n "$next" ]] && reason+=", next unfinished task: $next"
reason+=". Re-read tasks.json and continue the controller loop. Only if you need the user's decision, set phase to \"blocked\" with blockedReason, record it in issues.md, and ask."

jq -n --arg r "$reason" '{decision: "block", reason: $r}'
exit 0
