"""Walk history (산책 내역) from the app's 산책 page.

Revision ID: 20261121_0003
"""
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "20261121_0003"
down_revision = "20261031_0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "walk_records",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("duration_sec", sa.Integer(), nullable=False),
        sa.Column("distance_m", sa.Float(), nullable=False),
        sa.Column("target_minutes", sa.Integer()),
        sa.Column("planned_distance_m", sa.Float()),
        sa.Column("planned_route", postgresql.JSONB(), nullable=False, server_default=sa.text("'[]'::jsonb")),
        sa.Column("path", postgresql.JSONB(), nullable=False, server_default=sa.text("'[]'::jsonb")),
        sa.Column("proposal_id", sa.String(64)),
        sa.Column("source", sa.String(50), nullable=False, server_default="app"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("ended_at > started_at", name="ck_walk_order"),
        sa.CheckConstraint("duration_sec > 0 AND distance_m >= 0", name="ck_walk_amounts"),
        sa.CheckConstraint("target_minutes IS NULL OR target_minutes BETWEEN 5 AND 180", name="ck_walk_target"),
    )
    op.create_index("ix_walk_records_user_id", "walk_records", ["user_id"])
    op.create_index("ix_walk_records_user_started", "walk_records", ["user_id", "started_at"])


def downgrade() -> None:
    op.drop_index("ix_walk_records_user_started", table_name="walk_records")
    op.drop_index("ix_walk_records_user_id", table_name="walk_records")
    op.drop_table("walk_records")
