#!/usr/bin/env bash
# watch-notify 훅 설치/해제 (docs/DESIGN.md 7, 11.5)
#   ./install.sh              설치
#   ./install.sh --uninstall  ~/.claude/settings.json 에서 훅만 제거 (config, 로그는 남김)

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="$HOME/.claude/watch-notify"
SETTINGS="$HOME/.claude/settings.json"
COMMAND="$BASE/watch-notify.sh"
EVENTS=(UserPromptSubmit Stop Notification)

for bin in jq curl; do
  command -v "$bin" >/dev/null || { echo "$bin 이 필요합니다 (brew install $bin)" >&2; exit 1; }
done

backup_settings() {
  [ -f "$SETTINGS" ] || return 0
  local bak="$SETTINGS.bak-$(date +%Y%m%d-%H%M%S)"
  cp "$SETTINGS" "$bak"
  echo "settings.json 백업: $bak"
}

# 이 스크립트가 넣은 항목만 지운다. 비어 버린 이벤트 키도 정리한다
strip_filter='
  def strip: map(.hooks |= map(select(.command != $cmd))) | map(select(.hooks | length > 0));
  if .hooks then
    .hooks |= (with_entries(.value |= strip) | with_entries(select(.value | length > 0)))
  else . end
'

if [ "${1:-}" = "--uninstall" ]; then
  [ -f "$SETTINGS" ] || { echo "settings.json 이 없습니다"; exit 0; }
  backup_settings
  tmp="$(mktemp)"
  jq --arg cmd "$COMMAND" "$strip_filter" "$SETTINGS" >"$tmp" && mv "$tmp" "$SETTINGS"
  echo "훅을 제거했습니다. $BASE 는 그대로 두었습니다."
  exit 0
fi

mkdir -p "$BASE/state"
install -m 755 "$HERE/watch-notify.sh" "$COMMAND"
echo "스크립트 설치: $COMMAND"

if [ ! -f "$BASE/config" ]; then
  install -m 600 "$HERE/config.example" "$BASE/config"
  echo "설정 템플릿 생성: $BASE/config  ← WORKER_URL, NOTIFY_SECRET 을 채우세요"
else
  chmod 600 "$BASE/config"
  echo "기존 설정 유지: $BASE/config"
fi

[ -f "$SETTINGS" ] || echo '{}' >"$SETTINGS"
backup_settings
tmp="$(mktemp)"
jq --arg cmd "$COMMAND" --argjson events "$(printf '%s\n' "${EVENTS[@]}" | jq -R . | jq -s .)" "
  $strip_filter
  | reduce \$events[] as \$e (.;
      .hooks[\$e] = ((.hooks[\$e] // []) + [{hooks: [{type: \"command\", command: \$cmd}]}]))
" "$SETTINGS" >"$tmp" && mv "$tmp" "$SETTINGS"
echo "훅 등록: ${EVENTS[*]} → $SETTINGS"
echo "새로 시작하는 Claude Code 세션부터 적용됩니다."
