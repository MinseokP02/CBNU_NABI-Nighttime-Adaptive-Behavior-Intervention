# NABI server

NABI (Nighttime Adaptive Behavioral Intervention)의 FastAPI/PostgreSQL 백엔드입니다. JWT 인증, 수면·행동 기록, 개인 baseline, robust risk 분석, 규칙 기반 중재, 미션·보상·고양이 성장, 안전한 LLM 개인화를 구현합니다.

API 명세와 백엔드 설계는 [`backend/README.md`](backend/README.md)를 참고하십시오. 실행 후 Swagger UI는 <http://127.0.0.1:8000/docs>에서 확인합니다.

## 구조

```text
.
├── .env.example
├── .gitignore
├── compose.yaml
├── compose.dev.yaml
├── README.md
├── backend/
│   ├── .dockerignore
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── pyproject.toml
│   ├── alembic.ini
│   ├── alembic/
│   ├── app/
│   └── tests/
└── scripts/
    ├── init.sh
    ├── backup-db.sh
    └── restore-db.sh
```

- `GET /health`: DB에 의존하지 않는 liveness
- `GET /ready`: PostgreSQL readiness
- schema 변경은 `backend/alembic/` migration으로만 수행

## 설계

- `db`는 PostgreSQL 17이며 named volume `postgres_data`에 데이터를 저장합니다.
- backend는 DB healthcheck 통과 후 시작하며 Compose 내부 DNS 이름 `db:5432`를 사용합니다.
- 기본 구성에서 DB 포트는 공개하지 않고 backend도 `127.0.0.1`에만 바인딩합니다.
- 개발 override에서만 실제 Android 기기용 backend 포트와 로컬 전용 DB 포트를 공개합니다.
- migration은 컨테이너 시작과 분리해 명시적으로 실행합니다.
- backend는 비루트 사용자, read-only root filesystem, capability 제거, `no-new-privileges`로 실행합니다.

## 환경 설정

`.env.example`을 `.env`로 복사하고 `POSTGRES_PASSWORD`, `JWT_SECRET_KEY`를 강한 값으로 변경합니다. `.env`는 Git에서 제외됩니다.

`POSTGRES_PASSWORD`와 `DATABASE_URL` 안의 비밀번호는 반드시 같아야 합니다. 후속 애플리케이션에서는 `DATABASE_URL`이 있으면 우선하고, 없으면 SQLAlchemy `URL.create()`로 개별 `DB_*`/`POSTGRES_*` 값을 조립하십시오. 문자열 보간은 `@`, `:`, `/`, `%` 같은 비밀번호 문자의 URL encoding 오류를 만들 수 있습니다.

### Windows PowerShell

```powershell
Copy-Item .env.example .env
# 편집기로 .env의 sample secret 변경
docker compose config
docker compose build
docker compose up -d db
docker compose ps
docker compose logs -f db
```

Migration, seed, backend 기동:

```powershell
docker compose run --rm backend alembic upgrade head
docker compose run --rm backend python -m app.seed.seed_interventions
docker compose up -d --build backend
docker compose exec backend alembic upgrade head
docker compose exec db psql -U nabi_user -d nabi_db
```

Android 실기기 개발 모드:

```powershell
docker compose -f compose.yaml -f compose.dev.yaml up -d --build
```

실기기는 `localhost` 대신 개발 PC의 LAN IP(예: `http://192.168.0.10:8000`)를 사용합니다. Android emulator는 일반적으로 `http://10.0.2.2:8000`을 사용합니다. Windows 방화벽에서 선택한 포트를 허용해야 할 수 있습니다.

### Linux 서버

```sh
chmod +x scripts/*.sh
./scripts/init.sh
# .env의 sample secret 변경
docker compose config
docker compose build
docker compose up -d db
docker compose ps
docker compose logs -f db
```

백엔드 코드가 추가된 뒤:

```sh
docker compose up -d --build backend
docker compose exec backend alembic upgrade head
```

운영에서는 `compose.dev.yaml`을 사용하지 않습니다. API를 인터넷에 직접 publish하지 말고 TLS를 종료하는 Nginx, Caddy 또는 cloud load balancer 뒤에 둡니다.

종료는 `docker compose down`을 사용하며 named volume은 보존됩니다.

> **데이터 삭제 경고:** `docker compose down -v`는 `postgres_data`와 DB 데이터를 삭제합니다. 검증된 백업 없이 실행하지 마십시오.

## 개발 override

`!override`를 지원하는 Docker Compose v2.24.4 이상이 필요합니다.

```sh
docker compose -f compose.yaml -f compose.dev.yaml config
docker compose -f compose.yaml -f compose.dev.yaml up -d --build
```

이 모드는 `backend/app`을 `/app/app`에 bind mount하고 `uvicorn --reload`를 실행합니다. DB host port가 필요 없으면 `compose.dev.yaml`의 `db.ports`를 제거하거나 개인 override로 관리합니다.

## 백업과 복원

스크립트는 비밀번호를 명령행 인자로 전달하지 않습니다. DB 컨테이너 환경과 로컬 인증을 사용합니다. 기본 백업은 PostgreSQL custom format입니다.

```sh
./scripts/backup-db.sh
./scripts/backup-db.sh /secure/path/nabi.dump
./scripts/restore-db.sh backups/nabi_YYYYMMDDTHHMMSSZ.dump
./scripts/restore-db.sh legacy-backup.sql
```

복원은 기존 객체를 교체할 수 있습니다. 대상 DB와 파일을 확인하고 별도 환경에서 먼저 복원 테스트를 하십시오.

PowerShell에서는 바이너리 리디렉션 호환성 문제를 피하도록 컨테이너 안에서 만든 뒤 복사합니다.

```powershell
docker compose exec -T db pg_dump -U nabi_user -d nabi_db -Fc -f /tmp/nabi.dump
docker compose cp db:/tmp/nabi.dump ./nabi.dump
docker compose exec -T db rm /tmp/nabi.dump
```

## 검증

```sh
docker compose config
docker compose build backend
docker compose up -d db
docker compose ps
docker compose exec db pg_isready -U nabi_user -d nabi_db
docker compose exec db psql -U nabi_user -d nabi_db -c 'select current_database(), current_user;'
docker compose exec db getent hosts db
docker volume inspect nabi_postgres_data
```

백엔드 코드가 추가되면 secret 값은 출력하지 않고 전달 여부만 확인합니다.

```sh
docker compose run --rm --no-deps backend sh -c \
  'test -n "$DATABASE_URL" && test "$DB_HOST" = db && test "$DB_PORT" = 5432'
```

영속성은 테스트 테이블 생성 후 `docker compose down`, `docker compose up -d db`를 수행해 확인합니다.

## 흔한 오류

- `POSTGRES_* must be set`: `.env`가 없거나 값이 비었습니다. `.env.example`을 복사합니다.
- `ModuleNotFoundError: app`: 현재 인프라 단계의 의도된 제한입니다. 후속 단계에서 `backend/app/main.py`를 추가합니다.
- `!override` 태그 오류: Docker Compose를 v2.24.4 이상으로 업데이트합니다.
- `port is already allocated`: 8000/5432 사용 프로세스를 종료하거나 host port를 변경합니다.
- DB가 unhealthy: `docker compose logs db`에서 자격 증명과 volume 권한을 확인합니다.
- `password authentication failed`: `POSTGRES_PASSWORD`와 `DATABASE_URL`이 다르거나 기존 volume이 이전 비밀번호로 초기화됐습니다. 데이터가 필요하면 DB 비밀번호를 변경하고, 불필요한 개발 데이터일 때만 백업 후 volume을 재생성합니다.
- Android 연결 실패: PC LAN IP, 동일 네트워크, OS 방화벽, 공유기의 client isolation을 확인합니다.
- CORS 오류: origin을 정확히 나열하며 credentials 허용과 wildcard origin을 함께 쓰지 않습니다.

## 운영 보안과 버전 고정

- secret은 이미지, Compose 파일, Git에 넣지 않고 secret manager나 권한 제한 env file로 주입합니다.
- 외부 트래픽에는 TLS reverse proxy, 인증, rate limit, 개인정보 로그 마스킹을 적용합니다.
- 현재 개발 기준은 `python:3.12-slim`, `postgres:17`입니다. 운영 릴리스에서는 검증한 patch tag와 digest(`image@sha256:...`)로 고정하고 CI에서 새 digest를 빌드·테스트·승격합니다.
- 백엔드 개발 단계에서는 Python lock/constraints 파일과 hash도 CI에서 고정합니다.
- `LLM_API_KEY`, JWT secret, DB URL을 로그나 오류 응답에 포함하지 않습니다.
