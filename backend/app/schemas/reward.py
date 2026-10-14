from datetime import datetime

from pydantic import BaseModel, ConfigDict


class RewardResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    result_id: int
    reward_type: str
    amount: int
    created_at: datetime
