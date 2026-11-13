"""Model inference with structured-output validation and one repair retry.

Providers
- mock: returns the deterministic template output as JSON. Lets the whole pipeline
  (validation, safety, API contract) run without a GPU.
- openai_compatible: POST {LLM_INFERENCE_URL}/chat/completions. Works with vLLM serving
  Qwen2.5-7B-Instruct, Ollama's OpenAI endpoint, or an external API.
"""
import json
import logging
import time
from dataclasses import dataclass

import httpx

from app.config import Settings
from app.prompt_builder import repair_message
from app.response_validator import LLMOutput, OutputInvalid, validate_output
from app.safety import check_output
from app.schemas import InterventionCategory

logger = logging.getLogger("nabi.llm.inference")


class InferenceError(Exception):
    """The model could not be reached or returned an unusable HTTP payload."""


@dataclass
class StructuredResult:
    output: LLMOutput | None   # None => caller must use fallback
    source: str                # "llm" | "fallback"
    failure: str | None = None  # reason category for logs (never contains user text)
    unsafe: bool = False        # True when the output was rejected by the safety check


class InferenceEngine:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self._client = client

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()

    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient()
        return self._client

    async def _complete(self, messages: list[dict], mock_payload: dict, timeout: float) -> str:
        if self.settings.llm_provider == "mock":
            return json.dumps(mock_payload, ensure_ascii=False)
        if not self.settings.llm_inference_url:
            raise InferenceError("LLM_INFERENCE_URL is not configured")
        body: dict = {
            "model": self.settings.llm_model,
            "messages": messages,
            "temperature": self.settings.llm_temperature,
            "max_tokens": self.settings.llm_max_tokens,
        }
        if self.settings.llm_json_mode:
            body["response_format"] = {"type": "json_object"}
        headers = {}
        if self.settings.llm_inference_api_key:
            headers["Authorization"] = f"Bearer {self.settings.llm_inference_api_key}"
        url = f"{self.settings.llm_inference_url.rstrip('/')}/chat/completions"
        try:
            response = await self._http().post(url, json=body, headers=headers, timeout=timeout)
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
        except httpx.TimeoutException:
            raise InferenceError("timeout") from None
        except httpx.HTTPStatusError as exc:
            raise InferenceError(f"http {exc.response.status_code}") from None
        except httpx.HTTPError as exc:
            raise InferenceError(type(exc).__name__) from None
        except (KeyError, IndexError, TypeError, ValueError):
            raise InferenceError("malformed completion payload") from None
        if not isinstance(content, str) or not content.strip():
            raise InferenceError("empty completion")
        return content

    async def generate_structured(
        self,
        messages: list[dict],
        *,
        mock_payload: dict,
        expected_category: InterventionCategory | None,
        check_category: bool = True,
    ) -> StructuredResult:
        deadline = time.monotonic() + self.settings.llm_inference_timeout_seconds
        conversation = list(messages)
        last_failure = "unknown"
        for attempt in range(self.settings.llm_max_retries + 1):
            remaining = deadline - time.monotonic()
            if remaining <= 0.2:
                last_failure = "timeout_budget"
                break
            try:
                raw = await self._complete(conversation, mock_payload, remaining)
            except InferenceError as exc:
                # Connection-level failures are not retried: retrying rarely helps within budget.
                logger.warning("inference failed attempt=%d reason=%s", attempt, exc)
                return StructuredResult(None, "fallback", failure=f"inference:{exc}")
            try:
                output = validate_output(raw, expected_category, check_category)
            except OutputInvalid as exc:
                last_failure = f"invalid:{exc}"
                logger.info("output invalid attempt=%d reason=%s", attempt, exc)
                conversation = [*conversation, {"role": "assistant", "content": raw[:1000]}, repair_message(str(exc))]
                continue
            safety = check_output(output.response)
            if not safety.allowed:
                logger.info("output unsafe categories=%s", ",".join(safety.categories))
                return StructuredResult(None, "fallback", failure="unsafe:" + ",".join(safety.categories), unsafe=True)
            return StructuredResult(output, "llm")
        return StructuredResult(None, "fallback", failure=last_failure)
