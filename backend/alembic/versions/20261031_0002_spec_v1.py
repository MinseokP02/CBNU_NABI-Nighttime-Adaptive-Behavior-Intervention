"""Align schema with API & LLM server spec v1.0.

- sleep_records: spec diary fields (SOL, WASO, final awakening, out-of-bed), computed TIB/TST/SE.
  Existing rows are converted (out_of_bed_time := wake_time, SE as 0-1 ratio, sleep_date := night date).
- behavior_records: spec fields (caffeine, pre-bed screen, nap, exercise, stress, relaxation).
  The previous screen_time/nighttime_screen_time/steps/app_usage columns have no spec equivalent
  and are dropped.
- baselines, risk_analyses: derived data, recreated in the new snapshot format.
- interventions: catalog keyed by `code`; legacy categories are deactivated, not deleted.
- conversations, chat_messages: chat history (API-15/16).

Revision ID: 20261031_0002
"""
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "20261031_0002"
down_revision = "20261014_0001"
branch_labels = None
depends_on = None

LEGACY_CATEGORIES = "('DIGITAL_USE', 'PHYSICAL_ACTIVITY', 'BEDTIME_ROUTINE', 'SLEEP_ROUTINE')"
UUID = postgresql.UUID(as_uuid=True)


def _baselines_v2() -> None:
    op.create_table(
        "baselines",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", UUID, sa.ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("window_start", sa.Date()),
        sa.Column("window_end", sa.Date()),
        sa.Column("window_days", sa.Integer(), nullable=False),
        sa.Column("valid_days", sa.Integer(), nullable=False),
        sa.Column("metrics", postgresql.JSONB(), nullable=False),
        sa.Column("fingerprint", sa.String(64), nullable=False),
        sa.Column("analysis_version", sa.String(16), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_baselines_user_id", "baselines", ["user_id"])


def _risk_v2() -> None:
    op.create_table(
        "risk_analyses",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", UUID, sa.ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("baseline_id", sa.BigInteger(), sa.ForeignKey("baselines.id", ondelete="SET NULL")),
        sa.Column("window_days", sa.Integer(), nullable=False),
        sa.Column("reference_date", sa.Date()),
        sa.Column("risk_level", sa.String(32), nullable=False),
        sa.Column("sleep_state", sa.String(32), nullable=False),
        sa.Column("behavior_state", sa.String(32), nullable=False),
        sa.Column("risk_factors", postgresql.JSONB(), nullable=False),
        sa.Column("details", postgresql.JSONB(), nullable=False),
        sa.Column("analysis_version", sa.String(16), nullable=False),
        sa.Column("analyzed_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_risk_analyses_user_id", "risk_analyses", ["user_id"])
    op.create_index("ix_risk_analyses_analyzed_at", "risk_analyses", ["analyzed_at"])


def upgrade() -> None:
    # --- sleep_records -------------------------------------------------------------------------
    for name in ("ck_sleep_order", "ck_sleep_durations", "ck_sleep_se"):
        op.drop_constraint(name, "sleep_records", type_="check")
    op.drop_constraint("uq_sleep_user_date", "sleep_records", type_="unique")
    op.alter_column("sleep_records", "wake_time", new_column_name="final_awakening")
    op.alter_column("sleep_records", "sol", type_=sa.Integer(), postgresql_using="round(sol)::integer")
    op.alter_column("sleep_records", "sol", new_column_name="sleep_onset_latency_min")
    op.alter_column("sleep_records", "waso", type_=sa.Integer(), postgresql_using="round(waso)::integer")
    op.alter_column("sleep_records", "waso", new_column_name="wake_after_sleep_onset_min")
    op.add_column("sleep_records", sa.Column("out_of_bed_time", sa.DateTime(timezone=True)))
    op.add_column("sleep_records", sa.Column("time_in_bed_min", sa.Integer()))
    op.add_column("sleep_records", sa.Column("total_sleep_time_min", sa.Integer()))
    op.add_column("sleep_records", sa.Column("sleep_efficiency", sa.Float()))
    op.execute("UPDATE sleep_records SET out_of_bed_time = final_awakening")
    op.execute(
        """
        UPDATE sleep_records SET
          time_in_bed_min = GREATEST(1, round(extract(epoch FROM (out_of_bed_time - bedtime)) / 60)::integer),
          total_sleep_time_min = GREATEST(0, round(extract(epoch FROM (final_awakening - bedtime)) / 60)::integer
                                 - sleep_onset_latency_min - wake_after_sleep_onset_min)
        """
    )
    op.execute("UPDATE sleep_records SET sleep_efficiency = round(total_sleep_time_min::numeric / time_in_bed_min, 4)")
    # sleep_date was the local wake date; the spec uses the night date (bedtime - 12h, local).
    op.execute(
        """
        UPDATE sleep_records s
        SET sleep_date = ((s.bedtime AT TIME ZONE u.timezone) - interval '12 hours')::date
        FROM users u WHERE u.user_id = s.user_id
        """
    )
    for column in ("out_of_bed_time", "time_in_bed_min", "total_sleep_time_min", "sleep_efficiency"):
        op.alter_column("sleep_records", column, nullable=False)
    op.drop_column("sleep_records", "tst")
    op.drop_column("sleep_records", "se")
    op.alter_column("sleep_records", "source", server_default="app")
    op.create_unique_constraint("uq_sleep_user_date", "sleep_records", ["user_id", "sleep_date"])
    op.create_check_constraint("ck_sleep_order", "sleep_records", "final_awakening > bedtime AND out_of_bed_time >= final_awakening")
    op.create_check_constraint(
        "ck_sleep_durations", "sleep_records",
        "sleep_onset_latency_min >= 0 AND wake_after_sleep_onset_min >= 0 AND total_sleep_time_min >= 0 AND time_in_bed_min > 0",
    )
    op.create_check_constraint("ck_sleep_efficiency", "sleep_records", "sleep_efficiency BETWEEN 0 AND 1")

    # --- behavior_records ----------------------------------------------------------------------
    op.drop_constraint("ck_behavior_screen", "behavior_records", type_="check")
    op.drop_constraint("ck_behavior_steps", "behavior_records", type_="check")
    for column in ("screen_time", "nighttime_screen_time", "steps", "app_usage"):
        op.drop_column("behavior_records", column)
    op.add_column("behavior_records", sa.Column("caffeine_after_cutoff", sa.Boolean()))
    op.add_column("behavior_records", sa.Column("screen_before_bed_min", sa.Integer()))
    op.add_column("behavior_records", sa.Column("nap_duration_min", sa.Integer()))
    op.add_column("behavior_records", sa.Column("exercise_duration_min", sa.Integer()))
    op.add_column("behavior_records", sa.Column("stress_level", sa.Integer()))
    op.add_column("behavior_records", sa.Column("relaxation_completed", sa.Boolean()))
    op.add_column("behavior_records", sa.Column("source", sa.String(50), nullable=False, server_default="app"))
    op.create_check_constraint(
        "ck_behavior_minutes", "behavior_records",
        "(screen_before_bed_min IS NULL OR screen_before_bed_min BETWEEN 0 AND 1440) AND "
        "(nap_duration_min IS NULL OR nap_duration_min BETWEEN 0 AND 1440) AND "
        "(exercise_duration_min IS NULL OR exercise_duration_min BETWEEN 0 AND 1440)",
    )
    op.create_check_constraint("ck_behavior_stress", "behavior_records", "stress_level IS NULL OR stress_level BETWEEN 1 AND 5")

    # --- derived analysis tables ---------------------------------------------------------------
    op.drop_table("risk_analyses")
    op.drop_table("baselines")
    _baselines_v2()
    _risk_v2()

    # --- interventions catalog -----------------------------------------------------------------
    op.drop_constraint("interventions_category_key", "interventions", type_="unique")
    op.alter_column("interventions", "rule", new_column_name="code")
    op.execute("ALTER TABLE interventions RENAME CONSTRAINT interventions_rule_key TO interventions_code_key")
    op.add_column("interventions", sa.Column("instruction", sa.Text(), nullable=False, server_default=""))
    op.add_column("interventions", sa.Column("prohibited", postgresql.JSONB(), nullable=False, server_default=sa.text("'[]'::jsonb")))
    op.add_column("interventions", sa.Column("requires_expert_review", sa.Boolean(), nullable=False, server_default=sa.false()))
    op.create_index("ix_interventions_category", "interventions", ["category"])
    op.execute(f"UPDATE interventions SET active = false WHERE category IN {LEGACY_CATEGORIES}")
    op.execute("UPDATE missions SET active = false WHERE intervention_id IN (SELECT intervention_id FROM interventions WHERE active = false)")
    op.execute("UPDATE mission_assignments SET status = 'EXPIRED' WHERE status = 'ASSIGNED' AND mission_id IN (SELECT mission_id FROM missions WHERE active = false)")
    op.create_index("ix_mission_assignments_user_mission", "mission_assignments", ["user_id", "mission_id"])

    # --- chat ----------------------------------------------------------------------------------
    op.create_table(
        "conversations",
        sa.Column("conversation_id", sa.String(40), primary_key=True),
        sa.Column("user_id", UUID, sa.ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_conversations_user_id", "conversations", ["user_id"])
    op.create_table(
        "chat_messages",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("conversation_id", sa.String(40), sa.ForeignKey("conversations.conversation_id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", UUID, sa.ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("intent", sa.String(32)),
        sa.Column("emotion", sa.String(32)),
        sa.Column("cat_action", sa.String(16)),
        sa.Column("related_intervention_id", sa.BigInteger(), sa.ForeignKey("interventions.intervention_id", ondelete="SET NULL")),
        sa.Column("safety_status", sa.String(16)),
        sa.Column("source", sa.String(16)),
        sa.Column("request_id", sa.String(64)),
        sa.Column("latency_ms", sa.Integer()),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("role IN ('user', 'assistant')", name="ck_chat_role"),
    )
    op.create_index("ix_chat_messages_conversation_id", "chat_messages", ["conversation_id"])
    op.create_index("ix_chat_messages_user_id", "chat_messages", ["user_id"])
    op.create_index("ix_chat_messages_created_at", "chat_messages", ["created_at"])


def downgrade() -> None:
    """Lossy: v1.0 catalog rows, chat history and new behavior fields are discarded."""
    op.drop_table("chat_messages")
    op.drop_table("conversations")
    op.drop_index("ix_mission_assignments_user_mission", table_name="mission_assignments")

    non_legacy = f"SELECT intervention_id FROM interventions WHERE category NOT IN {LEGACY_CATEGORIES}"
    non_legacy_missions = f"SELECT mission_id FROM missions WHERE intervention_id IN ({non_legacy})"
    op.execute(
        f"""DELETE FROM reward_transactions WHERE result_id IN (
              SELECT r.result_id FROM mission_results r JOIN mission_assignments a ON a.assignment_id = r.assignment_id
              WHERE a.mission_id IN ({non_legacy_missions}))"""
    )
    op.execute(f"DELETE FROM mission_assignments WHERE mission_id IN ({non_legacy_missions})")
    op.execute(f"DELETE FROM missions WHERE intervention_id IN ({non_legacy})")
    op.execute(f"DELETE FROM interventions WHERE category NOT IN {LEGACY_CATEGORIES}")
    op.execute("UPDATE interventions SET active = true")
    op.execute("UPDATE missions SET active = true")
    op.drop_index("ix_interventions_category", table_name="interventions")
    for column in ("instruction", "prohibited", "requires_expert_review"):
        op.drop_column("interventions", column)
    op.execute("ALTER TABLE interventions RENAME CONSTRAINT interventions_code_key TO interventions_rule_key")
    op.alter_column("interventions", "code", new_column_name="rule")
    op.create_unique_constraint("interventions_category_key", "interventions", ["category"])

    op.drop_table("risk_analyses")
    op.drop_table("baselines")
    op.create_table(
        "baselines",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", UUID, sa.ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("metric_name", sa.String(64), nullable=False), sa.Column("median", sa.Float(), nullable=False),
        sa.Column("mad", sa.Float(), nullable=False), sa.Column("sample_count", sa.Integer(), nullable=False),
        sa.Column("window_start", sa.Date(), nullable=False), sa.Column("window_end", sa.Date(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("user_id", "metric_name", name="uq_baseline_user_metric"),
    )
    op.create_index("ix_baselines_user_id", "baselines", ["user_id"])
    op.create_table(
        "risk_analyses",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", UUID, sa.ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("risk_level", sa.String(32), nullable=False), sa.Column("sleep_state", sa.String(64), nullable=False),
        sa.Column("behavior_state", sa.String(64), nullable=False), sa.Column("risk_factor", sa.String(128), nullable=False),
        sa.Column("scores", postgresql.JSONB(), nullable=False), sa.Column("explanation", sa.Text(), nullable=False),
        sa.Column("analyzed_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_risk_analyses_user_id", "risk_analyses", ["user_id"])
    op.create_index("ix_risk_analyses_analyzed_at", "risk_analyses", ["analyzed_at"])

    op.drop_constraint("ck_behavior_minutes", "behavior_records", type_="check")
    op.drop_constraint("ck_behavior_stress", "behavior_records", type_="check")
    for column in ("caffeine_after_cutoff", "screen_before_bed_min", "nap_duration_min", "exercise_duration_min", "stress_level", "relaxation_completed", "source"):
        op.drop_column("behavior_records", column)
    op.add_column("behavior_records", sa.Column("screen_time", sa.Float(), nullable=False, server_default="0"))
    op.add_column("behavior_records", sa.Column("nighttime_screen_time", sa.Float(), nullable=False, server_default="0"))
    op.add_column("behavior_records", sa.Column("steps", sa.Integer(), nullable=False, server_default="0"))
    op.add_column("behavior_records", sa.Column("app_usage", postgresql.JSONB()))
    op.create_check_constraint("ck_behavior_screen", "behavior_records", "screen_time >= 0 AND nighttime_screen_time >= 0 AND nighttime_screen_time <= screen_time")
    op.create_check_constraint("ck_behavior_steps", "behavior_records", "steps >= 0")

    for name in ("ck_sleep_order", "ck_sleep_durations", "ck_sleep_efficiency"):
        op.drop_constraint(name, "sleep_records", type_="check")
    op.drop_constraint("uq_sleep_user_date", "sleep_records", type_="unique")
    op.add_column("sleep_records", sa.Column("tst", sa.Float()))
    op.add_column("sleep_records", sa.Column("se", sa.Float()))
    op.execute("UPDATE sleep_records SET tst = total_sleep_time_min, se = round((sleep_efficiency * 100)::numeric, 2)")
    op.execute(
        """
        UPDATE sleep_records s SET sleep_date = (s.final_awakening AT TIME ZONE u.timezone)::date
        FROM users u WHERE u.user_id = s.user_id
        """
    )
    op.alter_column("sleep_records", "tst", nullable=False)
    op.alter_column("sleep_records", "se", nullable=False)
    for column in ("out_of_bed_time", "time_in_bed_min", "total_sleep_time_min", "sleep_efficiency"):
        op.drop_column("sleep_records", column)
    op.alter_column("sleep_records", "source", server_default=None)
    op.alter_column("sleep_records", "sleep_onset_latency_min", new_column_name="sol")
    op.alter_column("sleep_records", "sol", type_=sa.Float())
    op.alter_column("sleep_records", "wake_after_sleep_onset_min", new_column_name="waso")
    op.alter_column("sleep_records", "waso", type_=sa.Float())
    op.alter_column("sleep_records", "final_awakening", new_column_name="wake_time")
    op.create_unique_constraint("uq_sleep_user_date", "sleep_records", ["user_id", "sleep_date"])
    op.create_check_constraint("ck_sleep_order", "sleep_records", "wake_time > bedtime")
    op.create_check_constraint("ck_sleep_durations", "sleep_records", "sol >= 0 AND waso >= 0 AND tst >= 0")
    op.create_check_constraint("ck_sleep_se", "sleep_records", "se BETWEEN 0 AND 100")
