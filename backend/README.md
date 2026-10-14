# NABI backend

## Architecture

요청 흐름은 `router → service → repository → SQLAlchemy Session → PostgreSQL`입니다. 모든 개인 데이터 API는 JWT `sub`의 UUID를 사용하며 URL의 다른 사용자 UUID로 접근하면 403을 반환합니다. 서버 timestamp는 UTC `TIMESTAMPTZ`로 저장하고 수면 날짜와 시계 시각 해석에 사용자 timezone을 적용합니다.

분석은 조회 시점에 최신 기록을 확인하는 MVP 방식입니다. 기록 생성 요청 안에서 분석을 실행하지 않으므로 수집 실패와 분석 실패를 분리합니다.

## Startup on Windows PowerShell

```powershell
Copy-Item .env.example .env   # 이미 있으면 생략
# POSTGRES_PASSWORD, DATABASE_URL, TEST_DATABASE_URL, JWT_SECRET_KEY 변경
docker compose up -d db
docker compose run --rm backend alembic upgrade head
docker compose run --rm backend python -m app.seed.seed_interventions
docker compose up -d --build backend
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8000/ready
```

Swagger UI: <http://127.0.0.1:8000/docs>

## API

- `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me`
- `POST /api/sleep-records`, `POST /api/behavior-records`
- `GET /api/users/{user_id}/state`
- `GET /api/analysis/baseline`, `GET /api/analysis/risk`
- `GET /api/missions/active`, `POST /api/missions/{assignment_id}/complete`
- `GET /api/cat/profile`, `GET /api/rewards/history`
- `POST /api/chat`
- `GET /health`, `GET /ready`

### Register example

```http
POST /api/auth/register
Content-Type: application/json

{"email":"nabi@example.com","password":"a-long-private-password","isi_score":10,"timezone":"Asia/Seoul","cat_name":"나비"}
```

```json
{"user_id":"6f5e7ee9-3b83-4a50-9a2f-a48ce0cb5bcc","email":"nabi@example.com","isi_score":10,"timezone":"Asia/Seoul","profile":null,"created_at":"2026-10-14T04:00:00Z"}
```

### Sleep record example

```http
POST /api/sleep-records
Authorization: Bearer <access-token>
Content-Type: application/json

{"sleep_date":"2026-10-14","bedtime":"2026-10-13T23:00:00+09:00","wake_time":"2026-10-14T07:00:00+09:00","sol":20,"waso":10,"tst":450,"se":93.75,"sleep_quality":4,"source":"android"}
```

중복 날짜는 409이며 `wake_time > bedtime`, 음이 아닌 duration, `TST <= time_in_bed`, 계산 SE와 입력 SE의 1%p 이내 일치를 검증합니다. `sleep_date`는 사용자 timezone의 기상일이어야 합니다.

## Baseline and risk

최근 7일 분석 구간 이전의 기록만 baseline에 사용하므로 데이터 누수가 없습니다. metric마다 유효한 14개 표본을 별도로 요구하며 빈 날을 0으로 채우지 않습니다.

`bedtime`과 `wake_time`은 각각 사용자 profile의 `target_bedtime`, `target_wake_time`을 기준으로 `[-720, 720)`분 offset으로 변환합니다. profile 값이 없으면 `23:00`, `07:00`을 사용합니다. 이 방식은 자정 전후 시각을 연속적으로 비교합니다.

MAD는 `median(abs(x - median(x)))`입니다. MAD가 0이고 최근 값도 같으면 z=0, 다르면 `DEVIATION_UNSCORABLE`로 처리합니다. 작은 epsilon으로 과장된 위험을 만들지 않습니다. WARNING/HIGH 경계값은 임상 진단이 아닌 MVP 변화 탐지 설정입니다.

대표 상태는 robust z가 큰 항목을 우선하고 동률이면 고정 metric 우선순위를 사용합니다. 설명은 수면과 행동 변화가 함께 관찰됐다고만 표현하고 인과관계를 단정하지 않습니다.

## Mission, reward, and cat

`GET /api/missions/active`는 활성 assignment가 없으면 seed mission 하나를 발급합니다. 완료 API의 ID는 mission ID가 아니라 `assignment_id`입니다.

완료 처리에서 assignment와 cat row를 `SELECT FOR UPDATE`로 잠그고 검증 결과, 상태 변경, EXP transaction, level-up을 한 DB transaction으로 커밋합니다. result/보상 UNIQUE 제약과 기존 result 반환으로 반복 요청의 중복 지급을 방지합니다.

기록의 생성 시각과 source metadata를 확인하지만 Android 기기 데이터 자체의 위변조 방지를 보증하지는 않습니다. `NIGHTTIME_SCREEN_TIME` seed mission은 일일 야간 총량 조건이며, 취침 전 30분 구간 데이터가 없는 상태에서 그 조건을 검증했다고 주장하지 않습니다.

## LLM safety

중재 선택은 서버 규칙 엔진만 수행합니다. LLM은 선택된 문구를 1~2문장으로 다듬을 뿐이며 진단·처방·약물 권고·위험 과장을 금지합니다. 전송 context에는 이메일/UUID/원시 일지를 넣지 않습니다. 비활성화, timeout, HTTP 오류, 비정상 JSON 응답은 모두 deterministic fallback으로 처리합니다. 외부 호출 전에 DB session을 닫아 장시간 transaction을 유지하지 않습니다.

## PostgreSQL tests

테스트는 SQLite를 사용하지 않습니다. 전용 DB가 없으면 다음처럼 한 번 생성합니다.

```powershell
docker compose exec db createdb -U nabi_user nabi_test_db
docker compose run --rm -e APP_ENV=test backend pytest -p no:cacheprovider
docker compose run --rm --no-deps backend ruff check --no-cache app tests
```

`TEST_DATABASE_URL`의 database 이름이 정확히 `nabi_test_db`가 아니면 테스트가 즉시 중단됩니다. session fixture가 Alembic upgrade를 수행하고 종료 시 downgrade합니다.

## Seed and migration

```powershell
docker compose run --rm backend alembic current
docker compose run --rm backend alembic upgrade head
docker compose run --rm backend python -m app.seed.seed_interventions
```

Seed는 DIGITAL_USE, PHYSICAL_ACTIVITY, BEDTIME_ROUTINE, SLEEP_ROUTINE 중재와 대응 mission을 category/title 기준으로 멱등 생성합니다.

## Data lifecycle

현재 구현은 최소 수집 원칙으로 분석에 필요한 데이터만 저장합니다. 사용자 삭제, export, 법적 보존 기간, soft-delete 여부, 백업에서의 삭제 전파는 제품·연구윤리·관할 법규 결정 후 별도 migration/API로 설계해야 합니다. 확정 전 임의 자동 삭제는 수행하지 않습니다.
