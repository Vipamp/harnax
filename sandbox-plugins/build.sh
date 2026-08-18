#!/bin/bash
# Builds the sandbox images used by harnax-agent-service:
#   1. harnax-sandbox:latest  — plugin base image (Python 3.11 + harnax-cli plugin)
#   2. harnax-sandbox:py-node — default sandbox image (Python 3.11 + Node.js 22)
# The plugin base image requires Go; the custom image always builds.
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# ---------- Step 1: plugin base image (needs Go) ----------
if command -v go &> /dev/null; then
  echo "=== Cross-compiling harnax-cli ==="
  mkdir -p "$SCRIPT_DIR/harnax-cli/bin"
  (cd "$PROJECT_ROOT/harnax-cli" && \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags "-s -w" \
    -o "$SCRIPT_DIR/harnax-cli/bin/harnax" .)

  echo "=== Copying SKILL.md ==="
  cp "$PROJECT_ROOT/harnax-cli/SKILL.md" "$SCRIPT_DIR/harnax-cli/SKILL.md"

  echo "=== Building plugin image: harnax-sandbox:latest ==="
  docker build -t harnax-sandbox:latest -f "$SCRIPT_DIR/Dockerfile.sandbox" "$SCRIPT_DIR"
else
  echo "WARN: Go not installed, skipping harnax-cli plugin image (harnax-sandbox:latest)."
  echo "      Install Go and re-run to enable the harnax-cli sandbox plugin."
fi

# ---------- Step 2: custom sandbox image (Python + Node) ----------
if docker image inspect harnax-sandbox:latest >/dev/null 2>&1; then
  BASE_IMAGE="harnax-sandbox:latest"
else
  BASE_IMAGE="python:3.11-slim"
fi

echo "=== Building custom sandbox image: harnax-sandbox:py-node (base: $BASE_IMAGE) ==="
docker build \
  --build-arg BASE_IMAGE="$BASE_IMAGE" \
  -t harnax-sandbox:py-node \
  -f "$SCRIPT_DIR/Dockerfile.custom-sandbox" \
  "$SCRIPT_DIR"

echo "=== Done ==="
docker images | grep harnax-sandbox || true
