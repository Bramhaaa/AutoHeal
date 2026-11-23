#!/usr/bin/env bash
set -euo pipefail

# Builds a standalone Git repository from demo-service/ with:
#   - main: all 4 scenarios passing (green baseline)
#   - scenario/<id>: 1 branch per bug scenario (deterministically failing)
# Usage: ./scripts/create-scenario-branches.sh /path/to/output-repo

TARGET_DIR="${1:?Usage: $0 <target-dir>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

rm -rf "$TARGET_DIR"
mkdir -p "$TARGET_DIR"

cp "$DEMO_DIR/pom.xml" "$TARGET_DIR/"
cp -R "$DEMO_DIR/src" "$TARGET_DIR/"

cd "$TARGET_DIR"
git init -q -b main
git add .
git commit -q -m "chore: green baseline for autoheal-demo-service"

for patch in "$DEMO_DIR"/scenarios/*.patch; do
  scenario="$(basename "$patch" .patch)"
  git checkout -q -b "scenario/$scenario" main
  git apply "$patch"
  git add -A
  git commit -q -m "bug: introduce $scenario"
done

git checkout -q main
echo "Created standalone demo repo at $TARGET_DIR with branches:"
git branch --list
