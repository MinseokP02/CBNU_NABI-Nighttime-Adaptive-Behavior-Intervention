"""Request/response contracts for the internal LLM API (spec §5)."""
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


class Intent(StrEnum):
    SLEEP_COMPLAINT = "SLEEP_COMPLAINT"
    SLEEP_WORRY = "SLEEP_WORRY"
    STRESS = "STRESS"
    MISSION_QUERY = "MISSION_QUERY"
    MEDICAL_QUESTION = "MEDICAL_QUESTION"
    GREETING = "GREETING"
    GRATITUDE = "GRATITUDE"
    SMALL_TALK = "SMALL_TALK"
    CRISIS = "CRISIS"
    OTHER = "OTHER"


class Emotion(StrEnum):
    EMPATHETIC = "EMPATHETIC"
    CALM = "CALM"
    ENCOURAGING = "ENCOURAGING"
    HAPPY = "HAPPY"
    CONCERNED = "CONCERNED"
    NEUTRAL = "NEUTRAL"


class CatAction(StrEnum):
    IDLE = "IDLE"
    SIT = "SIT"
    WALK = "WALK"
    SLEEP = "SLEEP"
    HAPPY = "HAPPY"
    COMFORT = "COMFORT"


class InterventionCategory(StrEnum):
    STIMULUS_CONTROL = "STIMULUS_CONTROL"
    SLEEP_SCHEDULE = "SLEEP_SCHEDULE"
    RELAXATION = "RELAXATION"
    COGNITIVE = "COGNITIVE"
    SLEEP_HYGIENE = "SLEEP_HYGIENE"


class SafetyStatus(StrEnum):
    PASS = "PASS"            # output used as generated
    MODIFIED = "MODIFIED"    # unsafe/invalid output replaced by a safe fixed sentence
    CRISIS = "CRISIS"        # crisis signal: fixed crisis guidance, no inference


class SleepState(StrEnum):
    UNKNOWN = "UNKNOWN"
    STABLE = "STABLE"
    IMPROVING = "IMPROVING"
    DETERIORATING = "DETERIORATING"


class BehaviorState(StrEnum):
    UNKNOWN = "UNKNOWN"
    HEALTHY = "HEALTHY"
    CAUTION = "CAUTION"
    AT_RISK = "AT_RISK"


class RiskLevel(StrEnum):
    UNKNOWN = "UNKNOWN"
    LOW = "LOW"
    MODERATE = "MODERATE"
    HIGH = "HIGH"


class CatPersona(StrEnum):
    FRIENDLY_SUPPORTIVE = "friendly_supportive"
    CALM_GENTLE = "calm_gentle"
    PLAYFUL = "playful"


class MissionPhase(StrEnum):
    START = "START"
    PROGRESS = "PROGRESS"
    COMPLETE = "COMPLETE"


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class BaselineSummary(_Strict):
    median_sleep_min: float | None = None
    median_efficiency: float | None = Field(default=None, ge=0, le=1)
    median_sleep_onset_min: float | None = None


class PersonalContext(_Strict):
    sleep_state: SleepState = SleepState.UNKNOWN
    behavior_state: BehaviorState = BehaviorState.UNKNOWN
    risk_level: RiskLevel = RiskLevel.UNKNOWN
    risk_factors: list[str] = Field(default_factory=list, max_length=12)
    baseline_summary: BaselineSummary | None = None


class ApprovedIntervention(_Strict):
    intervention_id: int | None = None
    category: InterventionCategory
    instruction: str = Field(min_length=1, max_length=300)
    reason_codes: list[str] = Field(default_factory=list, max_length=12)
    prohibited: list[str] = Field(default_factory=list, max_length=12)


class HistoryTurn(_Strict):
    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(min_length=1, max_length=1000)


class GenerateRequest(_Strict):
    request_id: str = Field(min_length=1, max_length=64)
    user_message: str = Field(min_length=1, max_length=1000)
    context: PersonalContext = Field(default_factory=PersonalContext)
    intervention: ApprovedIntervention | None = None
    recent_history: list[HistoryTurn] = Field(default_factory=list, max_length=20)
    cat_persona: CatPersona = CatPersona.FRIENDLY_SUPPORTIVE


class GenerateResponse(BaseModel):
    request_id: str
    response: str
    intent: Intent
    emotion: Emotion
    cat_action: CatAction
    intervention_category: InterventionCategory | None
    safety_status: SafetyStatus
    source: str  # "llm" | "fallback" | "safety"
    model: str


class InterventionRequest(_Strict):
    request_id: str = Field(min_length=1, max_length=64)
    intervention: ApprovedIntervention
    context: PersonalContext = Field(default_factory=PersonalContext)
    cat_persona: CatPersona = CatPersona.FRIENDLY_SUPPORTIVE


class InterventionResponse(BaseModel):
    request_id: str
    guidance: str
    emotion: Emotion
    cat_action: CatAction
    intervention_category: InterventionCategory
    safety_status: SafetyStatus
    source: str
    model: str


class MissionInfo(_Strict):
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=500)
    category: InterventionCategory | None = None


class MissionRequest(_Strict):
    request_id: str = Field(min_length=1, max_length=64)
    phase: MissionPhase
    mission: MissionInfo
    reward_exp: int | None = Field(default=None, ge=0)
    cat_persona: CatPersona = CatPersona.FRIENDLY_SUPPORTIVE


class MissionResponse(BaseModel):
    request_id: str
    message: str
    emotion: Emotion
    cat_action: CatAction
    safety_status: SafetyStatus
    source: str
    model: str


class SafetyCheckRequest(_Strict):
    request_id: str = Field(min_length=1, max_length=64)
    text: str = Field(min_length=1, max_length=2000)
    direction: str = Field(default="INPUT", pattern="^(INPUT|OUTPUT)$")


class SafetyCheckResponse(BaseModel):
    request_id: str
    safety_status: SafetyStatus
    categories: list[str]
    allowed: bool


class HealthResponse(BaseModel):
    status: str
    provider: str
    model: str
