# NABI server

NABI (Nighttime Adaptive Behavioral Intervention)의 FastAPI/PostgreSQL 백엔드입니다. JWT 인증, 수면·행동 기록, 개인 baseline, robust risk 분석, 규칙 기반 중재, 미션·보상·고양이 성장, 안전한 LLM 개인화를 구현합니다.

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


- 외부 트래픽에는 TLS reverse proxy, 인증, rate limit, 개인정보 로그 마스킹을 적용합니다.
- 현재 개발 기준은 `python:3.12-slim`, `postgres:17`입니다. 운영 릴리스에서는 검증한 patch tag와 digest(`image@sha256:...`)로 고정하고 CI에서 새 digest를 빌드·테스트·승격합니다.
- 백엔드 개발 단계에서는 Python lock/constraints 파일과 hash도 CI에서 고정합니다.
- `LLM_API_KEY`, JWT secret, DB URL을 로그나 오류 응답에 포함하지 않습니다.
