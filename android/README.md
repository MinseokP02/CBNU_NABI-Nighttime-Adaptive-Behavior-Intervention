# NABI Android 앱

수면 코치 고양이 **나비**가 홈 화면과 다른 앱 위를 돌아다니는 Kotlin 앱입니다.
고양이 오버레이는 [Mochi](https://github.com/MinseokP02/Mochi)를 기반으로 만들었고, NABI 백엔드(`/api/v1`)와 연결됩니다.

```
Android 앱 ──HTTP(JWT)──▶ backend :8000 /api/v1 ──X-Internal-Token──▶ llm-server :8100 (내부 전용)
```

앱은 **백엔드에만** 연결합니다. LLM 서버는 내부망 전용이므로, LLM 대화는 `POST /chat/messages`와 미션 완료 API를 통해 간접적으로 사용합니다.

## 실행

1. 서버를 먼저 켭니다 (루트 폴더에서):
   ```powershell
   docker compose up -d db llm-server
   docker compose run --rm backend alembic upgrade head
   docker compose run --rm backend python -m app.seed.seed_interventions
   docker compose up -d --build backend
   ```
2. Android Studio → File → Open → 이 `android` 폴더를 엽니다. Gradle 동기화가 끝날 때까지 기다립니다.
3. Run ▶ 으로 에뮬레이터나 폰에 설치합니다.
4. 앱에서 서버 주소 입력 → **연결 확인** → 회원가입/로그인 → **다른 앱 위에 표시 권한 허용** → **나비 부르기**.

### 서버 주소

백엔드는 `127.0.0.1:8000`에만 열려 있습니다 (`compose.yaml`).

| 기기 | 주소 | 준비 |
|---|---|---|
| 에뮬레이터 | `http://10.0.2.2:8000` (기본값) | 없음 |
| USB로 연결한 폰 | `http://127.0.0.1:8000` | PC에서 `adb reverse tcp:8000 tcp:8000` (연결할 때마다) |
| 같은 Wi‑Fi의 폰 | `http://<PC IP>:8000` | `compose.yaml`의 포트를 `0.0.0.0`으로 열어야 함. 개발용으로만 |

HTTP(비암호화) 연결은 **debug 빌드에서만** 허용됩니다 (`app/src/debug/res/xml/network_security_config.xml`). release 빌드는 HTTPS만 쓰며 기본 주소는 `app/build.gradle.kts`의 `DEFAULT_BASE_URL`에서 바꿉니다.

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

## 구조

```text
android/
├── app/build.gradle.kts            DEFAULT_BASE_URL (debug/release)
└── app/src/main/java/com/cbnu/nabi/
    ├── data/
    │   ├── ApiClient.kt            /api/v1 호출 (HttpURLConnection + org.json, 외부 라이브러리 없음)
    │   ├── Models.kt               응답 모델, CatAction enum, 한글 라벨
    │   └── Prefs.kt                서버 주소, JWT, conversation_id, 친밀도
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
| 연결 확인 | `GET /health` |
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
