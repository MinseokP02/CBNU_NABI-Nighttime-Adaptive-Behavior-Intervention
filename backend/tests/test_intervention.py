from app.models.intervention import Intervention
from app.services.intervention_service import select_intervention


def test_rule_based_intervention_matching(db):
    db.add(Intervention(category="DIGITAL_USE", rule="PRE_BED_PHONE_OVERUSE", title="Screen pause", message_template="Pause gently", active=True))
    db.commit()
    risk = {"risk_level": "WARNING", "sleep_state": "NORMAL_SLEEP", "behavior_state": "PRE_BED_PHONE_OVERUSE"}
    result = select_intervention(db, risk)
    assert result["category"] == "DIGITAL_USE"
    assert result["rule"] == "PRE_BED_PHONE_OVERUSE"
