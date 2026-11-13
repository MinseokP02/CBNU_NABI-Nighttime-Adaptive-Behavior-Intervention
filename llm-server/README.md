# NABI LLM server

백엔드만 호출하는 내부 LLM API(명세 §5)입니다. 대화 응답, 중재 설명, 미션 메시지를 만들고 안전성과 구조를 검증합니다. 중재를 고르는 일은 하지 않습니다.

## Endpoints

모든 요청에 `X-Internal-Token: $LLM_INTERNAL_TOKEN` 헤더가 필요합니다. health만 예외입니다.

| Method | Endpoint | 기능 |
|---|---|---|
| POST | `/internal/v1/llm/generate` | 일반 응답 (LLM-01~03, 06~10) |
| POST | `/internal/v1/llm/intervention` | 승인된 중재 설명 (LLM-04) |
| POST | `/internal/v1/llm/mission` | 미션 시작·진행·완료 메시지 (LLM-05) |
| POST | `/internal/v1/llm/safety-check` | 입력/출력 안전성 검사 (LLM-08) |
| GET | `/internal/v1/llm/health` | 상태 점검 |

`/generate` 요청과 응답은 명세 §5.2 예시와 같습니다. 응답에는 명세 필드 외에 `source`(`llm` / `fallback` / `safety`)와 `model`이 더 들어갑니다. 요청 스키마는 정의되지 않은 필드를 거부하므로(`extra=forbid`), 이메일 같은 개인정보 필드가 실수로 전달되면 422가 납니다.

## 처리 흐름

```
check_input ──CRISIS──▶ 고정 위기 안내 (109 / 1577-0199 / 119), 추론 안 함
     │
classify_intent (키워드, LLM-01) ─▶ 기본 응답 문장 준비
     │
prompt_builder  ① System(역할·페르소나·의료 안전 규칙·JSON 형식) ② 개인 상태 ③ 승인된 중재·금지 사항 ④ 최근 대화
     │
inference       mock | openai_compatible (/chat/completions, JSON mode)
     │
response_validator  JSON 추출 → enum 검사 → intervention_category가 승인된 중재와 같은지 확인
     │  (실패 시 오류 내용을 알려 주고 1회 재시도)
check_output    약물·용량 / 진단 / 수면 제한 / 술 권유 / 위험 과장 표현 차단
     │
실패 → fallback (LLM-10, 항상 안전 검사를 통과하는 고정 문장)
```

- `safety_status`: `PASS`(안전) · `MODIFIED`(생성 결과가 안전하지 않아 고정 문장으로 바꿈) · `CRISIS`
- `cat_action`: `IDLE` `SIT` `WALK` `SLEEP` `HAPPY` `COMFORT`. 미션 완료 메시지는 항상 `HAPPY`입니다.
- 추론 시간 예산은 재시도를 포함해 `LLM_INFERENCE_TIMEOUT_SECONDS`(기본 8초)입니다. 백엔드 timeout은 12초로, 명세 NFR-02의 P95 10초 목표 안에 들어갑니다.
- 로그에는 request id, 경로, 상태, 지연 시간, fallback 사유만 남기고 사용자 발화는 남기지 않습니다.

## 모델 설정

| `LLM_PROVIDER` | 용도 | 추가 설정 |
|---|---|---|
| `mock` (기본) | GPU 없이 개발·테스트. 규칙 기반 고정 문장이 검증 파이프라인을 그대로 거칩니다 | – |
| `openai_compatible` | vLLM으로 자체 호스팅한 Qwen2.5-7B-Instruct, Ollama, 외부 API | `LLM_INFERENCE_URL`, `LLM_MODEL`, 필요하면 `LLM_INFERENCE_API_KEY` |

GPU 서버에서 Qwen을 자체 호스팅하는 방법:

```powershell
docker compose --profile gpu up -d vllm
# .env: LLM_PROVIDER=openai_compatible, LLM_INFERENCE_URL=http://vllm:8000/v1, LLM_MODEL=Qwen/Qwen2.5-7B-Instruct
docker compose up -d llm-server
```

JSON mode(`response_format`)를 지원하지 않는 서버라면 `LLM_JSON_MODE=false`로 설정하십시오. validator가 출력 텍스트에서 JSON을 추출합니다.

## Tests

```powershell
docker run --rm -v ${PWD}:/src -w /src python:3.12-slim sh -c "pip install -q -r requirements.txt && pytest -p no:cacheprovider && ruff check --no-cache app tests"
```

DB가 필요 없습니다. 외부 모델 호출은 mock으로 대체해 다음 경우를 검증합니다: 정상 응답, 잘못된 enum 뒤 재시도로 복구, 승인되지 않은 중재로 바꾼 응답, 의료 조언 응답, timeout, HTTP 500.
