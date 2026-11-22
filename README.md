# NABI server

NABI (Nighttime Adaptive Behavioral Intervention)의 FastAPI/PostgreSQL 백엔드와 내부 LLM 서버입니다. JWT 인증, 수면·행동 기록, 개인 baseline, robust risk 분석, 규칙 기반 CBT-I 중재, 미션·보상·고양이 성장, 안전성 검증을 거친 LLM 대화를 구현합니다.

- 백엔드 API·분석/중재 엔진: [`backend/README.md`](backend/README.md)
- LLM 서버: [`llm-server/README.md`](llm-server/README.md)
- Android 앱 (Kotlin, 화면 위 고양이): [`android/README.md`](android/README.md)

## 이 PC에서 서버 실행 (Windows)

1. [Docker Desktop](https://www.docker.com/products/docker-desktop/)을 설치하고 실행합니다 (처음 설치 시 WSL 2 설치·재부팅이 필요할 수 있음).
2. 저장소 루트에서:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\start-server.ps1
   ```
   `.env`가 없으면 무작위 비밀값으로 만들고, DB·LLM 서버(mock)·백엔드를 띄운 뒤 마이그레이션과 중재 카탈로그 시드까지 실행합니다.
3. 에뮬레이터 앱은 `http://10.0.2.2:8000`으로 자동 연결됩니다 (앱에서 서버 주소를 입력하지 않음 — `android/app/build.gradle.kts`의 `DEFAULT_BASE_URL`).
4. 중지: `docker compose down` (데이터는 볼륨에 남음)

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
├── android/      (Kotlin Android 앱 — Android Studio에서 이 폴더를 엽니다)
├── llm-server/
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── app/   (main, inference, prompt_builder, safety, response_validator, schemas, ...)
│   └── tests/
└── scripts/
    ├── start-server.ps1   (Windows: Docker Desktop으로 전체 서버 실행)
    ├── init.sh
    ├── backup-db.sh
    └── restore-db.sh
```


- 외부 트래픽에는 TLS reverse proxy, 인증, rate limit, 개인정보 로그 마스킹을 적용합니다.
- 현재 개발 기준은 `python:3.12-slim`, `postgres:17`입니다. 운영 릴리스에서는 검증한 patch tag와 digest(`image@sha256:...`)로 고정하고 CI에서 새 digest를 빌드·테스트·승격합니다.
- 백엔드 개발 단계에서는 Python lock/constraints 파일과 hash도 CI에서 고정합니다.
- `LLM_API_KEY`, JWT secret, DB URL을 로그나 오류 응답에 포함하지 않습니다.
