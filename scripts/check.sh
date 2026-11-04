#!/usr/bin/env bash
# ClaudeWatchNotify 상태 점검 (docs/OPERATIONS.md 7절)
#   scripts/check.sh          맥 훅, Worker, 워치(adb 연결 시)를 차례로 점검
#   scripts/check.sh --send   점검 후 워치로 테스트 알림(입력 대기)을 보낸다
#
# 비밀값은 화면에 출력하지 않는다.

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/local.properties"
BASE="$HOME/.claude/watch-notify"
SETTINGS="$HOME/.claude/settings.json"
PKG="com.yunseong.claudewatch"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAILED=1; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
FAILED=0

prop() { grep "^$1=" "$PROPS" 2>/dev/null | cut -d= -f2- | tr -d '\n'; }
cfg()  { ( . "$BASE/config" 2>/dev/null; eval "printf %s \"\${$1:-}\"" ); }

echo "■ 맥 훅"
if [ -x "$BASE/watch-notify.sh" ]; then ok "스크립트 설치됨"; else bad "스크립트 없음 → hooks/install.sh"; fi
if [ -f "$BASE/config" ]; then
  perm="$(stat -f %Lp "$BASE/config")"
  [ "$perm" = 600 ] && ok "config 권한 600" || warn "config 권한 $perm (600 권장)"
  [ -n "$(cfg WORKER_URL)" ] && ok "WORKER_URL $(cfg WORKER_URL)" || bad "config에 WORKER_URL 없음"
  [ -n "$(cfg NOTIFY_SECRET)" ] && ok "NOTIFY_SECRET 설정됨" || bad "config에 NOTIFY_SECRET 없음"
  echo "    MIN_SECONDS=$(cfg MIN_SECONDS) (0이면 모두 보냄, 진동 기준은 워치에서)"
else
  bad "config 없음 → hooks/install.sh 후 값 채우기"
fi
if jq -e --arg c "$BASE/watch-notify.sh" '[.hooks // {} | .[] | .[] | .hooks[] | select(.command == $c)] | length == 3' "$SETTINGS" >/dev/null 2>&1; then
  ok "settings.json에 훅 3개 (UserPromptSubmit, Stop, Notification)"
else
  bad "settings.json 훅 누락 → hooks/install.sh"
fi
if [ -s "$BASE/notify.log" ]; then
  warn "최근 전송 실패 (notify.log):"; tail -3 "$BASE/notify.log" | sed 's/^/      /'
else
  ok "전송 실패 기록 없음"
fi

echo "■ Worker"
URL="$(cfg WORKER_URL)"; URL="${URL:-$(prop claudewatch.workerUrl)}"
if [ -n "$URL" ] && [ "$(curl -s -m 10 "$URL/health")" = '{"ok":true}' ]; then ok "health 응답 ($URL)"; else bad "health 실패 ($URL)"; fi
SECRET="$(cfg NOTIFY_SECRET)"
if [ -n "$SECRET" ]; then
  code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "$URL/notify" -H "Authorization: Bearer $SECRET" -d '{}')"
  [ "$code" = 400 ] && ok "NOTIFY_SECRET 일치 (빈 요청에 400)" || bad "NOTIFY_SECRET 확인 실패 (HTTP $code, 401이면 Worker Secret과 불일치)"
fi
if [ -d "$ROOT/worker/node_modules" ]; then
  tokens="$(cd "$ROOT/worker" && npx --no-install wrangler kv key list --binding TOKENS --remote 2>/dev/null \
    | python3 -c 'import json,sys
try:
  ks=[k for k in json.load(sys.stdin) if k["name"].startswith("device:")]
  [print("…"+k["name"][-6:], (k.get("metadata") or {}).get("device","?"), (k.get("metadata") or {}).get("registeredAt","?")) for k in ks]
except Exception: print("ERR")')"
  if [ "$tokens" = ERR ] || [ -z "${tokens// /}" ]; then
    [ "$tokens" = ERR ] && warn "KV 조회 실패 (npx wrangler login 필요할 수 있음)" || bad "등록된 워치 없음 → 워치 앱 열기"
  else
    ok "등록된 워치:"; echo "$tokens" | sed 's/^/      /'
  fi
else
  warn "worker/node_modules 없음 → KV 확인 생략 (cd worker && npm install)"
fi

echo "■ 워치 (adb)"
W="$("$ADB" devices 2>/dev/null | awk -F'\t' '$2=="device" && ($1 ~ /adb-tls-connect/ || $1 ~ /:[0-9]+$/) {print $1; exit}')"
if [ -z "$W" ]; then
  warn "adb로 연결된 워치 없음 → 워치 점검 생략 (OPERATIONS.md 8.6)"
else
  sh_() { "$ADB" -s "$W" shell "$@" 2>/dev/null; }
  echo "    기기: $W ($(sh_ getprop ro.product.model | tr -d '\r'))"
  ver="$(sh_ dumpsys package $PKG | grep -m1 versionName | tr -d ' \r')"
  [ -n "$ver" ] && ok "앱 설치됨 ($ver)" || bad "앱 없음 → ./gradlew :wear:installDebug"
  sh_ dumpsys package $PKG | grep -q "POST_NOTIFICATIONS: granted=true" && ok "알림 권한" || bad "알림 권한 없음 → 앱 열고 허용"
  reg="$(sh_ run-as $PKG cat shared_prefs/registration.xml)"
  if echo "$reg" | grep -q 'name="lastError"'; then
    bad "등록 실패: $(echo "$reg" | grep -oE 'name="lastError">[^<]*' | cut -d'>' -f2)"
  elif echo "$reg" | grep -q registeredToken; then
    ok "워커 등록됨 (토큰 …$(echo "$reg" | grep -oE 'registeredToken">[^<]*' | tail -c 7))"
  else
    warn "등록 기록 없음 → 앱 열기"
  fi
  min="$(sh_ run-as $PKG cat shared_prefs/settings.xml | grep -oE 'minSeconds" value="[0-9]+' | grep -oE '[0-9]+$')"
  echo "    진동 기준: ${min:-0}초"
  zen="$(sh_ settings get global zen_mode | tr -d '\r')"
  [ "$zen" = 0 ] && echo "    방해 금지: 꺼짐" || echo "    방해 금지: 켜짐 (진동은 ALARM이라 그래도 울림)"
  last="$(sh_ dumpsys vibrator_manager | grep "usage: ALARM | $PKG" | tail -1 | cut -c1-24 | tr -s ' ')"
  [ -n "$last" ] && echo "    마지막 진동:$last" || echo "    마지막 진동: 기록 없음 (재부팅 후 비어 있을 수 있음)"
fi

if [ "${1:-}" = "--send" ] && [ -n "$SECRET" ]; then
  echo "■ 테스트 알림"
  res="$(curl -s -m 15 -X POST "$URL/notify" -H "Authorization: Bearer $SECRET" \
    -d "{\"event\":\"Notification\",\"project\":\"check.sh\",\"session\":\"check000\",\"message\":\"점검 테스트 알림\",\"host\":\"$(hostname -s)\",\"ts\":\"$(date -u +%FT%TZ)\"}")"
  echo "$res" | grep -q '"sent":[1-9]' && ok "전송: $res (워치가 입력 대기 패턴으로 울려야 함)" || bad "전송 결과: $res"
fi

echo
[ "$FAILED" = 0 ] && echo "결과: 문제 없음" || echo "결과: ✗ 항목을 docs/OPERATIONS.md 8절에서 찾아 조치"
exit "$FAILED"
