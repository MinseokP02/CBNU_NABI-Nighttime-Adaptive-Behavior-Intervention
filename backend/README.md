# NABI backend

명세서 「NABI API 및 LLM 서버 기능 명세서 v1.0」을 기준으로 구현한 FastAPI 백엔드입니다.

## Architecture

요청 흐름은 `router → service → repository → SQLAlchemy Session → PostgreSQL`입니다. 사용자는 JWT `sub`로만 식별하며 클라이언트가 `user_id`를 지정하는 API는 없습니다. 시간은 ISO 8601(offset 필수), 날짜는 `YYYY-MM-DD`이고 서버 timestamp는 UTC `TIMESTAMPTZ`로 저장합니다.

설계 원칙(명세 §9): **분석 엔진은 상태 판단, 중재 엔진은 행동 선택, LLM은 설명·표현**만 담당합니다. LLM 출력은 의료적 판단이나 중재 선택을 바꾸지 못합니다.

```
Android → /api/v1 (FastAPI) → PostgreSQL
                   ├─ Analysis Engine   services/baseline_service.py, risk_service.py
                   ├─ Intervention Engine services/intervention_service.py
                   └─ llm_client ──X-Internal-Token──▶ llm-server /internal/v1/llm/*
```

## Startup on Windows PowerShell

```powershell
Copy-Item .env.example .env   # 이미 있으면 새 LLM_* / 분석 설정 항목을 .env에 추가
# POSTGRES_PASSWORD, DATABASE_URL, TEST_DATABASE_URL, JWT_SECRET_KEY, LLM_INTERNAL_TOKEN 변경
docker compose up -d db llm-server
docker compose run --rm backend alembic upgrade head
docker compose run --rm backend python -m app.seed.seed_interventions
docker compose up -d --build backend
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
```

Swagger UI: <http://127.0.0.1:8000/docs>

## API (Base URL `/api/v1`)

| ID | Method | Endpoint | 비고 |
|---|---|---|---|
| API-01 | POST | `/auth/register` | 201, 중복 이메일 409 |
| API-02 | POST | `/auth/login` | JWT 발급 |
| API-03 | GET | `/users/me` | |
| API-04 | GET | `/users/me/state` | baseline·위험도·고양이·활성 미션 요약 |
| API-05 | POST | `/sleep-records` | TIB/TST/SE 계산, 201 |
| API-06 | GET | `/sleep-records` | `start_date`, `end_date`, `limit` |
| API-07 | POST | `/behavior-records` | 201 |
| API-08 | GET | `/behavior-records` | `start_date`, `end_date`, `limit` |
| API-09 | GET | `/analysis/baseline` | `ready` / `collecting` |
| API-10 | GET | `/analysis/risk?window=3\|7` | 그 외 값은 422 |
| API-11 | POST | `/analysis/refresh` | baseline + 3·7일 위험도 저장 |
| API—11 | GET | `/analysis/trends?days=14` | 지표별 일자 값 + 개인 기준선 평소 범위 (7~60일, 추이 그래프용) |
| API-12 | GET | `/interventions/recommendations` | `include_explanation=true`면 LLM-04 설명 포함 |
| API-13 | GET | `/missions/active` | 활성 미션이 없으면 추천 중재의 미션 발급 |
| API-14 | POST | `/missions/{mission_id}/complete` | 검증·보상·LLM-05 완료 메시지 |
| API-15 | POST | `/chat/messages` | LLM 대화, 분당 호출 제한 |
| API-16 | GET | `/chat/history` | `conversation_id`, `limit` |
| API-17 | GET | `/cat/state` | 고양이 행동 태그 |
| API-18 | GET | `/reports/weekly` | `end_date` (기본: 사용자 시간대 오늘) |
| API-19 | GET | `/health` | DB·LLM 상태 포함. 컨테이너용 `/health`, `/ready`도 유지 |



`GET /rewards/history`는 기존 보상 이력 API로 유지합니다.

### 오류 형식 (명세 §4.7)

```json
{"error": {"code": "VALIDATION_ERROR", "message": "Request validation failed", "details": [...]}}
```

`INVALID_REQUEST`(400, 잘못된 JSON), `UNAUTHORIZED`(401), `FORBIDDEN`(403), `RESOURCE_NOT_FOUND`(404), `DUPLICATE_RECORD`(409), `VALIDATION_ERROR`(422), `RATE_LIMITED`(429), `INTERNAL_ERROR`(500), `LLM_UNAVAILABLE`(503). 미션 규칙 위반은 409 `MISSION_NOT_VERIFIED` / `MISSION_EXPIRED`입니다.

### 수면일지 계산 (API-05)

```
TIB = out_of_bed_time − bedtime
TST = (final_awakening − bedtime) − sleep_onset_latency_min − wake_after_sleep_onset_min
SE  = TST / TIB              # 0~1 비율, 소수 4자리 (×100 = %)
```

명세 예시 입력(23:30 취침, SOL 35, WASO 25, 06:50 기상, 07:10 기상 후 이탈)은 이 식으로 TIB 460, **TST 380**, SE 0.8261입니다. 명세 응답 예시의 TST 370 / SE 0.8043은 위 식과 10분 차이가 나므로 문서 예시 값의 오기로 판단했습니다.

- `sleep_date`는 그 밤의 날짜입니다: 사용자 시간대 기준 `(bedtime − 12시간)`의 날짜. 01:00 취침은 전날 밤으로 기록됩니다.
- 다음은 422입니다: offset 없는 시각, `final_awakening ≤ bedtime`, `out_of_bed_time < final_awakening`, 음수 시간, `SOL + WASO`가 취침~기상 시간을 넘는 경우, TIB 24시간 초과, 미래 시각.
- 같은 날짜 중복은 409입니다.

### 행동 기록 (API-07)

`caffeine_after_cutoff`, `screen_before_bed_min`, `nap_duration_min`, `exercise_duration_min`, `stress_level`(1~5), `relaxation_completed`. 입력하지 않은 항목은 0이 아니라 "기록 없음"(NULL)으로 저장하며, 최소 한 항목은 필요합니다.

## Analysis Engine (규칙 기반)

### Baseline (API-09)

- **창**: 최근 7일 분석 구간 바로 앞의 14일(rolling). 기록이 그만큼 쌓이기 전에는 사용자의 처음 14일을 씁니다.
- **유효일**: 침대에 있던 시간(TIB)이 3~16시간인 밤. 유효일이 `BASELINE_MIN_VALID_DAYS`(기본 10) 이상이면 `ready`, 미만이면 `collecting`입니다.
- **지표**: `sleep_duration`, `sleep_efficiency`, `sleep_onset_latency`, `wake_after_sleep_onset`, `bedtime`, `wake_time`의 median과 MAD. 취침·기상 시각은 목표 시각(`profile.target_bedtime`/`target_wake_time`, 기본 23:00/07:00)으로부터의 분 단위 offset입니다.
- 입력 기록이 바뀔 때만 새 snapshot(`baseline_id`)을 저장합니다.

### Risk (API-10)

`Robust Z = (x − median) / (1.4826 × MAD)`. x는 최근 3일 또는 7일 유효 기록의 중앙값입니다.

**MAD 안정화 규칙**: MAD를 지표별 최소값(수면시간 15분, 효율 0.02, SOL·WASO 5분, 취침·기상 15분)보다 작게 보지 않고, z는 ±10에서 자릅니다. 이렇게 하면 MAD = 0일 때 0으로 나누는 문제와, 생활이 아주 규칙적인 사용자에게서 z가 지나치게 커지는 문제를 막습니다.

| 요인 | 개인 기준(z ≥ 2.0, 나빠지는 방향) | 참고 기준(baseline 없어도 적용) | 심각 |
|---|---|---|---|
| PROLONGED_SLEEP_ONSET | SOL 증가 | SOL > 30분 | z ≥ 3 또는 > 60분 |
| FREQUENT_NIGHT_AWAKENING | WASO 증가 | WASO > 30분 | z ≥ 3 또는 > 60분 |
| LOW_SLEEP_EFFICIENCY | SE 감소 | SE < 0.85 | z ≥ 3 또는 < 0.75 |
| SHORT_SLEEP_DURATION | TST 감소 | – | z ≥ 3 |
| IRREGULAR_BEDTIME / IRREGULAR_WAKE_TIME | 시각 이동 \|z\| | 구간 내 최대-최소 ≥ 120분 | z ≥ 3 |
| POOR_SLEEP_QUALITY | – | 수면 질 중앙값 ≤ 2 | – |
| LATE_CAFFEINE | – | 기록일의 25% 이상 | – |
| EXCESSIVE_PRE_BED_SCREEN | – | 중앙값 ≥ 60분 | – |
| LONG_DAYTIME_NAP | – | 중앙값 ≥ 30분 | – |
| HIGH_STRESS | – | 중앙값 ≥ 4 | – |
| LOW_PHYSICAL_ACTIVITY | – | 중앙값 < 10분 | – |

최근 구간에 기록이 window의 절반(3일 → 2일, 7일 → 4일) 이상 있어야 평가합니다.

- `sleep_state`: `UNKNOWN`(baseline 수집 중 또는 기록 부족) · `DETERIORATING`(수면 요인 1개 이상) · `IMPROVING`(요인 없음 + SE 상승 또는 SOL/WASO 감소 z ≥ 2) · `STABLE`
- `behavior_state`: `UNKNOWN` · `HEALTHY`(0개) · `CAUTION`(1개) · `AT_RISK`(2개 이상)
- `risk_level`: `HIGH`(수면 요인 3개 이상, 또는 2개 이상이면서 심각 1개 포함) · `MODERATE`(수면 요인 1개 이상 또는 행동 AT_RISK) · `LOW` · `UNKNOWN`

임계값은 임상 진단 기준이 아니라 MVP 변화 탐지 설정이며 `.env`에서 조정합니다.

## Intervention Engine (API-12)

`intervention_service.RULES`는 선언형 규칙 표입니다. 모든 규칙을 평가한 뒤 우선순위 → CBT-I 범주 순서 → 규칙 순서로 정렬합니다.

| 규칙 | 조건 | 중재 code | 범주 | 우선순위 |
|---|---|---|---|---|
| R-SC-01 | PROLONGED_SLEEP_ONSET 또는 FREQUENT_NIGHT_AWAKENING | SC_GET_OUT_OF_BED | STIMULUS_CONTROL | HIGH |
| R-SC-02 | LOW_SLEEP_EFFICIENCY | SC_BED_FOR_SLEEP_ONLY | STIMULUS_CONTROL | HIGH |
| R-SS-01 | IRREGULAR_BEDTIME | SS_CONSISTENT_BEDTIME | SLEEP_SCHEDULE | MEDIUM |
| R-SS-02 | IRREGULAR_WAKE_TIME 또는 SHORT_SLEEP_DURATION | SS_FIXED_WAKE_TIME | SLEEP_SCHEDULE | MEDIUM |
| R-CG-01 | 대화에서 수면 관련 부정적 생각 감지 | CG_WORRY_NOTE | COGNITIVE | MEDIUM |
| R-CG-02 | POOR_SLEEP_QUALITY + HIGH_STRESS | CG_WORRY_NOTE | COGNITIVE | MEDIUM |
| R-RX-01 | HIGH_STRESS | RX_BREATHING | RELAXATION | MEDIUM |
| R-SH-01~04 | 카페인·화면·낮잠·활동 | SH_* | SLEEP_HYGIENE | MEDIUM/LOW |
| R-DEFAULT | 해당 없음 | SS_FIXED_WAKE_TIME | SLEEP_SCHEDULE | LOW |

- 심각 요인과 연결된 규칙은 HIGH로 올립니다.
- 카탈로그(`interventions`)에 없거나 비활성이거나 `requires_expert_review=true`인 중재는 고르지 않습니다. 남는 중재가 없으면 기본 중재를 씁니다.
- **수면 제한**은 카탈로그에 넣지 않았습니다. 넣으려면 전문가 검토 후 `requires_expert_review=true`로 추가해야 합니다.
- 응답 최상위 필드는 명세 예시를 따릅니다: `intervention_id`, `category`, `priority`, `reason_codes`, `mission_id`, `requires_llm_explanation`. 그 밖에 `code`, `title`, `message`, `rule_ids`, `alternatives`를 함께 반환합니다.

카탈로그와 미션 문구는 `app/seed/seed_interventions.py`에 있으며 전문가 검토용 초안입니다.

## Chat (API-15, 명세 §6 흐름)

1. 인증·검증 → 2. baseline·7일 위험도·승인된 중재 조회 → 3. LLM 서버가 안전성 사전 검사(위기 표현이면 추론 없이 고정 안내) → 4~5. 프롬프트·추론·JSON/enum·중재 일치·의료 안전 검사 → 6. 사용자·응답 메시지와 메타데이터(intent, emotion, cat_action, safety_status, source, latency) 저장.

- LLM 호출 전에 DB transaction을 commit해 트랜잭션을 오래 잡고 있지 않습니다.
- LLM 서버에는 상태 enum, 위험 요인 코드, baseline 중앙값, 승인된 중재, 최근 대화 6개만 보냅니다. 이메일·UUID·원본 일지는 보내지 않습니다.
- 백엔드도 LLM 응답의 enum을 다시 검증합니다.
- LLM 장애 시 기본값(`LLM_FAIL_MODE=fallback`)은 중재 문구 기반의 고정 응답(200, `source=fallback`)입니다. `error`로 설정하면 503 `LLM_UNAVAILABLE`을 반환합니다. 어느 모드든 위기 표현에는 상담전화 안내를 반환합니다.
- `CHAT_RATE_LIMIT_PER_MINUTE`(기본 20)를 넘으면 429입니다. 프로세스 메모리 기반이므로 여러 인스턴스로 운영할 때는 프록시나 Redis로 옮겨야 합니다.

## Missions, cat

- 미션 검증 유형: `SLEEP_DIARY`, `BEDTIME_WINDOW`, `WAKE_TIME_WINDOW`, `NO_LATE_CAFFEINE`, `SCREEN_BEFORE_BED_MAX`, `NAP_MAX`, `EXERCISE_MIN`, `RELAXATION_DONE`, `SELF_REPORT`. 미션 기간(24시간) 안에 작성된 기록으로만 검증합니다.
- 완료 처리는 해당 미션의 최신 assignment와 고양이 row를 `SELECT FOR UPDATE`로 잠그고 한 transaction으로 커밋합니다. 반복 요청은 `idempotent=true`, 보상 0으로 응답합니다.
- `/cat/state`의 `cat_action`은 다음 순서로 정합니다: 최근 6시간 안에 미션 완료 → `HAPPY`, 현지 시각 00~06시 → `SLEEP`, 위험도 MODERATE/HIGH → `COMFORT`, 그 외 → `IDLE`.

## PostgreSQL tests

SQLite는 사용하지 않습니다. 테스트 DB가 없으면 한 번 생성합니다.

```powershell
docker compose exec db createdb -U nabi_user nabi_test_db
docker compose run --rm -e APP_ENV=test backend pytest -p no:cacheprovider
docker compose run --rm --no-deps backend ruff check --no-cache app tests
```

`TEST_DATABASE_URL`의 DB 이름이 정확히 `nabi_test_db`가 아니면 테스트가 바로 중단됩니다. session fixture가 Alembic upgrade와 downgrade를 모두 실행하므로 migration의 양방향 동작도 함께 검증됩니다.

## Migration `20261031_0002`

- 기존 수면 기록은 변환합니다: `wake_time` → `final_awakening`(= `out_of_bed_time`), SE는 0~1 비율로, `sleep_date`는 기상일에서 밤 날짜로 바꿉니다.
- 기존 행동 기록의 `screen_time`, `nighttime_screen_time`, `steps`, `app_usage`는 명세에 대응하는 필드가 없어 **삭제**됩니다. 필요하면 migration 전에 `scripts/backup-db.sh`로 백업하십시오.
- `baselines`, `risk_analyses`는 다시 계산할 수 있는 파생 데이터라서 새 형식으로 다시 만듭니다.
- 기존 중재 4종(DIGITAL_USE 등)과 그 미션은 삭제하지 않고 비활성화합니다. 진행 중이던 assignment는 `EXPIRED`로 바꿉니다.

## 구현 전 확정 사항 (명세 §9) 중 이번 구현의 임시 결정

| 항목 | 임시 결정 | 위치 |
|---|---|---|
| 유효 기록 기준 | TIB 3~16시간, 14일 중 10일 | `.env` `BASELINE_MIN_VALID_DAYS`, `VALID_*` |
| 위험도 임계값 | 위 표 | `.env` `ROBUST_Z_*`, `risk_service.py` |
| 미션 완료 규칙 | 24시간 안에 작성된 기록으로 검증 | `mission_service.py` |
| LLM 모델·방식 | Qwen2.5-7B-Instruct(vLLM) 또는 외부 OpenAI 호환 API, 기본은 mock | `llm-server` |
| 대화 보존 기간·개인정보·위기 대응 절차 | 미확정. 대화는 삭제 정책 없이 저장되고, 위기 시 109·1577-0199·119를 안내 | 결정 후 별도 migration/API |
