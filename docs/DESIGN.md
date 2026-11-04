# ClaudeWatchNotify 설계 문서

맥북에서 돌아가는 Claude Code 세션이 작업을 마치거나 사용자 입력을 기다릴 때, 갤럭시 워치로 진동 알림을 보내는 시스템이다.
이 문서는 구현할 때 컨텍스트로 넣는 기준 문서다. 결정을 바꾸면 이 문서부터 고친다.

---

## 1. 목표와 범위

**목표**
- 자리를 비워도 Claude 작업이 끝난 걸 워치 진동으로 바로 안다.
- 권한 승인이나 입력을 기다리는 상황은 다른 진동 패턴으로 구분한다.
- 짧은 질답(30초 미만)에는 울리지 않는다.

**1차 범위**
- 맥 훅 스크립트 (bash + jq + curl)
- Cloudflare Worker 중계 서버 (TypeScript)
- Wear OS 단독 앱: FCM 수신, 이벤트별 진동(방해 금지 중에도), 알림 표시, 최근 기록, 토큰 등록 상태 화면

**범위 밖 (2차 이후)**: 13절 참고

---

## 2. 전체 구조

```
┌──────────────────────── MacBook ────────────────────────┐
│ Claude Code 세션들                                        │
│   UserPromptSubmit ─┐                                    │
│   Stop ─────────────┼─▶ ~/.claude/watch-notify/          │
│   Notification ─────┘      watch-notify.sh               │
│                              │ (bash + jq, 백그라운드 curl) │
└──────────────────────────────┼──────────────────────────┘
                               │ POST /notify  (Bearer NOTIFY_SECRET)
                               ▼
┌──────────────── Cloudflare Worker (workers.dev) ────────┐
│ /notify   : 페이로드 검증 → OAuth 토큰 → FCM v1 전송      │
│ /register : 워치 FCM 토큰 저장 (Bearer REGISTER_SECRET)  │
│ Secret: FCM_SERVICE_ACCOUNT, NOTIFY_SECRET,             │
│         REGISTER_SECRET                                 │
│ KV TOKENS: device:<fcmToken>, oauth:access               │
└──────────────────────────────┬──────────────────────────┘
                               │ FCM HTTP v1 (data 메시지, priority high)
                               ▼
                      Google FCM
                               │ 폰 블루투스 경유 또는 워치 Wi-Fi/LTE
                               ▼
┌──────────────── 갤럭시 워치 (Wear OS 3+) ───────────────┐
│ ClaudeMessagingService.onMessageReceived                │
│   ├─ Room에 기록 저장 (최대 10건, 3일 보관)                │
│   ├─ 알림 표시 (조용한 채널, 세션별 1개, 그룹)              │
│   └─ 진동 직접 재생 (Vibrator, usage=ALARM)               │
│ onNewToken / 앱 실행 → POST /register                    │
│ MainActivity: 등록 상태 카드 + 최근 기록 목록               │
└─────────────────────────────────────────────────────────┘
```

---

## 3. 컴포넌트별 책임

| 컴포넌트 | 위치 | 책임 | 하지 않는 것 |
|---|---|---|---|
| 맥 훅 스크립트 | `hooks/watch-notify.sh` | 훅 입력 파싱, 경과 시간 계산, 짧은 응답 거르기, 워커 호출, 실패 로그 | 재시도, FCM 직접 호출, 비밀 키 보관(NOTIFY_SECRET 제외) |
| 설치 스크립트 | `hooks/install.sh` | 스크립트 복사, 설정 파일 템플릿 생성, `~/.claude/settings.json`에 훅 등록/해제 | 워커 배포 |
| Worker | `worker/` | 인증, 페이로드 검증, OAuth 토큰 발급·캐시, FCM 전송, 무효 토큰 삭제 | 메시지 내용 저장, 재시도 |
| Wear 앱 | `wear/` | 토큰 등록, 메시지 수신, 기록 저장·정리, 알림 표시, 진동 재생, 상태 화면 | 알림 채널로 진동 울리기 (8.3 참고) |

---

## 4. 시퀀스

### 4.1 워치 토큰 등록

```
Wear 앱                    Worker                     KV
  │ onNewToken(token)        │                          │
  │ 또는 앱 실행 시 저장된 토큰과 다르면                    │
  │── POST /register ───────▶│                          │
  │   Bearer REGISTER_SECRET │── put device:<token> ───▶│
  │   {token, device}        │                          │
  │◀──────── 200 {ok} ───────│                          │
  │ SharedPreferences에 등록 토큰·시각 저장                 │
```

### 4.2 작업 완료 (Stop)

```
Claude Code        watch-notify.sh             Worker            FCM        Wear 앱
 │ 프롬프트 제출       │                          │                 │            │
 │─UserPromptSubmit─▶│ state/<session> = now    │                 │            │
 │   ... 작업 ...      │                          │                 │            │
 │─Stop─────────────▶│ elapsed = now - start    │                 │            │
 │                   │ elapsed < MIN_SECONDS? → 종료              │            │
 │                   │── POST /notify (bg) ────▶│ OAuth(캐시)      │            │
 │                   │                          │── send ────────▶│── data ───▶│
 │                   │                          │                 │            │ Room 저장 + 정리
 │                   │                          │                 │            │ 알림(조용히) + 진동(ALARM)
```

### 4.3 입력 대기 (Notification)
Stop과 같지만 경과 시간을 확인하지 않고 항상 보낸다. `message`에 Claude Code가 준 안내 문구가 들어가고, 채널은 `claude_attention_silent`, 진동은 길게 한 번이다.

---

## 5. 메시지 스키마

### 5.1 훅 → Worker (`POST /notify` 본문)

```json
{
  "event": "Stop",
  "project": "keystore",
  "session": "bd492368",
  "elapsed": "124",
  "message": "작업 완료",
  "host": "yunseong-mbp",
  "ts": "2026-11-04T09:12:33Z"
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `event` | O | `Stop` 또는 `Notification` |
| `project` | O | `cwd`의 폴더 이름 |
| `session` | O | `session_id` 앞 8자리. 워치 알림 tag로 씀 |
| `elapsed` | X | 경과 초(문자열). 시작 시각을 모르면 빈 문자열 |
| `message` | O | Stop은 `작업 완료`, Notification은 Claude Code가 준 `message` |
| `host` | O | `hostname -s` |
| `ts` | O | UTC ISO8601 |

- 모든 값은 **문자열**이다. FCM data 필드는 문자열만 받는다.
- Worker가 `message`는 200자, 나머지는 64자로 자른다.

### 5.2 Worker → FCM

```json
{
  "message": {
    "token": "<워치 FCM 토큰>",
    "data": { "...5.1의 필드 그대로..." },
    "android": { "priority": "high", "ttl": "600s" }
  }
}
```

- `notification` 필드는 **넣지 않는다**. 넣으면 앱이 백그라운드일 때 시스템이 알림을 대신 띄워서 채널과 기록 저장을 거치지 않는다.
- TTL을 10분으로 둔다. 워치가 오래 꺼져 있다가 켜졌을 때 지난 알림이 한꺼번에 오는 걸 막는다.

---

## 6. Worker API

기본 URL: `https://claude-watch-notify.<계정>.workers.dev`

| 메서드 | 경로 | 인증 | 본문 | 응답 |
|---|---|---|---|---|
| GET | `/health` | 없음 | - | `200 {"ok":true}` |
| POST | `/register` | `Bearer REGISTER_SECRET` | `{"token": "...", "device": "SM-R960"}` | `200 {"ok":true}` |
| DELETE | `/register` | `Bearer REGISTER_SECRET` | `{"token": "..."}` | `200 {"ok":true}` |
| POST | `/notify` | `Bearer NOTIFY_SECRET` | 5.1 스키마 | `200 {"sent":n,"removed":m,"errors":[...]}` |

- 인증에 실패하면 `401`, 본문이 잘못되면 `400`을 돌려준다. 등록된 토큰이 없으면 `200 {"sent":0}`이다.
- 비밀값 비교에는 `crypto.subtle.timingSafeEqual`을 쓴다.
- FCM이 `UNREGISTERED`(404)나 토큰 관련 `INVALID_ARGUMENT`(400)로 응답하면 KV에서 그 토큰을 지운다.
- 그 밖의 FCM 오류는 `errors`에 `{status, code}`로 담아 돌려준다. 재시도는 하지 않는다.

**OAuth 토큰**
- 서비스 계정 JSON으로 RS256 JWT를 만들고(WebCrypto), `https://oauth2.googleapis.com/token`에서 액세스 토큰으로 바꾼다.
- 스코프: `https://www.googleapis.com/auth/firebase.messaging`
- KV `oauth:access`에 `expirationTtl=3000`(50분)으로 캐시한다.

**KV 키**
| 키 | 값 | 메타데이터 |
|---|---|---|
| `device:<token>` | `"1"` | `{device, registeredAt}` |
| `oauth:access` | 액세스 토큰 문자열 | - |

---

## 7. 맥 훅 상세

### 7.1 파일

```
~/.claude/watch-notify/
├─ watch-notify.sh   ← hooks/watch-notify.sh 복사본
├─ config            ← chmod 600
├─ state/<session_id>  ← 프롬프트 시작 시각(epoch), 1일이 지나면 삭제
└─ notify.log        ← 실패만 기록
```

`config`:
```bash
WORKER_URL="https://claude-watch-notify.<계정>.workers.dev"
NOTIFY_SECRET="..."
MIN_SECONDS=30
```

### 7.2 `~/.claude/settings.json`에 등록하는 훅

`UserPromptSubmit`, `Stop`, `Notification` 세 이벤트에 같은 스크립트를 건다. 스크립트가 `hook_event_name`으로 동작을 나눈다.

```json
{
  "hooks": {
    "UserPromptSubmit": [{ "hooks": [{ "type": "command", "command": "~/.claude/watch-notify/watch-notify.sh" }] }],
    "Stop":             [{ "hooks": [{ "type": "command", "command": "~/.claude/watch-notify/watch-notify.sh" }] }],
    "Notification":     [{ "hooks": [{ "type": "command", "command": "~/.claude/watch-notify/watch-notify.sh" }] }]
  }
}
```

`install.sh`가 기존 설정을 백업(`settings.json.bak-<시각>`)한 뒤 jq로 합친다. 이미 등록돼 있으면 중복으로 넣지 않는다. `install.sh --uninstall`로 해제한다.

### 7.3 동작 규칙
- `config`가 없으면 아무것도 하지 않고 `exit 0`. 훅이 Claude 작업을 막으면 안 된다.
- 어떤 경우든 스크립트는 **항상 exit 0**이다. Stop 훅이 0이 아닌 코드로 끝나면 Claude 동작에 영향을 줄 수 있다.
- 전송은 `( curl ... ) &`로 백그라운드에서 하고, 훅 자체는 바로 끝낸다. curl 타임아웃은 10초다.
- 시작 시각 기록이 없으면(설치 전에 시작한 세션 등) 경과 시간을 모르므로 **보낸다**.
- HTTP 2xx가 아니거나 curl이 실패하면 `notify.log`에 `시각 event project http=코드` 한 줄을 남긴다.

---

## 8. Wear 앱 상세

### 8.1 빌드 기준
- 위치: `wear/` 모듈, 패키지 `com.yunseong.claudewatch`
- PhoneMonitor와 같은 컨벤션: 버전 카탈로그, AGP 9.3(내장 Kotlin), Kotlin 2.4, Java 17
- minSdk 30 (Wear OS 3, 갤럭시 워치4 이상), compileSdk 37, targetSdk 36
- UI: Compose for Wear OS Material3 (`androidx.wear.compose:compose-material3`)
- 의존성: Firebase Messaging(BOM), Room(KSP), kotlinx-coroutines-play-services, lifecycle
- 단독 앱: `com.google.android.wearable.standalone = true`. 폰 앱 없음
- `BuildConfig.WORKER_URL`, `BuildConfig.REGISTER_SECRET`은 `local.properties`의 `claudewatch.workerUrl`, `claudewatch.registerSecret`에서 읽는다
- `wear/google-services.json`이 있을 때만 google-services 플러그인을 적용한다. 없으면 빌드는 되지만 FCM이 동작하지 않는다

### 8.2 패키지 구조

```
com.yunseong.claudewatch
├─ ClaudeWatchApp.kt            Application: 알림 채널 생성
├─ data/
│  ├─ NotificationRecord.kt     Room Entity
│  ├─ RecordDao.kt
│  ├─ AppDatabase.kt
│  └─ RecordRepository.kt       저장 + 보관 정책 적용
├─ notify/
│  ├─ ClaudeEvent.kt            FCM data → 모델 변환
│  ├─ Channels.kt               조용한 알림 채널, 이전 채널 정리
│  ├─ Haptics.kt                이벤트별 진동 패턴 재생 (usage=ALARM)
│  ├─ NotificationPoster.kt     알림 생성, 그룹 요약
│  └─ ClaudeMessagingService.kt FirebaseMessagingService
├─ register/
│  └─ TokenRegistrar.kt         /register 호출, 등록 상태 보관
└─ ui/
   ├─ MainActivity.kt
   ├─ HomeViewModel.kt
   └─ HomeScreen.kt
```

### 8.3 알림 채널과 진동

**알림은 조용히 띄우고, 진동은 앱이 `Vibrator`로 직접 울린다.**

| 이벤트 | 채널 ID (소리·진동 없음) | 진동 패턴(ms) |
|---|---|---|
| Stop | `claude_done_silent` | `0, 150, 100, 150` (짧게 두 번) |
| Notification | `claude_attention_silent` | `0, 600` (길게 한 번) |

- 진동 usage는 **ALARM**이다. 방해 금지와 무음 모드에서도 울리고, 백그라운드 앱에서도 허용되는 usage이기 때문이다. 대가로 **취침 모드에서도 울린다.**
- 사용자는 워치의 방해 금지를 **항상 켜 둔다.** 그래서 방해 금지를 따르는 방식으로는 진동을 받을 수 없다.

**채널 진동을 쓰지 않는 이유** (갤럭시 워치7 SM-R930, Wear OS API 36에서 확인)
- 처음에는 채널 진동으로 방해 금지를 따르게 설계했다. 하지만 갤럭시 워치는 알림 진동을 **삼성 시스템 UI**(`com.samsung.android.wearable.sysui`)가 대신 울린다.
- 삼성 UI는 채널의 **진동 패턴을 무시**하고(모든 알림이 약 420ms 기본 진동), 방해 금지 중에는 채널의 **bypassDnd 설정도 무시**한다.
- 방해 금지 접근 권한을 받아 bypassDnd 채널을 만들어 봤지만 울리지 않았다. 게다가 워치에는 이 권한을 켜는 설정 화면이 없다.
- 확인 방법: `adb shell dumpsys vibrator_manager`의 진동 기록에서 `usage`와 패키지 이름을 본다.

**채널 규칙**
- 채널 설정은 만든 뒤 앱이 바꿀 수 없다. 바꾸려면 **새 채널 ID**를 만들고, 이전 ID를 `Channels.OBSOLETE`에 넣어 지운다.
- 지금 `OBSOLETE`에 들어 있는 ID: `claude_done`, `claude_attention`, `claude_done_dnd`, `claude_attention_dnd`
- 진동 패턴은 채널이 아니라 `Haptics.kt`에 있으므로, 패턴을 바꿀 때 채널 ID는 그대로 둔다.
- 알림 `tag` = `session`, `id` = 1. 같은 세션의 새 알림은 이전 알림을 대체한다.
- 모든 알림은 그룹 `claude`로 묶고, 그룹 요약 알림(id 0)을 같이 올린다.
- 제목은 `project`, 본문은 `message`(Stop이면 `작업 완료 · 2분 4초`처럼 경과 시간을 붙임), 탭하면 `MainActivity`를 연다. `autoCancel`을 켠다.
- Android 13 이상에서는 `POST_NOTIFICATIONS` 런타임 권한이 필요하다. `MainActivity`가 처음 실행될 때 요청한다.

### 8.4 기록 보관 정책
- Room 테이블 `records`: `id, event, project, session, elapsed, message, host, sentAt, receivedAt`
- **최대 10건, 받은 지 3일이 지나면 삭제**한다.
- 정리는 ① 새 기록을 저장한 직후 ② 앱을 열 때 실행한다. 별도 스케줄러는 없다.
- 정리 순서: `receivedAt < now - 3일` 삭제 → 최신 10건을 뺀 나머지 삭제

### 8.5 토큰 등록
- `onNewToken`에서 바로 `/register`를 호출한다(서비스 스레드에서 blocking).
- 앱을 열 때 `FirebaseMessaging.token`을 가져와서, 저장된 등록 토큰과 다르거나 마지막 등록이 실패했으면 다시 등록한다.
- 상태(`registeredToken`, `registeredAt`, `lastError`)는 SharedPreferences에 두고, `StateFlow`로 화면에 보여 준다.
- HTTP는 `HttpURLConnection`, JSON은 `org.json`을 쓴다. 라이브러리를 추가하지 않는다.

### 8.6 화면 (`MainActivity` 하나)

```
┌──────────────┐
│   Claude     │  ListHeader
│ ┌──────────┐ │
│ │ 등록됨 ✓  │ │  상태 카드: 등록 여부, 토큰 끝 6자리, 등록 시각
│ │ …a1b2c3   │ │  탭하면 다시 등록
│ └──────────┘ │
│ keystore     │  기록 카드 (최신순, 최대 10건)
│ 작업 완료 2분 │  제목 project, 본문 message, 시간 상대 표기
│ PhoneMonitor │  권한 대기는 다른 아이콘 또는 색으로 표시
│ 권한 승인 대기 │
└──────────────┘
```

---

## 9. 보안

| 비밀값 | 보관 위치 | 노출되면 생기는 일 |
|---|---|---|
| 서비스 계정 키 | Worker Secret `FCM_SERVICE_ACCOUNT` | Firebase 프로젝트 권한을 탈취당함. 맥이나 앱에는 두지 않는다 |
| `NOTIFY_SECRET` | 맥 `config` (600) | 워치로 스팸 알림을 보낼 수 있음 |
| `REGISTER_SECRET` | 앱 `BuildConfig` (`local.properties`) | 다른 기기를 등록해 알림 내용을 엿볼 수 있음 |

- `local.properties`, `google-services.json`, `config`, 서비스 계정 JSON은 깃에 올리지 않는다(`.gitignore`).
- 알림에는 프로젝트 이름과 Claude의 안내 문구만 실린다. 코드나 대화 내용은 보내지 않는다.
- 비밀값은 `openssl rand -hex 32`로 만든다.

---

## 10. 저장소 구조

```
ClaudeWatchNotify/
├─ docs/DESIGN.md
├─ settings.gradle.kts, build.gradle.kts, gradle.properties
├─ gradle/libs.versions.toml, gradle/wrapper/
├─ local.properties              (git 제외) sdk.dir, claudewatch.*
├─ wear/
│  ├─ build.gradle.kts
│  ├─ google-services.json       (git 제외)
│  └─ src/main/...
├─ worker/
│  ├─ package.json, tsconfig.json, wrangler.jsonc
│  └─ src/index.ts, src/fcm.ts, src/auth.ts
└─ hooks/
   ├─ watch-notify.sh
   ├─ install.sh
   └─ config.example
```

---

## 11. 초기 세팅 절차

### 11.1 비밀값 만들기
```bash
openssl rand -hex 32   # NOTIFY_SECRET
openssl rand -hex 32   # REGISTER_SECRET
```

### 11.2 Firebase
1. https://console.firebase.google.com 에서 프로젝트 `claude-watch-notify`를 만든다(Analytics는 꺼도 됨).
2. Android 앱을 추가하고 패키지명 `com.yunseong.claudewatch`를 넣는다. `google-services.json`을 받아 `wear/`에 둔다.
3. 프로젝트 설정 → 서비스 계정 → **새 비공개 키 생성**으로 JSON을 받는다. 이 파일은 Worker Secret에만 넣고 지운다.
4. 프로젝트 설정 → 클라우드 메시징에서 **Firebase Cloud Messaging API (V1)**이 사용 설정돼 있는지 확인한다.

### 11.3 Cloudflare Worker
```bash
cd worker
npm install
npx wrangler login
npx wrangler kv namespace create TOKENS        # 나온 id를 wrangler.jsonc에 넣는다
npx wrangler secret put FCM_SERVICE_ACCOUNT < ~/Downloads/<서비스계정>.json
npx wrangler secret put NOTIFY_SECRET
npx wrangler secret put REGISTER_SECRET
npx wrangler deploy                             # 나온 URL을 기록
curl https://claude-watch-notify.<계정>.workers.dev/health
```

### 11.4 워치 앱 빌드와 설치
1. `local.properties`에 추가:
   ```
   claudewatch.workerUrl=https://claude-watch-notify.<계정>.workers.dev
   claudewatch.registerSecret=<REGISTER_SECRET>
   ```
2. 워치: 설정 → 워치 정보 → 소프트웨어 정보 → **소프트웨어 버전**을 7번 탭해서 개발자 옵션을 켠다. 개발자 옵션에서 **ADB 디버깅**과 **무선 디버깅**을 켠다(워치와 맥이 같은 Wi-Fi에 있어야 함).
3. 무선 디버깅 → **새 기기 페어링**에 나온 IP:포트와 코드로 페어링하고 연결한다.
   ```bash
   adb pair <IP>:<페어링포트>     # 코드 입력
   adb connect <IP>:<연결포트>
   ./gradlew :wear:installDebug
   ```
4. 워치에서 앱을 열고 알림 권한을 허용한 뒤, 상태 카드에 **등록됨**이 뜨는지 확인한다.

### 11.5 맥 훅 설치
```bash
cd hooks
./install.sh                         # 스크립트 복사, config 템플릿 생성, settings.json에 훅 등록
vi ~/.claude/watch-notify/config     # WORKER_URL, NOTIFY_SECRET 입력
# 테스트
echo '{"hook_event_name":"Notification","session_id":"test0000","cwd":"/tmp/demo","message":"테스트 알림"}' \
  | ~/.claude/watch-notify/watch-notify.sh
```
훅 설정은 새로 시작하는 Claude Code 세션부터 적용된다.

---

## 12. 실패 처리와 제약

| 상황 | 동작 |
|---|---|
| 맥이 오프라인 | curl이 실패하고 `notify.log`에 기록. 재시도 없음 |
| Worker 오류 | 응답 코드를 `notify.log`에 기록 |
| 워치 토큰 만료 | FCM이 UNREGISTERED를 돌려주면 Worker가 토큰을 삭제. 워치는 `onNewToken`이나 앱을 열 때 다시 등록 |
| 워치가 꺼져 있음 | FCM이 TTL 10분 동안 보관했다가 전달. 그 뒤에는 버림 |
| 워치 앱 강제 종료 | 앱을 다시 열기 전까지 FCM 메시지를 받지 못함(안드로이드 정책) |
| 방해 금지 / 취침 모드 | 진동은 울린다(usage=ALARM). 알림 팝업은 워치 설정에 따라 안 뜰 수 있지만 알림 목록과 기록에는 남음 |
| 짧은 응답 | Stop이면서 경과 시간이 `MIN_SECONDS` 미만이면 보내지 않음 |

**알려진 제약**
- Stop 훅은 응답(턴)이 끝날 때마다 실행된다. 긴 작업 하나가 여러 턴으로 나뉘면 알림도 여러 번 온다.
- 여러 세션이 동시에 끝나면 세션마다 진동이 한 번씩 울린다. 묶어서 보내지는 않는다.
- Cloudflare 무료 플랜의 KV 쓰기는 하루 1,000회다. OAuth 캐시 갱신(하루 최대 약 29회)과 등록만 쓰기를 하므로 충분하다.

---

## 13. 향후 범위 (2차)
- 타일과 컴플리케이션: 마지막 완료 프로젝트, 실행 중인 세션 수
- 폰 등 여러 기기로 동시 전송 (KV에 토큰 여러 개가 이미 가능한 구조)
- 워치에서 응답 보내기: 권한 승인이나 거절 (Worker → 맥 역방향 채널 필요)

---

## 14. 결정 기록

| 결정 | 이유 |
|---|---|
| ntfy 대신 직접 만든 워치 앱 + FCM | 이벤트별 진동 패턴과 기록 화면이 필요하고, 폰 알림 미러링 설정에 영향받지 않기 위해 |
| 로컬 토큰 캐시 대신 Cloudflare Worker 중계 | 서비스 계정 키를 맥에 두지 않고, 맥 쪽 의존성을 curl 하나로 줄이기 위해 |
| 파이썬 없이 bash + jq + curl | 훅이 매번 실행되므로 시작 비용을 줄이고, jq는 이미 설치돼 있음 |
| 짧은 응답 판정에 UserPromptSubmit 기록 사용 | Stop 훅 입력에 소요 시간이 없음 |
| ~~`Vibrator` 대신 알림 채널 진동~~ → **`Vibrator` 직접 사용 (usage=ALARM)** | 방해 금지를 항상 켜 두는 환경이고, 갤럭시 워치는 채널 진동 패턴과 bypassDnd를 무시함 (8.3). 2026-11-04 실기기 테스트 후 변경 |
| data 메시지만 사용 | 앱이 백그라운드일 때도 `onMessageReceived`가 호출되도록 |
| 기록 10건, 3일 보관 | 워치에서 최근 상황만 보면 됨 |
| 토큰 자동 등록 | FCM 토큰이 바뀌어도 알림이 끊기지 않게 |
| 비밀값 두 개 분리 | 맥 설정이 새도 기기 등록은 못 하게, 앱이 분석돼도 알림 발송은 못 하게 |
