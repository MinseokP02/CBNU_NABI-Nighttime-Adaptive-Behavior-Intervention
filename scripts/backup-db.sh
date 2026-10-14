#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ENV_FILE="$PROJECT_DIR/.env"
[ -f "$ENV_FILE" ] || { echo "Error: run scripts/init.sh first." >&2; exit 1; }

BACKUP_DIR=${BACKUP_DIR:-"$PROJECT_DIR/backups"}
mkdir -p "$BACKUP_DIR"
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUTPUT_FILE=${1:-"$BACKUP_DIR/nabi_${TIMESTAMP}.dump"}

cd "$PROJECT_DIR"
docker compose exec -T db sh -eu -c \
  'pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --format=custom --no-owner --no-privileges' \
  > "$OUTPUT_FILE"
echo "Backup created: $OUTPUT_FILE"
