#!/usr/bin/env bash
# PreToolUse hook (Bash): the agent workflow never publishes anything. Blocks git push, remote
# changes and GitHub write operations, in the main session and in every subagent.
# Local commits stay allowed. Push yourself from your own terminal after human review.
set -o pipefail

cmd="$(jq -r '.tool_input.command // empty')"
[[ -z "$cmd" ]] && exit 0

# Split compound commands so `cd x && git push` and `git status; git push` are caught.
segments="$(printf '%s\n' "$cmd" | sed -E 's/(\&\&|\|\||;|\|)/\n/g')"

blocked=""
while IFS= read -r seg; do
  seg="$(printf '%s' "$seg" | sed -E 's/^[[:space:]]*(env [^ ]+ |[A-Z_]+=[^ ]+ )*//')"
  if [[ "$seg" =~ (^|[[:space:]/])git([[:space:]]+-[^[:space:]]+([[:space:]]+[^-[:space:]][^[:space:]]*)?)*[[:space:]]+push([[:space:]]|$) ]]; then
    blocked="git push"
  elif [[ "$seg" =~ (^|[[:space:]/])git[[:space:]].*remote[[:space:]]+(add|set-url|rename|remove|rm)([[:space:]]|$) ]]; then
    blocked="git remote modification"
  elif [[ "$seg" =~ (^|[[:space:]/])gh[[:space:]]+(pr|issue)[[:space:]]+(create|merge|close|reopen|edit|comment|review|ready|lock|delete)([[:space:]]|$) ]]; then
    blocked="GitHub write operation (gh ${BASH_REMATCH[2]} ${BASH_REMATCH[3]})"
  elif [[ "$seg" =~ (^|[[:space:]/])gh[[:space:]]+(release|repo[[:space:]]+(create|delete|edit|fork|sync|rename))([[:space:]]|$) ]]; then
    blocked="GitHub write operation (gh ${BASH_REMATCH[2]})"
  elif [[ "$seg" =~ (^|[[:space:]/])gh[[:space:]]+api([[:space:]]|$) && "$seg" =~ [[:space:]](-X|--method|-f|-F|--field|--raw-field|--input)([[:space:]=]|$) ]]; then
    blocked="GitHub API write (gh api with a method or fields)"
  fi
  [[ -n "$blocked" ]] && break
done <<<"$segments"

if [[ -n "$blocked" ]]; then
  echo "Blocked by .claude/hooks/block-remote-git.sh: $blocked is not allowed. This workflow only commits locally; the user pushes and opens the PR after human review." >&2
  exit 2
fi
exit 0
