# TikTrace — Phase 1: 캡처

[모드 C 안드로이드 설계](../docs/tiktok-android-capture-design.md)의 Phase 1 구현.

WebView 로 TikTok 모바일 웹을 띄우고, 페이지가 받은 피드 응답을 **원본 그대로** 로컬 DB에 쌓는다.
루팅·MITM·APK 재서명 전부 불필요하다. 스톡 갤럭시에서 동작한다.

---

## Phase 1 의 범위

**하는 것**

- `WebViewCompat.addDocumentStartJavaScript()` 로 페이지 스크립트보다 먼저 `fetch`/`XHR` 후킹
- `WebViewCompat.addWebMessageListener()` 로 응답 본문을 네이티브로 전달
- 응답 원본을 가공 없이 Room(SQLite)에 적재 — 본문 SHA-256 으로 중복 제거
- 하단 현황 표시줄 · 진단 화면 · JSONL 내보내기

**하지 않는 것** (설계 문서의 Phase 2 이후)

- 정규화, Tier 1/2/3 점수 계산, 랭킹 화면
- 백그라운드 리폴링 (WorkManager)
- 알림

> **지금 당장 Phase 1 만 돌려도 의미가 있다.** 데이터는 소급 수집이 불가능하다.
> 파서가 없어도 원본이 쌓여 있으면 나중에 전부 재계산할 수 있지만, 안 쌓은 날은 영원히 없다.

---

## 빌드

Android Studio 로 `android/` 를 열면 된다. 커맨드라인이면:

```bash
cd android
gradle wrapper            # gradlew 가 없다면 한 번만
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`compileSdk 35` / `minSdk 26`. Android SDK 가 설치돼 있고 `ANDROID_HOME` 이 잡혀 있어야 한다.

---

## 첫 실행

1. 앱을 열면 `tiktok.com/foryou` 가 뜬다. **WebView 안에서 로그인**한다 — 쿠키는 `CookieManager` 가 유지하므로 한 번이면 된다.
2. 피드를 스크롤한다.
3. 하단 표시줄이 `캡처 3건 · 영상 27개 · 412KB · 14:32` 처럼 바뀌면 정상이다.

표시줄을 누르면 메뉴가 열린다.

### 진단

| 항목 | 의미 |
|---|---|
| `document-start 주입: 사용 중` | 페이지 스크립트보다 먼저 후킹됨. **정상** |
| `document-start 주입: 폴백` | `onPageStarted` 로 늦게 주입됨 → **첫 화면 분량을 놓친다.** 시스템 WebView 를 106+ 로 업데이트할 것 |
| `WebMessageListener 브리지: 폴백` | `addJavascriptInterface` 로 동작 중. 기능은 하지만 원본 제한이 없다 |
| `중복 무시 N건` | 같은 응답이 두 번 들어와 걸러진 수. 0 이 아니어도 정상 |
| `형식 불명 N건` | 봉투가 깨진 메시지. **0 이어야 한다** |

---

## 내보내기와 검증

메뉴 → **JSONL 내보내기** → 저장 위치 선택. 한 줄에 응답 하나이고, `body` 는 원본 JSON 이 그대로 들어간다.

받은 파일은 검증기로 확인한다:

```bash
node android/tools/validate-export.mjs ~/Downloads/tiktrace-20260827-1432.jsonl
```

```
응답          128건
기간          2026-08-27 05:12 ~ 2026-08-27 09:44 (UTC)
화면별        fyp=119 · detail=7 · profile=2
영상          선언 1043개 / 실제 1043개 / 고유 912개
크리에이터    604명

이상 없음 — Tier 1 계산에 필요한 (좋아요, createTime) 이 모두 들어 있다.
```

**`고유` 가 `실제` 보다 작다는 것이 중요하다.** 그 차이가 중복 관측이고, 곧 [Tier 2](../docs/tiktok-fyp-capture-design.md) 의 공짜 스냅샷이다.

검증기가 `body 가 JSON 이 아니라 문자열로 감싸졌다` 를 뱉으면 봇 체크 HTML 을 받았다는 뜻이다 — 설계 문서 §7 이 경고한 상황이다.

---

## 테스트

후킹 스크립트는 Android SDK 없이도 검증된다. `hook.js` 를 가짜 브라우저 샌드박스에 실제로 로드해 돌린다:

```bash
node android/tools/test-hook.mjs
```

캡처 동작뿐 아니라 **"페이지를 깨뜨리지 않는다"** 는 계약(`clone()` 순서, 중복 설치, `responseText` 예외, fetch 실패)까지 검사한다. 후킹이 페이지의 응답 본문을 먹어버리면 TikTok 이 그냥 안 뜨므로, 이게 Phase 1 에서 가장 중요한 테스트다.

---

## 검증 상태

| | 상태 |
|---|---|
| `hook.js` 동작·계약 | ✅ 9/9 통과 (`tools/test-hook.mjs`) |
| 내보내기 형식 파싱 | ✅ 합성 데이터로 정·부정 케이스 확인 |
| **Kotlin 컴파일** | ❌ **미검증** — 작성 환경에 Android SDK 가 없었다 |
| **기기 실행** | ❌ **미검증** |

첫 빌드에서 컴파일 오류가 날 수 있다. 나면 알려주면 고친다.

---

## 구조

```
app/src/main/
  assets/hook.js                  후킹 스크립트 (단일 원본, 테스트가 이걸 직접 로드한다)
  java/dev/worktrace/tiktrace/
    App.kt                        프로세스 스코프 + DI 대용
    capture/HookInstaller.kt      document-start 주입, 브리지, 딥링크 차단
    data/RawPayload.kt            엔티티 · Surface · 집계 모델
    data/RawPayloadDao.kt         삽입(중복 무시) · 집계 · keyset 페이지
    data/CaptureRepository.kt     봉투 해석 · 적재 · JSONL 내보내기
    ui/FeedActivity.kt            WebView 화면 · 현황 · 진단 · 내보내기
tools/
  test-hook.mjs                   후킹 검증 하네스
  validate-export.mjs             내보낸 JSONL 검증기
```

---

## 알아둘 것

- **모바일 웹이라 "앱으로 열기" 배너가 뜬다.** 딥링크(`snssdk://` 등)는 `CaptureWebViewClient` 가 삼키므로 네이티브 앱으로 튕겨나가지는 않는다.
- **자동 스크롤을 붙이지 말 것.** 인위적 조회수가 발생해 계정 제재 위험이 있다. 수동 브라우징 캡처는 이미 받은 데이터를 기록하는 것뿐이라 성격이 다르다.
- **데이터는 전부 로컬**이다(`allowBackup=false`, 앱 전용 저장소). 세션 쿠키는 어디로도 나가지 않는다.
- 백그라운드 리폴링(Phase 5)을 붙일 때는 삼성 절전 예외 설정이 필수다. 설계 문서 §5 참고.
