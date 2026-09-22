#!/bin/bash
# Builds the default sandbox image used by harnax-agent-service:
#   harnax-sandbox:py-node — Python 3.11 + Node.js 22
#
# CLI packages no longer have a part in this image: they land in a per-payload-combination
# image built at runtime by CliImageBuilder (see docs/superpowers/specs/
# 2026-09-21-cli-package-plugin-design.md §4.2).
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Building custom sandbox image: harnax-sandbox:py-node ==="
docker build \
  -t harnax-sandbox:py-node \
  -f "$SCRIPT_DIR/Dockerfile.custom-sandbox" \
  "$SCRIPT_DIR"

echo "=== Done ==="
docker images | grep harnax-sandbox || true
