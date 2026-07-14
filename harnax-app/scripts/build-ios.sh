#!/bin/bash
# harnax-app iOS 构建脚本
# 用法: ./scripts/build-ios.sh
# 注意: iOS 打包需要 macOS + Xcode + Apple Developer 证书

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Harnax App iOS Build ==="
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
echo "Option 1: HBuilderX Cloud Build (Recommended, no Xcode needed)"
echo "  1. Open HBuilderX"
echo "  2. File > Import > Import from local directory > $PROJECT_DIR"
echo "  3. Distribution > Native App - Cloud Build"
echo "  4. Select iOS platform"
echo "  5. Upload .p12 certificate and provisioning profile"
echo "  6. Click Build"
echo ""
echo "Option 2: HBuilderX Local Build (requires Xcode)"
echo "  1. Install Xcode from App Store"
echo "  2. Configure Apple Developer certificate in Xcode"
echo "  3. In HBuilderX: Distribution > Native App - Local Build"
echo "  4. Select iOS platform"
echo ""
echo "Certificate Requirements:"
echo "  - Apple Developer Account (\$99/year)"
echo "  - Distribution Certificate (.p12)"
echo "  - Provisioning Profile (.mobileprovision)"
echo "  - Bundle Identifier: com.harnax.chat (or custom)"
echo ""
