# ClaudeWatchNotify 운영 가이드

인프라 구조, 동작 흐름, 세팅과 재배포, 테스트, 장애 대응을 정리한 문서다.
설계 결정과 그 이유는 [DESIGN.md](DESIGN.md)에 있다. 이 문서는 "어떻게 운영하나"만 다룬다.

> 공개 저장소이므로 Worker URL, 계정 이메일, 계정 ID 같은 값은 적지 않는다. 각 값을 어디서 확인하는지만 적는다.

---

## 1. 한눈에 보기

```
 맥 (Claude Code)                Cloudflare                  Google            갤럭시 워치
┌──────────────────┐  HTTPS   ┌────────────────────┐  HTTPS ┌─────────┐      ┌──────────────────┐
│ 훅 3개            │ ───────▶ │ Worker              │ ─────▶ │ FCM v1  │ ───▶ │ Claude Watch 앱   │
│ watch-notify.sh  │ /notify  │ claude-watch-notify │        └─────────┘      │ 알림 + 진동(ALARM) │
└──────────────────┘          │ KV: TOKENS          │ ◀───────────────────── │ /register        │
                              └────────────────────┘    워치가 토큰 등록       └──────────────────┘
```

| 구성 요소 | 위치 | 역할 |
|---|---|---|
| 맥 훅 | `~/.claude/watch-notify/` (원본 `hooks/`) | Claude 이벤트를 Worker로 전송 |
| Worker | Cloudflare Workers `claude-watch-notify` (원본 `worker/`) | 인증, Google OAuth, FCM 전송, 토큰 관리 |
| KV | Cloudflare KV `TOKENS` (id는 `worker/wrangler.jsonc`) | 워치 FCM 토큰, OAuth 토큰 캐시 |
| Firebase | 프로젝트 `claude-watch-notify` | FCM 발송 권한, 앱 등록(`google-services.json`) |
| 워치 앱 | 갤럭시 워치7 SM-R930 (원본 `wear/`) | 수신, 기록, 진동, 상태 화면 |

---

## 2. 인프라와 리소스

### 2.1 리소스 목록

| 리소스 | 이름 / 값 | 확인하는 곳 |
|---|---|---|
| Cloudflare 계정 | 무료 플랜 | `cd worker && npx wrangler whoami` |
| Worker | `claude-watch-notify` | Cloudflare 대시보드 → Workers & Pages |
| Worker URL | `https://claude-watch-notify.<서브도메인>.workers.dev` | `local.properties`의 `claudewatch.workerUrl`, 배포 출력 |
| KV 네임스페이스 | `TOKENS` | `worker/wrangler.jsonc`, `npx wrangler kv namespace list` |
| Worker Secret | `FCM_SERVICE_ACCOUNT`, `NOTIFY_SECRET`, `REGISTER_SECRET` | `npx wrangler secret list` (값은 볼 수 없음) |
| Firebase 프로젝트 | `claude-watch-notify` | https://console.firebase.google.com |
| Firebase 서비스 계정 | `firebase-adminsdk-…@claude-watch-notify.iam.gserviceaccount.com` | Firebase 프로젝트 설정 → 서비스 계정 |
| GitHub | `oyunseong/ClaudeWatchNotify` (공개) | |

### 2.2 비밀값이 있는 곳

| 비밀값 | 원본 (맥, git 제외) | 실제로 쓰는 곳 |
|---|---|---|
| `NOTIFY_SECRET` | `local.properties` `claudewatch.notifySecret` | 맥 `~/.claude/watch-notify/config`, Worker Secret |
| `REGISTER_SECRET` | `local.properties` `claudewatch.registerSecret` | 워치 앱 `BuildConfig`(빌드할 때 주입), Worker Secret |
| 서비스 계정 키 | **보관하지 않음** (Worker Secret에만 있음) | Worker Secret `FCM_SERVICE_ACCOUNT` |
| `google-services.json` | `wear/google-services.json` | 워치 앱 빌드 |

- `local.properties`가 맥에 있는 유일한 원본이다. **다른 곳에 백업해 두면** 맥을 바꿔도 복구가 쉽다(비밀번호 관리자 권장).
- 서비스 계정 키를 잃어버려도 문제없다. Firebase에서 새로 발급받아 Secret만 바꾸면 된다(6.4).

### 2.3 비용
모두 무료 범위다. Workers는 하루 10만 요청, KV 쓰기는 하루 1,000회까지 무료이고, 실제 사용량은 하루 수십~수백 요청 수준이다. FCM은 무료다.

---

## 3. 동작 흐름

### 3.1 알림 한 건이 워치에 닿기까지

| 단계 | 어디서 | 무엇을 | 실패하면 |
|---|---|---|---|
| ① | Claude Code | 프롬프트 제출 → `UserPromptSubmit` 훅이 세션 시작 시각을 `state/<session_id>`에 기록 | 경과 시간만 빈 값이 됨 |
| ② | Claude Code | 응답 종료 → `Stop` 훅, 입력 대기 → `Notification` 훅 | 훅이 없으면 아무 일도 없음 |
| ③ | `watch-notify.sh` | 경과 시간 계산, JSON을 만들어 **백그라운드 curl**로 `POST /notify`. 훅은 바로 끝남 | `notify.log`에 기록, 재시도 없음 |
| ④ | Worker | `NOTIFY_SECRET` 확인 → 필드 정리(문자열, 길이 제한) → KV의 워치 토큰 목록 조회 | 401 / 400 / 500 응답 |
| ⑤ | Worker | KV에 캐시된 OAuth 토큰을 쓰거나, 서비스 계정으로 JWT를 서명해 새로 발급(50분 캐시) | 500 (OAuth 실패) |
| ⑥ | Worker → FCM | 토큰마다 data 메시지 전송 (priority high, TTL 10분) | 무효 토큰은 KV에서 삭제, 그 밖의 오류는 `errors`에 담김 |
| ⑦ | FCM → 워치 | 폰 블루투스나 워치 Wi-Fi로 전달 | 워치가 10분 넘게 오프라인이면 버려짐 |
| ⑧ | 워치 앱 | `onMessageReceived` → Room에 기록(최대 10건, 3일) → 조용한 알림 표시 | |
| ⑨ | 워치 앱 | 진동 판단: 음소거 프로젝트 → 조용한 시간 → 이벤트별 켜기 → (완료면) 기준 시간. 통과하면 고른 패턴·세기로 `Vibrator`(usage=ALARM) | 조건에 걸리면 진동만 생략 |
| ⑩ | 워치 앱 | 입력 대기면(반복 알림 켜짐) 간격 뒤 다시 진동 예약, 같은 세션 완료가 오면 취소. 오늘 통계 갱신, 타일 갱신 | |

### 3.2 워치 토큰 등록

```
앱 실행 / onNewToken ──▶ FCM 토큰 확인 ──(바뀜·실패 이력)──▶ POST /register ──▶ KV device:<token>
                                                     │실패
                                                     ▼
                                    RegisterRetryWorker (네트워크 연결 시, 최대 8회 백오프)
```

- 토큰은 앱 재설치, 앱 데이터 삭제, FCM 갱신 때 바뀐다. 바뀌면 자동으로 다시 등록한다.
- 옛 토큰은 다음 `/notify` 때 FCM이 무효로 판정하면 Worker가 지운다(`removed` 카운트).

### 3.3 진동 정책 요약
- 진동은 **알람 용도**라 방해 금지, 무음, 취침 모드에서도 울린다. 사용자가 방해 금지를 항상 켜 두기 때문이다. 밤에 울리지 않게 하려면 앱의 **조용한 시간**을 켠다.
- 워치 앱에서 바꾸는 것: 진동 기준(탭 0/5/10/30초/1분, 길게 눌러 1~60분), 세기, 이벤트별 패턴, 이벤트별 켜기, 조용한 시간, 입력 대기 반복 알림, 프로젝트 음소거. 자세한 규칙은 DESIGN.md 8.7, 8.9~8.11.
- 맥의 `MIN_SECONDS`는 0으로 둔다. 0이 아니면 그보다 짧은 작업은 맥에서부터 보내지 않아 워치 기록에도 남지 않는다.
- 설정은 워치 앱 데이터(`shared_prefs/settings.xml`)에 있다. 앱을 지우면 기본값으로 돌아간다.

---

## 4. 처음부터 세팅하기

상황에 맞는 절만 따라 한다. 자세한 클릭 경로는 DESIGN.md 11절에 있다.

### 4.1 새 맥에 세팅 (Worker·Firebase는 그대로)
1. 저장소 clone, `local.properties` 복원 (`sdk.dir`, `claudewatch.*` 세 줄)
2. `wear/google-services.json` 복원 (없으면 Firebase 콘솔 → 프로젝트 설정 → 내 앱에서 다시 받기)
3. 맥 훅 설치:
   ```bash
   hooks/install.sh
   # ~/.claude/watch-notify/config 에 WORKER_URL, NOTIFY_SECRET(local.properties 값) 입력, MIN_SECONDS=0
   ```
4. Worker 관리가 필요하면: `cd worker && npm install && npx wrangler login`
5. 점검: `scripts/check.sh --send`

### 4.2 새 워치에 설치 (또는 워치 초기화 후)
1. 워치 개발자 옵션 → ADB 디버깅, 무선 디버깅 켜기 → **새 기기 페어링**
2. 맥에서:
   ```bash
   adb pair <IP>:<페어링포트>      # 6자리 코드 입력
   adb connect <IP>:<연결포트>     # 이후로는 mDNS로 자동 연결됨
   ANDROID_SERIAL=<기기> ./gradlew :wear:installDebug
   adb -s <기기> shell pm grant com.yunseong.claudewatch android.permission.WRITE_SECURE_SETTINGS   # 무선 디버깅 토글용
   ```
3. 워치에서 앱을 열고 **알림 허용** → "알림 수신 중"이 뜨는지 확인
4. 워치 설정 → 애플리케이션 → Claude Watch → **"사용하지 않으면 앱 활동 일시중지" 끄기** (8.5 참고)
5. (선택) 타일 추가: 워치 화면을 길게 눌러 타일 편집 → "Claude 상태" 추가
6. `scripts/check.sh --send` → 입력 대기 패턴으로 진동하면 끝

### 4.3 전부 새로 (Firebase·Cloudflare까지)
DESIGN.md 11절 순서대로 한다: 비밀값 생성 → Firebase 프로젝트·앱·서비스 계정 키 → Cloudflare 로그인·KV 생성(`wrangler.jsonc`에 id 반영)·배포·Secret 3개 → `local.properties`에 URL → 워치 설치(4.2) → 맥 훅(4.1). 서비스 계정 키 파일은 Secret 등록 후 지운다.

---

## 5. 변경하고 다시 배포하기

### 5.1 테스트

| 테스트 | 명령 | 내용 | 기기 |
|---|---|---|---|
| 로직 단위 테스트 | `./gradlew :wear:testDebugUnitTest` | FCM data 변환, 진동 판단(기준·조용한 시간·음소거·이벤트별), 프리셋 순환, 패턴 데이터, 시간 표기, 알림 본문 (12개) | 불필요 |
| 보관 정책 테스트 | `ANDROID_SERIAL=<기기> ./gradlew :wear:connectedDebugAndroidTest` | 10건 유지, 3일 삭제 (메모리 DB) | 워치 |
| Worker 타입 검사 | `cd worker && npx tsc --noEmit` | | 불필요 |
| Worker 로컬 실행 | `cd worker && npx wrangler dev` (`.dev.vars` 필요, `.dev.vars.example` 참고) | 로컬 KV로 API 시험 | 불필요 |
| 전체 점검 | `scripts/check.sh --send` | 훅, Worker, 워치 상태와 실제 진동 | 워치(선택) |

> ⚠️ **`connectedDebugAndroidTest`는 끝날 때 앱을 지운다.** 그러면 FCM 토큰, 기록, 설정, adb로 준 권한이 모두 사라진다. 실행한 뒤에는 4.2의 2~3단계(설치, 권한, 앱 열기)를 다시 하거나, 처음부터 다음처럼 앱을 남기고 실행한다:
> `./gradlew :wear:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`

### 5.2 워치 앱 업데이트
```bash
adb devices                                   # 워치 이름 확인 (이름 끝에 " (2)" 가 붙을 수 있음)
ANDROID_SERIAL="<기기>" ./gradlew :wear:installDebug
```
- 같은 서명(debug 키)으로 덮어쓰면 데이터와 권한, 토큰이 유지된다.
- 설치하면 앱 프로세스가 종료된다. **워치에서 앱을 한 번 열어 두면** 바로 다시 연결된다(열지 않아도 다음 FCM 메시지 때 살아남).
- 채널 설정을 바꿨다면 새 채널 ID를 만들고 이전 ID를 `Channels.OBSOLETE`에 넣는다(DESIGN.md 8.3).
- 연결이 안 되면 워치 앱의 **개발 → 무선 디버깅** 스위치를 켠다(8.6).

### 5.3 Worker 변경 배포
```bash
cd worker
npx tsc --noEmit
npx wrangler deploy
curl https://<worker-url>/health          # {"ok":true}
../scripts/check.sh --send
```
- 배포는 몇 초 안에 전 세계에 반영되고, 중단 없이 교체된다.
- 되돌리기: `npx wrangler rollback` (직전 버전), 버전 목록은 `npx wrangler deployments list`
- 실시간 로그: `npx wrangler tail` (요청과 예외를 바로 볼 수 있음)

### 5.4 맥 훅 변경
```bash
hooks/install.sh        # 스크립트를 다시 복사한다. config와 기존 훅은 유지
```
훅 설정(`settings.json`) 변경은 **새로 여는 Claude Code 세션부터** 적용된다. 스크립트 내용 변경은 바로 적용된다.

### 5.5 커밋과 push
- 비밀값 파일은 `.gitignore`로 막혀 있다. 커밋 전에 `git status`로 한 번 더 본다.
- 이 저장소는 **공개**다. 문서에 Worker URL, 계정 정보를 적지 않는다.

---

## 6. 비밀값 관리와 교체

### 6.1 `NOTIFY_SECRET` 교체 (맥 설정이 샜을 때)
```bash
NEW=$(openssl rand -hex 32)
# 1) local.properties 의 claudewatch.notifySecret 값을 $NEW 로
# 2) Worker
cd worker && printf %s "$NEW" | npx wrangler secret put NOTIFY_SECRET
# 3) 맥 config
sed -i '' "s/^NOTIFY_SECRET=.*/NOTIFY_SECRET=\"$NEW\"/" ~/.claude/watch-notify/config
../scripts/check.sh
```
Secret을 바꾸는 순간부터 3)을 마칠 때까지 보내는 알림은 401로 실패한다.

### 6.2 `REGISTER_SECRET` 교체 (앱 APK가 샜을 때)
1. 새 값을 `local.properties`의 `claudewatch.registerSecret`에 넣는다.
2. `printf %s "<새 값>" | npx wrangler secret put REGISTER_SECRET`
3. 워치 앱을 다시 빌드해 설치하고, 앱을 열어 재등록한다.
4. 모르는 기기가 등록돼 있으면 지운다: `npx wrangler kv key list --binding TOKENS --remote`로 보고 `npx wrangler kv key delete --binding TOKENS --remote "device:<토큰>"`.

### 6.3 Worker URL 노출
URL만으로는 아무것도 할 수 없다(비밀값 필요). 대량 요청이 오면 Cloudflare 대시보드 → Workers → 해당 Worker → 설정에서 workers.dev 경로를 끄고 다시 켜거나, Worker 이름을 바꿔 다시 배포한다(이름이 바뀌면 `WORKER_URL`과 앱 `workerUrl`도 바꿔야 함).

### 6.4 서비스 계정 키 교체 (유출 의심, 또는 정기 교체)
1. Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → **새 비공개 키 생성**
2. `jq -c . ~/Downloads/<새 키>.json | npx wrangler secret put FCM_SERVICE_ACCOUNT`
3. 캐시된 OAuth 토큰 지우기: `npx wrangler kv key delete --binding TOKENS --remote "oauth:access"`
4. `scripts/check.sh --send`로 확인
5. Google Cloud 콘솔 → IAM → 서비스 계정 → 키 탭에서 **이전 키 삭제**
6. 받은 키 파일 삭제

---

## 7. 점검과 모니터링

| 무엇을 | 어떻게 |
|---|---|
| 전체 상태 | `scripts/check.sh` (`--send`로 실제 진동까지) |
| 맥 전송 실패 | `~/.claude/watch-notify/notify.log` (실패만 기록) |
| Worker 요청·오류 | `cd worker && npx wrangler tail` |
| 등록된 워치 | `npx wrangler kv key list --binding TOKENS --remote` |
| 워치 앱 로그 | `adb -s <기기> logcat -s ClaudeMessaging TokenRegistrar WirelessDebugging` |
| 워치 진동 기록 | `adb -s <기기> shell dumpsys vibrator_manager \| grep claudewatch` (usage, 성공 여부) |
| 워치 알림 | `adb -s <기기> shell dumpsys notification \| grep claudewatch` |
| 워치 앱 상태 파일 | `adb -s <기기> shell run-as com.yunseong.claudewatch cat shared_prefs/registration.xml` (`settings.xml` 설정, `stats.xml` 오늘 통계도 같은 방식) |
| 반복 알림 예약 | `adb -s <기기> shell dumpsys jobscheduler \| grep -A3 claudewatch` |

정기 점검은 필요 없다. 진동이 이상하다고 느낄 때 `scripts/check.sh`부터 돌린다.

---

## 8. 장애 대응

**항상 `scripts/check.sh --send`부터 돌린다.** ✗가 처음 나온 구간이 원인이다. 흐름상 앞 구간(맥 → Worker → 워치) 순서로 본다.

### 8.1 진동이 아예 안 온다

| 확인 | 원인 | 조치 |
|---|---|---|
| `check.sh --send`는 진동이 오는데 실제 작업에서는 안 옴 | 훅이 그 세션에 적용 안 됨 | Claude Code 세션을 새로 연다. 훅 설정은 새 세션부터 적용 |
| ″ | 진동 기준보다 짧은 작업 | 워치 앱의 진동 기준을 "모든 작업"으로 |
| 맥 훅 ✗ | 설치나 설정 누락 | `hooks/install.sh`, config 값 확인 |
| `notify.log`에 `http=000` | 맥 네트워크 문제, 잘못된 URL | 인터넷 연결 확인, `WORKER_URL` 확인 |
| `notify.log`에 `http=401` | `NOTIFY_SECRET` 불일치 | config 값을 `local.properties`와 맞추거나 6.1로 다시 맞춤 |
| `notify.log`에 `http=500` | Worker 내부 오류. 대부분 OAuth 실패(서비스 계정 키 삭제나 비활성화) | `npx wrangler tail`로 오류 확인 → 6.4로 키 교체 |
| 응답이 `"sent":0` | 등록된 워치 없음 | 워치 앱을 연다. "연결 안 됨"이면 8.3 |
| 응답의 `errors`에 코드 | FCM 쪽 문제 | 8.2 |
| `sent:1`인데 워치 기록에도 없음 | FCM이 워치에 못 전달 | 워치의 블루투스 / Wi-Fi 연결 확인, 앱이 강제 종료됐거나 휴면 상태인지 확인(8.5) |
| 워치 기록에는 있는데 진동만 없음 | 앱 설정이 막음 | 홈의 "진동 설정" 요약을 본다: "조용한 시간 중", "완료 끔"/"대기 끔". 기록 카드에 🔇가 있으면 프로젝트 음소거(상세에서 다시 켬). 진동 기준도 확인 |
| ″ | 세기가 약함 | 설정 → 세기를 "강하게"로. 실제 진폭은 `dumpsys vibrator_manager`의 `played: [...]`로 확인 |
| ″ | 워치 진동 세기 0 | 워치 설정 → 소리와 진동 → 진동 세기 확인. `dumpsys vibrator_manager`에서 `ignored` 사유 확인 |
| 입력 대기 반복 알림이 안 옴 | 꺼져 있음, 앱을 열었음, 알림을 지웠음, 같은 세션이 완료됨 | 설정 → 다시 알림 확인. 앱을 열면 확인한 것으로 보고 취소한다(의도된 동작). Doze 중에는 몇 분 늦을 수 있음 |
| 반복 알림이 안 멈춤 | 같은 세션 완료가 안 옴(Claude가 다른 세션으로 진행) | 앱을 한 번 열면 전부 멈춘다 |
| 밤에 진동이 울림 | ALARM 진동은 취침 모드를 무시 | 설정 → 조용한 시간 켜기 |

### 8.2 FCM 오류 코드 (`errors[].code`)

| 코드 | 의미 | 조치 |
|---|---|---|
| `UNREGISTERED` | 토큰 만료 | 자동 삭제됨(`removed`). 워치 앱을 열면 재등록 |
| `SENDER_ID_MISMATCH` | 앱의 Firebase 프로젝트와 서비스 계정 프로젝트가 다름 | `google-services.json`과 서비스 계정 키가 같은 프로젝트인지 확인 |
| `THIRD_PARTY_AUTH_ERROR` / `PERMISSION_DENIED` | 서비스 계정 권한 문제 | 6.4로 키 재발급, Firebase Cloud Messaging API(V1) 사용 설정 확인 |
| `QUOTA_EXCEEDED` | 너무 많이 보냄 | 잠시 후 저절로 풀림. 훅이 반복 호출되는지 확인 |
| `UNAVAILABLE` / `INTERNAL` | Google 쪽 일시 장애 | 기다린다. 재시도 기능은 없음 |

### 8.3 워치가 "연결 안 됨"

| 오류 문구 | 원인 | 조치 |
|---|---|---|
| `Unable to resolve host …` / `timeout` | 워치가 오프라인 | 네트워크가 돌아오면 자동 재시도(최대 8회). 급하면 버튼을 탭 |
| `HTTP 401` | 앱에 들어간 `REGISTER_SECRET`이 Worker와 다름 | `local.properties` 값으로 다시 빌드·설치, 또는 Worker Secret을 맞춤 |
| `local.properties에 … 없음` | 빌드할 때 값이 비어 있었음 | `local.properties` 채우고 다시 빌드 |
| `FCM 토큰 없음` | `google-services.json` 없이 빌드됨, Play 서비스 문제 | `wear/google-services.json` 확인 후 다시 빌드 |

### 8.4 앱을 지웠거나 데이터가 사라졌다
`connectedDebugAndroidTest`, 앱 삭제, 데이터 삭제 후에는 다음을 다시 한다.
1. `ANDROID_SERIAL=<기기> ./gradlew :wear:installDebug`
2. `adb -s <기기> shell pm grant com.yunseong.claudewatch android.permission.WRITE_SECURE_SETTINGS`
3. 워치에서 앱 열기 → **알림 허용** 탭 (adb의 `pm grant POST_NOTIFICATIONS`는 앱이 권한 창을 띄우면 거절로 기록될 수 있어 직접 탭하는 게 확실함)
4. "알림 수신 중" 확인 → `scripts/check.sh --send`
옛 토큰은 다음 알림 때 Worker가 자동으로 지운다.

### 8.5 한동안 잘 되다가 어느 날부터 안 온다
- **앱 휴면**: 몇 달 동안 앱을 열지 않으면 Android가 앱을 멈춘다. 워치 설정 → 애플리케이션 → Claude Watch → "사용하지 않으면 앱 활동 일시중지"를 끈다. 앱을 한 번 열면 바로 풀린다.
- **강제 종료**: 설정에서 강제 종료하면 앱을 다시 열 때까지 FCM을 받지 못한다. 앱을 연다.
- **Cloudflare / Firebase 정책 변경, 키 비활성화**: `check.sh`의 Worker 구간과 `npx wrangler tail`로 확인한다.

### 8.6 맥에서 워치에 adb로 연결이 안 된다
1. 워치와 맥이 **같은 Wi-Fi**인지 확인한다.
2. 워치 앱 → **개발 → 무선 디버깅** 스위치를 켠다. 앱이 Wi-Fi도 같이 켠다. 설치하는 동안 앱 화면을 켜 둔다.
3. `adb devices`로 이름을 다시 확인한다. mDNS 이름 끝에 ` (2)`가 붙어 바뀌기도 한다.
4. 그래도 안 되면 워치 설정 → 개발자 옵션 → 무선 디버깅 → **새 기기 페어링**으로 `adb pair`부터 다시 한다.
5. 스위치가 "권한 필요"면 연결된 상태에서 `pm grant … WRITE_SECURE_SETTINGS`를 다시 실행한다.

### 8.7 `wrangler` 명령이 인증 오류를 낸다
`npx wrangler login`을 다시 한다. 이미 배포된 Worker 동작과는 무관하고, 배포나 관리할 때만 필요하다.

---

## 9. 알려진 제약

- 진동이 알람 용도라 **취침 모드에서도 울린다.** 앱의 조용한 시간으로 막는다.
- 반복 알림과 타일 갱신은 WorkManager·시스템 일정에 따르므로 Doze 중에는 몇 분 늦을 수 있다.
- Stop은 응답(턴)이 끝날 때마다 실행된다. 긴 작업이 여러 턴이면 알림도 여러 번 온다.
- 같은 세션의 새 알림은 워치 알림 목록에서 이전 것을 대체한다. 기록 화면에는 모두 남는다(최대 10건).
- 실패한 전송은 재시도하지 않는다. 워치가 10분 넘게 오프라인이면 그 사이 알림은 사라진다.
- 갤럭시 워치는 앱 알림 진동을 시스템 UI가 대신 울리면서 채널 설정을 무시한다. 그래서 진동은 앱이 직접 울린다(DESIGN.md 8.3).
- `connectedDebugAndroidTest`는 앱을 지운다(5.1).

---

## 10. 파일과 경로

| 경로 | 내용 |
|---|---|
| `docs/DESIGN.md` | 설계 기준과 결정 기록 |
| `docs/OPERATIONS.md` | 이 문서 |
| `scripts/check.sh` | 상태 점검 |
| `hooks/watch-notify.sh`, `hooks/install.sh`, `hooks/config.example` | 맥 훅 원본, 설치 스크립트, 설정 예시 |
| `worker/src/{index,fcm,auth}.ts`, `worker/wrangler.jsonc` | Worker 코드와 설정 |
| `wear/src/main/…` | 워치 앱 |
| `wear/src/debug/AndroidManifest.xml` | debug 빌드 전용 권한 |
| `wear/src/main/java/.../tile/StatusTileService.kt` | 워치 타일 |
| `wear/src/test/…`, `wear/src/androidTest/…` | 테스트 |
| `local.properties` *(git 제외)* | SDK 경로, Worker URL, 비밀값 2개 |
| `wear/google-services.json` *(git 제외)* | Firebase 앱 설정 |
| `~/.claude/watch-notify/` | 설치된 훅, `config`, `state/`, `notify.log` |
| `~/.claude/settings.json` | Claude Code 훅 등록 (백업: `settings.json.bak-*`) |
