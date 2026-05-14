#!/bin/bash

# VIPClaw Docker Build Script
# Usage: ./docker/build.sh [personal|enterprise|public]

set -e

EDITION=${1:-personal}

echo "🚀 Starting build for $EDITION edition..."

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven is not installed. Please install Maven first."
    exit 1
fi

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed. Please install Node.js first."
    exit 1
fi

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed. Please install Docker first."
    exit 1
fi

echo "✅ All dependencies are installed."

# Step 1: Build backend JAR
echo ""
echo "📦 Step 1: Building backend JAR..."
mvn clean package -pl vipclaw-admin -am -P$EDITION -DskipTests

# Copy JAR to docker/dist/backend/
echo "📋 Copying JAR to docker/dist/backend/..."
mkdir -p docker/dist/backend
cp vipclaw-admin/target/vipclaw-admin-*.jar docker/dist/backend/
echo "✅ Backend JAR built successfully."

# Step 2: Build frontend
echo ""
echo "🎨 Step 2: Building frontend..."
cd vipclaw-webui
npm run build:$EDITION

# Copy frontend files to docker/dist/frontend/
echo "📋 Copying frontend files to docker/dist/frontend/..."
cd ..
mkdir -p docker/dist/frontend
cp -r vipclaw-webui/dist/* docker/dist/frontend/
echo "✅ Frontend built successfully."

# Step 3: Build Docker images
echo ""
echo "🐳 Step 3: Building Docker images..."
docker build -f docker/Dockerfile.backend -t vipclaw-backend:$EDITION .
docker build -f docker/Dockerfile.frontend -t vipclaw-frontend:$EDITION .
echo "✅ Docker images built successfully."

echo ""
echo "🎉 Build complete!"
echo ""
echo "To start the services, run:"
echo "  docker-compose -f docker/docker-compose.$EDITION.yml up -d"
echo ""
echo "To view logs:"
echo "  docker-compose -f docker/docker-compose.$EDITION.yml logs -f"
echo ""
echo "To stop the services:"
echo "  docker-compose -f docker/docker-compose.$EDITION.yml down"
