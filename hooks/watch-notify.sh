#!/usr/bin/env bash
# Claude Code 훅 → Cloudflare Worker → FCM → 갤럭시 워치
# UserPromptSubmit / Stop / Notification 세 이벤트에 같은 스크립트를 건다. (docs/DESIGN.md 7)
# 어떤 경우에도 exit 0: 훅이 Claude 작업을 막으면 안 된다.

set -u

BASE="${CLAUDE_WATCH_NOTIFY_HOME:-$HOME/.claude/watch-notify}"
CONFIG="$BASE/config"
STATE="$BASE/state"
LOG="$BASE/notify.log"

[ -f "$CONFIG" ] || exit 0
# shellcheck source=/dev/null
. "$CONFIG"
[ -n "${WORKER_URL:-}" ] && [ -n "${NOTIFY_SECRET:-}" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

input="$(cat)"
event="$(jq -r '.hook_event_name // empty' <<<"$input" 2>/dev/null)" || exit 0
session="$(jq -r '.session_id // "unknown"' <<<"$input")"
cwd="$(jq -r '.cwd // empty' <<<"$input")"
now="$(date +%s)"

mkdir -p "$STATE" 2>/dev/null
# 세션 ID를 파일 이름으로 쓰므로 경로 문자를 걸러 낸다
state_file="$STATE/${session//[^A-Za-z0-9_-]/_}"

case "$event" in
  UserPromptSubmit)
    echo "$now" >"$state_file" 2>/dev/null
    # 하루 넘은 세션 기록 정리
    find "$STATE" -type f -mtime +1 -delete 2>/dev/null
    exit 0
    ;;
  Stop)
    elapsed=""
    start="$(cat "$state_file" 2>/dev/null || true)"
    if [[ "$start" =~ ^[0-9]+$ ]]; then
      elapsed=$((now - start))
      # 짧은 질답에는 울리지 않는다. 시작 시각을 모르면 보낸다
      [ "$elapsed" -lt "${MIN_SECONDS:-30}" ] && exit 0
    fi
    message="작업 완료"
    ;;
  Notification)
    elapsed=""
    message="$(jq -r '.message // "입력을 기다리는 중"' <<<"$input")"
    ;;
  *)
    exit 0
    ;;
esac

project="$(basename "${cwd:-unknown}")"
payload="$(jq -n \
  --arg event "$event" \
  --arg project "$project" \
  --arg session "${session:0:8}" \
  --arg elapsed "$elapsed" \
  --arg message "$message" \
  --arg host "$(hostname -s)" \
  --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{event:$event, project:$project, session:$session, elapsed:$elapsed, message:$message, host:$host, ts:$ts}')"

# 백그라운드로 보내고 훅은 바로 끝낸다. 실패만 로그에 남기고 재시도는 하지 않는다
(
  code="$(curl -sS -m 10 -o /dev/null -w '%{http_code}' \
    -X POST "${WORKER_URL%/}/notify" \
    -H "Authorization: Bearer $NOTIFY_SECRET" \
    -H 'Content-Type: application/json' \
    --data "$payload" 2>/dev/null)"
  case "$code" in
    2??) ;;
    *) echo "$(date '+%F %T') $event $project http=${code:-curl_failed}" >>"$LOG" ;;
  esac
) </dev/null >/dev/null 2>&1 &
disown 2>/dev/null

exit 0
