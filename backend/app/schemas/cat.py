from pydantic import BaseModel

from app.schemas.chat import CatAction


class CatStateResponse(BaseModel):
    """API-17. The app plays the animation for `cat_action`; coordinates stay on Android."""

    cat_id: int
    name: str
    level: int
    exp: int
    exp_to_next_level: int
    cat_action: CatAction
    reason: str
