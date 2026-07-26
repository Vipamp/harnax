#!/bin/sh
# Sandbox CLI Plugin init script
# Called by agent-service after sandbox creation to inject authentication.
# Args: $1=adminUrl $2=internalSecret
# This script is idempotent (safe to call multiple times).
set -e

if [ -z "$1" ] || [ -z "$2" ]; then
    echo "[harnax-cli] ERROR: usage: init.sh <adminUrl> <internalSecret>" >&2
    exit 1
fi

HARNAX_DIR="$HOME/.harnax"
mkdir -p "$HARNAX_DIR"

cat > "$HARNAX_DIR/credentials.json" <<EOF
{"mode":"internal","internalSecret":"$2","serverUrl":"$1"}
EOF

chmod 600 "$HARNAX_DIR/credentials.json"
echo "[harnax-cli] initialized (internal secret mode)"
