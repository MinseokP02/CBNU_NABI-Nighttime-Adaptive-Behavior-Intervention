from pydantic import BaseModel


class InterventionSelection(BaseModel):
    intervention_id: int | None
    category: str
    rule: str
    title: str
    message: str
    context: dict
