#!/bin/bash

# Harnax Docker Build Script
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
mvn spotless:apply
mvn clean package -pl harnax-admin -am -P$EDITION -DskipTests

# Copy JAR to docker/dist/backend/
echo "📋 Copying JAR to docker/dist/backend/..."
mkdir -p docker/dist/backend
cp harnax-admin/target/harnax-admin-*.jar docker/dist/backend/
echo "✅ Backend JAR built successfully."

# Step 1.5: Build router JAR
echo ""
echo "📦 Step 1.5: Building router JAR..."
mvn clean package -pl harnax-session-router -am -P$EDITION -DskipTests

# Copy JAR to docker/dist/router/
echo "📋 Copying JAR to docker/dist/router/..."
mkdir -p docker/dist/router
cp harnax-session-router/target/harnax-session-router-*.jar docker/dist/router/
echo "✅ Router JAR built successfully."

# Step 2: Build frontend
echo ""
echo "🎨 Step 2: Building frontend..."
cd harnax-webui
npm run build:$EDITION

# Copy frontend files to docker/dist/frontend/
echo "📋 Copying frontend files to docker/dist/frontend/..."
cd ..
mkdir -p docker/dist/frontend
cd harnax-webui
npm install
cd ..
cp -r harnax-webui/dist/* docker/dist/frontend/
echo "✅ Frontend built successfully."

# Step 3: Build Docker images
echo ""
echo "🐳 Step 3: Building Docker images..."
docker build -f docker/Dockerfile.backend -t harnax-backend:$EDITION .
docker build -f docker/Dockerfile.router -t harnax-router:$EDITION .
docker build -f docker/Dockerfile.frontend -t harnax-frontend:$EDITION .
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
