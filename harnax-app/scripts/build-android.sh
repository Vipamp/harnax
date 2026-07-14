#!/bin/bash
# harnax-app Android 构建脚本
# 用法: ./scripts/build-android.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Harnax App Android Build ==="
echo "Project: $PROJECT_DIR"
echo ""

# Step 1: Install dependencies
echo "[1/3] Installing dependencies..."
cd "$PROJECT_DIR"
if [ ! -d "node_modules" ]; then
  npm install
fi

# Step 2: Build app resources
echo "[2/3] Building app resources..."
npm run build:app

# Step 3: Output
DIST_DIR="$PROJECT_DIR/dist/build/app"
echo ""
echo "[3/3] Build complete!"
echo ""
echo "Output directory: $DIST_DIR"
echo ""
echo "=== Next Steps ==="
echo ""
echo "Option 1: HBuilderX Cloud Build (Recommended)"
echo "  1. Open HBuilderX"
echo "  2. File > Import > Import from local directory > $PROJECT_DIR"
echo "  3. Distribution > Native App - Cloud Build"
echo "  4. Select Android platform"
echo "  5. Configure keystore (or use test certificate)"
echo "  6. Click Build"
echo ""
echo "Option 2: HBuilderX Local Build"
echo "  1. Install Android SDK and configure in HBuilderX"
echo "  2. Distribution > Native App - Local Build"
echo "  3. Select Android platform"
echo ""
echo "Option 3: uni-app CLI Offline Build"
echo "  1. Download uni-app offline SDK"
echo "  2. Copy $DIST_DIR to the Android project assets"
echo "  3. Build with Android Studio"
echo ""
