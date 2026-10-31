"""LLM-09 structure validation: JSON parsing, schema/enum checks, intervention match."""
import json
import re

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from app.schemas import CatAction, Emotion, Intent, InterventionCategory

MAX_RESPONSE_CHARS = 300
_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)


class OutputInvalid(Exception):
    """Model output failed parsing or validation."""


class LLMOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    response: str = Field(min_length=1, max_length=MAX_RESPONSE_CHARS)
    intent: Intent
    emotion: Emotion
    cat_action: CatAction
    intervention_category: InterventionCategory | None = None

    @field_validator("response")
    @classmethod
    def _clean(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("empty response")
        if re.search(r"[{}<>]|```", value):
            raise ValueError("response contains markup")
        return value


def extract_json(text: str) -> dict:
    cleaned = _FENCE.sub("", text.strip())
    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError:
        start, end = cleaned.find("{"), cleaned.rfind("}")
        if start < 0 or end <= start:
            raise OutputInvalid("no JSON object in output") from None
        try:
            data = json.loads(cleaned[start : end + 1])
        except json.JSONDecodeError as exc:
            raise OutputInvalid(f"invalid JSON: {exc.msg}") from None
    if not isinstance(data, dict):
        raise OutputInvalid("output is not a JSON object")
    return data


def validate_output(
    text: str,
    expected_category: InterventionCategory | None,
    check_category: bool = True,
) -> LLMOutput:
    data = extract_json(text)
    try:
        output = LLMOutput.model_validate(data)
    except ValidationError as exc:
        first = exc.errors()[0]
        raise OutputInvalid(f"{'.'.join(str(p) for p in first['loc'])}: {first['msg']}") from None
    if check_category and output.intervention_category != expected_category:
        # The model must not switch to an intervention the engine did not approve.
        raise OutputInvalid(
            f"intervention_category mismatch: expected {expected_category}, got {output.intervention_category}"
        )
    return output
