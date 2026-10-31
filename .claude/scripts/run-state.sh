#!/usr/bin/env bash
# State machine of a /scala-implement run, stored in <changeDir>/tasks.json.
# The controller asks `next` what to do and records every transition through this script,
# so the loop is driven by the file rather than by the conversation.
#
# Usage: run-state.sh <changeDir> <command> [args]
#   next                              print the next step (see below)
#   show                              compact status table
#   phase <phase> [reason]            set phase: approved|implementing|final-review|docs|done|blocked
#   task <Tn> status <status>         pending|in-progress|review|fixing|done|blocked
#   task <Tn> round                   increment the task's fix round, print the new value
#   task <Tn> commit <sha>            record a commit for the task
#   park <Tn|final> <findingJson>     record an open finding (must carry "ruling")
#   final round                       start the next final-review round, print its number
#   final record <jsonSummary>        append a summary object for the current final-review round
#   final complete <reason>           mark the final review finished
#   docs done                         mark the closing documents written
#
# `next` prints one of:
#   NOT_APPROVED | BLOCKED <reason> | PREFLIGHT | TASK <Tn> <status> <fixRound>/<max>
#   FINAL_REVIEW <round>/<max> | FINAL_REVIEW_EXHAUSTED | DOCS | DONE
set -o pipefail

dir="${1:-}"; cmd="${2:-}"
[[ -n "$dir" && -n "$cmd" ]] || { sed -n '2,22p' "$0"; exit 2; }
file="$dir/tasks.json"
[[ -f "$file" ]] || { echo "no $file" >&2; exit 2; }
shift 2

update() { # update <jq-filter> [jq args...]
  local filter="$1" tmp
  shift
  tmp="$(mktemp)"
  if jq "$@" "$filter" "$file" >"$tmp"; then mv "$tmp" "$file"; else rm -f "$tmp"; exit 1; fi
}

require_task() {
  jq -e --arg t "$1" 'any(.tasks[]; .id == $t)' "$file" >/dev/null || { echo "unknown task $1" >&2; exit 2; }
}

case "$cmd" in
  next)
    jq -r '
      def maxTask: .limits.taskFixRounds // 2;
      def maxFinal: .limits.finalReviewRounds // 3;
      if (.approved | not) then "NOT_APPROVED"
      elif .phase == "blocked" then "BLOCKED \(.blockedReason // "no reason recorded")"
      elif .phase == "done" then "DONE"
      elif .phase == "approved" then "PREFLIGHT"
      elif any(.tasks[]; .status != "done") then
        (first(.tasks[] | select(.status != "done")) as $t
         | "TASK \($t.id) \($t.status) \($t.fixRounds // 0)/\(maxTask)")
      elif (.finalReview.status // "pending") != "done" then
        (if (.finalReview.round // 0) >= maxFinal then "FINAL_REVIEW_EXHAUSTED"
         else "FINAL_REVIEW \((.finalReview.round // 0) + 1)/\(maxFinal)" end)
      elif (.docs.status // "pending") != "done" then "DOCS"
      else "DONE" end' "$file"
    ;;
  show)
    jq -r '"\(.slug) | phase: \(.phase) | branch: \(.branch // "-") | base: \((.baseCommit // "-")[0:12])",
           (.tasks[] | "  \(.id) [\(.status)] rounds=\(.fixRounds // 0) commits=\(.commits | length) open=\(.openFindings | length)  \(.title)"),
           "  final review: \(.finalReview.status // "pending"), round \(.finalReview.round // 0), open=\(.finalReview.openFindings // [] | length)",
           "  docs: \(.docs.status // "pending")"' "$file"
    ;;
  phase)
    phase="${1:-}"; reason="${2:-}"
    case "$phase" in approved|implementing|final-review|docs|done|blocked) ;; *) echo "bad phase '$phase'" >&2; exit 2 ;; esac
    update '.phase = $p | .blockedReason = (if $p == "blocked" then $r else null end)' --arg p "$phase" --arg r "$reason"
    ;;
  task)
    t="${1:-}"; sub="${2:-}"; require_task "$t"
    case "$sub" in
      status)
        s="${3:-}"
        case "$s" in pending|in-progress|review|fixing|done|blocked) ;; *) echo "bad status '$s'" >&2; exit 2 ;; esac
        update '(.tasks[] | select(.id == $t)).status = $s' --arg t "$t" --arg s "$s" ;;
      round)
        update '(.tasks[] | select(.id == $t)).fixRounds += 1' --arg t "$t"
        jq -r --arg t "$t" '.tasks[] | select(.id == $t) | .fixRounds' "$file" ;;
      commit)
        sha="${3:-}"; [[ -n "$sha" ]] || { echo "missing sha" >&2; exit 2; }
        update '(.tasks[] | select(.id == $t)).commits += [$c]' --arg t "$t" --arg c "$sha" ;;
      *) echo "unknown task subcommand '$sub'" >&2; exit 2 ;;
    esac
    ;;
  park)
    scope="${1:-}"; finding="${2:-}"
    printf '%s' "$finding" | jq -e 'type == "object" and has("id") and has("ruling")' >/dev/null \
      || { echo "finding must be a JSON object with id and ruling" >&2; exit 2; }
    if [[ "$scope" == final ]]; then
      update '.finalReview.openFindings = ((.finalReview.openFindings // []) + [$f])' --argjson f "$finding"
    else
      require_task "$scope"
      update '(.tasks[] | select(.id == $t)).openFindings += [$f]' --arg t "$scope" --argjson f "$finding"
    fi
    ;;
  final)
    sub="${1:-}"
    case "$sub" in
      round)
        update '.finalReview.round = ((.finalReview.round // 0) + 1) | .finalReview.status = "in-progress"'
        jq -r '.finalReview.round' "$file" ;;
      record)
        summary="${2:-}"
        update '.finalReview.rounds = ((.finalReview.rounds // []) + [$s + {round: .finalReview.round}])' --argjson s "$summary" ;;
      complete)
        update '.finalReview.status = "done" | .finalReview.stopReason = $r' --arg r "${2:-}" ;;
      *) echo "unknown final subcommand '$sub'" >&2; exit 2 ;;
    esac
    ;;
  docs)
    [[ "${1:-}" == done ]] || { echo "usage: docs done" >&2; exit 2; }
    update '.docs.status = "done"'
    ;;
  *) echo "unknown command '$cmd'" >&2; exit 2 ;;
esac
