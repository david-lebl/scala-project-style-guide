#!/usr/bin/env bash
# Stop hook for the scala-implementer and scala-fixer subagents (Claude Code runs it as SubagentStop).
# A subagent that reports success may only finish once all quality gates pass; the gate summary
# goes back to it as its next instruction. A subagent reporting BLOCKED or NEEDS_CONTEXT may stop.
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

input="$(cat)"
last="$(printf '%s' "$input" | jq -r '.last_assistant_message // empty')"
if printf '%s' "$last" | grep -qE 'STATUS:[[:space:]]*(BLOCKED|NEEDS_CONTEXT)'; then
  exit 0
fi

summary="$("$SCRIPT_DIR/../scripts/scala-gates.sh" 2>&1)"
status=$?
[[ $status -eq 0 ]] && exit 0

if [[ $status -ge 2 ]]; then
  # The gates could not run (configuration problem): don't trap the subagent, surface it instead.
  echo "scala-gates could not run: $summary" >&2
  exit 0
fi

jq -n --arg s "$summary" '{
  decision: "block",
  reason: ("Quality gates failed. Fix the problems below, then finish again. Do not weaken tests, delete checks or add @nowarn to get green. If the failure is outside your task or you cannot fix it, end with STATUS: BLOCKED and explain why.\n\n" + $s)
}'
exit 0
