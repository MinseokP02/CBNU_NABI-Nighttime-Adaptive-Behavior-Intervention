# NABI Android 앱

수면 코치 고양이 **나비**가 홈 화면과 다른 앱 위를 돌아다니는 Kotlin 앱입니다.
고양이 오버레이는 [Mochi](https://github.com/MinseokP02/Mochi)를 기반으로 만들었고, NABI 백엔드(`/api/v1`)와 연결됩니다.

```
Android 앱 ──HTTP(JWT)──▶ backend :8000 /api/v1 ──X-Internal-Token──▶ llm-server :8100 (내부 전용)
```

앱은 **백엔드에만** 연결합니다. LLM 서버는 내부망 전용이므로, LLM 대화는 `POST /chat/messages`와 미션 완료 API를 통해 간접적으로 사용합니다.

## 실행

1. 서버를 먼저 켭니다 (루트 폴더에서, Docker Desktop 필요):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\start-server.ps1
   ```
2. Android Studio → File → Open → 이 `android` 폴더를 엽니다. Gradle 동기화가 끝날 때까지 기다립니다.
3. Run ▶ 으로 에뮬레이터나 폰에 설치합니다.
4. 회원가입/로그인 → **다른 앱 위에 표시 권한 허용** → **나비 부르기**. 서버 주소는 앱에서 입력하지 않습니다.

### 서버 주소

앱에는 서버 주소 입력 화면이 없습니다. 주소는 빌드 종류별로 `app/build.gradle.kts`의 `DEFAULT_BASE_URL`에 고정됩니다.

| 빌드 | 주소 |
|---|---|
| debug | `http://10.0.2.2:8000` — 에뮬레이터에서 이 PC의 `127.0.0.1:8000`(백엔드)을 가리킴 |
| release | `https://api.example.com` — 실제 배포 주소로 바꿔서 빌드 |

USB로 연결한 실제 폰에서 debug 빌드를 쓰려면 `DEFAULT_BASE_URL`을 `http://127.0.0.1:8000`으로 바꿔 빌드하고 PC에서 `adb reverse tcp:8000 tcp:8000`을 실행합니다.

HTTP(비암호화) 연결은 **debug 빌드에서만** 허용됩니다 (`app/src/debug/res/xml/network_security_config.xml`). release 빌드는 HTTPS만 씁니다.

### 언어

화면 문구는 `res/values/strings.xml`(한국어, 기본)과 `res/values-en/strings.xml`(영어)에 있고, 기기 언어(또는 Android 13+의 앱별 언어 설정)에 따라 바뀝니다. 코드에서는 `tr(R.string.키, 값…)`으로 씁니다 (`NabiApp.kt`). 새 문구를 추가할 때는 두 파일에 같은 키로 넣으세요.
- 앱은 모든 요청에 `Accept-Language`(앱 언어)를 보내고, 서버는 중재 추천·미션 문구, 대체 응답, LLM 대화를 그 언어(ko/en)로 돌려줍니다.

## 나비가 하는 일

| 상황 | 나비 반응 |
|---|---|
| 평소 | 돌아다니기·앉기·그루밍·낮잠 (Mochi와 동일) |
| 머리·턱 / 등 / 배 / 꼬리 / 발 쓰다듬기 | Mochi와 동일 (골골송, 냥펀치, 하악질 등) |
| **꾹 누르기 (0.7초)** | 대화 화면 열기 |
| 대화 답장 | LLM 답장을 말풍선으로 말하고, `cat_action`대로 움직임 |
| 미션 완료 | 완료 메시지를 말하고 기뻐함 (`HAPPY`) |
| 서버 `/cat/state` (5분마다) | `HAPPY` 미션 완료 직후 · `SLEEP` 밤 0~6시엔 주로 잠 · `COMFORT` 수면 위험이 높으면 화면 가운데 근처에 앉아 다독임 |
| 위기 응답 (`safety_status=CRISIS`) | 장난스러운 동작 없이 `COMFORT`로만 반응 |

`cat_action` 값(`IDLE`, `SIT`, `WALK`, `SLEEP`, `HAPPY`, `COMFORT`)은 `backend/app/schemas/chat.py`와 `llm-server/app/schemas.py`의 enum과 같습니다.

## 알림 · 기상 알람

마이페이지 › 알림 설정에서 켜면 `AlarmManager`로 예약됩니다 (`alarm/`). 목표 시각은 마이페이지의 **수면 목표**를 씁니다.

| 알림 | 시각 | 조건 |
|---|---|---|
| 취침 준비 안내 | 목표 취침 시각 30분 전 | — |
| 수면 일지 작성 알림 | 목표 기상 시각 1시간 후 | 오늘 일지가 없을 때만. 누르면 수면 일지 탭 |
| 활동 및 미션 안내 | 매일 15:00 | `GET /missions/active`에 남은 미션이 있을 때 (서버 실패 시 스트레칭 안내) |
| 기상 알람 | 목표 기상 시각 | 잠금화면 위 전체화면 알람, 알람 소리 + 진동, "일어났어요" / "5분 더", 10분 뒤 자동 종료 |

- "알림 받기"가 꺼져 있거나 목표 시각이 없으면 예약하지 않습니다. 로그아웃하면 모두 취소됩니다.
- 재부팅·앱 업데이트·시간/시간대 변경 시 `BootReceiver`가 다시 예약합니다.
- Android 12+에서 "알람 및 리마인더"(정확한 알람) 권한이 없으면 알림 설정 화면에 권한 카드가 뜨고, 그동안은 몇 분 늦을 수 있는 일반 알람으로 예약합니다.
- 테스트: 수면 목표를 1~2분 뒤로 저장 → `adb shell dumpsys alarm | grep -A3 com.cbnu.nabi`로 예약 확인.

## 기기 데이터 연동 (Health Connect · 사용 정보)

마이페이지 › 데이터 접근 권한에서 연결합니다 (`data/DeviceData.kt`, `ui/DataAccessDialog.kt`).

| 출처 | 권한 | 쓰는 곳 |
|---|---|---|
| Health Connect 수면 세션 | `health.READ_SLEEP` | 수면 기록 시트를 자동으로 채움: 취침(세션 시작), 기상(마지막 수면 단계 끝), 입면 잠복기(시작→첫 수면 단계), 밤중 각성(첫·마지막 수면 사이 깨어 있던 단계의 횟수·분) |
| Health Connect 운동 세션 | `health.READ_EXERCISE` | 전날 운동 시간 (0분이면 채우지 않음) |
| UsageStats | `PACKAGE_USAGE_STATS` (시스템 설정에서 허용) | 잠들기 전 2시간 화면 켜짐 시간 → `screen_before_bed_min` (Android 9+) |

- 자동으로 채운 값은 모두 사용자가 고칠 수 있고, 권한이 없으면 예전처럼 직접 입력합니다.
- 에뮬레이터 테스트 (debug 빌드 전용 시더, 어젯밤 23:40~07:05 수면과 전날 40분 걷기를 기록):
  ```powershell
  adb shell pm grant com.cbnu.nabi android.permission.health.WRITE_SLEEP
  adb shell pm grant com.cbnu.nabi android.permission.health.WRITE_EXERCISE
  adb shell am broadcast -n com.cbnu.nabi/.debug.HealthSeedReceiver
  ```

## 산책 (나비의 산책 제안 → 산책 페이지)

| 단계 | 동작 |
|---|---|
| 제안 | 앱 밖에서 나비(오버레이)가 "잠깐 같이 산책할래냥?" 카드를 띄움 (`pet/WalkSuggestionCard.kt`). 10~20시에 30분마다 `GET /interventions/recommendations`를 보고 `SH_DAYTIME_ACTIVITY`(낮 활동)이면 제안, 하루 한 번 |
| 수락 | "좋아, 같이 걷자" → 나의 미션에 진행 중으로 추가 → NABI 앱 → 산책 페이지 (`walk/WalkActivity.kt`). 앱 안의 나비의 제안(산책)을 수락해도 같은 페이지로 감 |
| 경로 추천 | 현재 위치에서 출발해 돌아오는 순환 경로. 산책 시간 15/30/45/60분, "다른 경로", 지도를 눌러 지나갈 곳 추가(최대 3곳, 표시를 누르면 삭제), "초기화" |
| 기록 | 산책 시작 → 위치 포그라운드 서비스(`WalkTrackingService`)가 화면이 꺼져도 경로 기록 → 산책 끝내기 → `POST /walks` |
| 내역 | `GET /walks` 목록과 누적 거리·시간. 저장 실패 시 기기에 보관했다가 다음에 자동 전송 (`PendingWalks`) |

- 지도: OpenStreetMap 타일(osmdroid, API 키 없음). 경로: 공개 OSRM 도보 라우터 `routing.openstreetmap.de` — **출발점과 경유지 좌표가 이 외부 서버로 전송됨**. 연결 실패 시 직선 경로로 표시. 정식 서비스 전에는 자체 라우터나 계약된 지도 API로 바꾸는 것을 권장.
- 테스트 (debug 빌드): 나비 화면 상주를 켠 뒤
  ```powershell
  adb emu geo fix 127.4563 36.6285          # 에뮬레이터 위치 (경도 위도)
  adb shell am broadcast -n com.cbnu.nabi/.debug.SuggestWalkReceiver
  ```

## 구조

```text
android/
├── app/build.gradle.kts            DEFAULT_BASE_URL (debug/release)
└── app/src/main/java/com/cbnu/nabi/
    ├── data/
    │   ├── ApiClient.kt            /api/v1 호출 (HttpURLConnection + org.json, 외부 라이브러리 없음)
    │   ├── Models.kt               응답 모델, CatAction enum, 한글 라벨
    │   └── Prefs.kt                JWT, conversation_id, 친밀도 (서버 주소는 빌드 설정값)
    ├── alarm/
    │   ├── NabiAlarms.kt           알림·기상 알람 예약/취소 (AlarmManager), 알림 채널
    │   ├── AlarmReceiver.kt        알림 표시 + 다음 날 재예약
    │   ├── BootReceiver.kt         재부팅·시간 변경 시 재예약
    │   └── WakeAlarmActivity.kt    전체화면 기상 알람 (해제/스누즈)
    ├── pet/
    │   ├── CatView.kt              고양이 그리기·상태 머신·터치 (Mochi) + 서버 반응·꾹 누르기·여러 줄 말풍선
    │   └── PetService.kt           오버레이 창 2개 (Mochi) + /cat/state 주기 동기화
    └── ui/
        ├── MainActivity.kt         서버 설정, 로그인/회원가입, 상태·미션, 나비 부르기/숨기기
        ├── ChatActivity.kt         나비와 대화 (API-15/16)
        └── Ui.kt                   백그라운드 실행, 뷰 헬퍼
```

### 연결된 API

| 화면/기능 | API |
|---|---|
| 회원가입·로그인 | `POST /auth/register`, `POST /auth/login` |
| 홈 상태 | `GET /users/me/state` |
| 미션 | `GET /missions/active`, `POST /missions/{id}/complete` |
| 대화 | `POST /chat/messages`, `GET /chat/history` |
| 오버레이 고양이 | `GET /cat/state` |
| (화면 미구현, 함수만 준비) | `POST /sleep-records`, `POST /behavior-records` — `ApiClient.createSleepRecord`, `createBehaviorRecord` |

## 참고

- AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7, minSdk 26, targetSdk 34. Mochi와 같은 버전이며 외부 라이브러리가 없습니다.
- Android 12 이상에서는 고양이가 살짝(80%) 반투명합니다. 고양이 주변을 눌러도 아래 앱이 반응하게 하려는 값입니다 (Mochi와 동일).
- 삼성 등 일부 기기는 배터리 최적화로 서비스를 종료할 수 있습니다. 설정 → 애플리케이션 → 나비 → 배터리 → 제한 없음.
- JWT는 개발 편의상 일반 SharedPreferences에 저장합니다. 출시 전에는 EncryptedSharedPreferences/Keystore로 옮겨야 합니다.

## 다음 작업 후보

- 수면일지·행동 기록 입력 화면 (API 함수는 준비됨)
- 주간 리포트(`GET /reports/weekly`), 중재 추천(`GET /interventions/recommendations`) 화면
- 토큰 만료 시 자동 재로그인, 토큰 암호화 저장
