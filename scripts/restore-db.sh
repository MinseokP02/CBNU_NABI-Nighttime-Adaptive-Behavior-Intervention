#!/usr/bin/env sh
set -eu

[ "$#" -eq 1 ] || { echo "Usage: $0 <backup.dump|backup.sql>" >&2; exit 1; }
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ENV_FILE="$PROJECT_DIR/.env"
INPUT_FILE=$1
[ -f "$ENV_FILE" ] || { echo "Error: run scripts/init.sh first." >&2; exit 1; }
[ -f "$INPUT_FILE" ] || { echo "Error: file not found: $INPUT_FILE" >&2; exit 1; }

cd "$PROJECT_DIR"
case "$INPUT_FILE" in
  *.sql)
    docker compose exec -T db sh -eu -c \
      'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=on' \
      < "$INPUT_FILE"
    ;;
  *)
    docker compose exec -T db sh -eu -c \
      'pg_restore --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --clean --if-exists --no-owner --no-privileges' \
      < "$INPUT_FILE"
    ;;
esac
echo "Restore completed from: $INPUT_FILE"
