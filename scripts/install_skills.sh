#!/usr/bin/env bash
# Sync the repo's bundled skills into the user-level skills directory
# (${KODA_HOME:-~/.koda}/skills) where the Koda core discovers them.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KODA_HOME="${KODA_HOME:-$HOME/.koda}"
DEST="$KODA_HOME/skills"

mkdir -p "$DEST"
cp -R "$REPO_DIR/skills/." "$DEST/"

COUNT=$(find "$DEST" -name "SKILL.md" | wc -l | tr -d ' ')
echo "installed $COUNT skills into $DEST"
