#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== Cross-compiling harnax-cli ==="
mkdir -p "$SCRIPT_DIR/harnax-cli/bin"
(cd "$PROJECT_ROOT/harnax-cli" && \
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags "-s -w" \
  -o "$SCRIPT_DIR/harnax-cli/bin/harnax" .)

echo "=== Copying SKILL.md ==="
cp "$PROJECT_ROOT/harnax-cli/SKILL.md" "$SCRIPT_DIR/harnax-cli/SKILL.md"

echo "=== Building sandbox image ==="
docker build -t harnax-sandbox:latest -f "$SCRIPT_DIR/Dockerfile.sandbox" "$SCRIPT_DIR"

echo "=== Done: harnax-sandbox:latest ==="
