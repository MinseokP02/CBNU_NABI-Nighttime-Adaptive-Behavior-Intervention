from pydantic import BaseModel


class RiskResponse(BaseModel):
    risk_level: str
    sleep_state: str
    behavior_state: str
    risk_factors: list[str]
    scores: dict
    explanation: str
    available_days: int
    required_days: int
