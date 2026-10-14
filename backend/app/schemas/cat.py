from datetime import datetime

from pydantic import BaseModel, ConfigDict


class CatResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    cat_id: int
    name: str
    level: int
    exp: int
    updated_at: datetime
