#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ENV_FILE="$PROJECT_DIR/.env"

command -v docker >/dev/null 2>&1 || { echo "Error: Docker is not on PATH." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Error: Docker Compose v2 is required." >&2; exit 1; }

if [ ! -f "$ENV_FILE" ]; then
  cp "$PROJECT_DIR/.env.example" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "Created .env. Replace all sample secrets before startup."
else
  echo ".env already exists; it was not overwritten."
fi

mkdir -p "$PROJECT_DIR/backups"
if grep -q 'replace_with_' "$ENV_FILE"; then
  echo "Warning: .env still contains sample secrets." >&2
fi
echo "Next: cd '$PROJECT_DIR' && docker compose config"
