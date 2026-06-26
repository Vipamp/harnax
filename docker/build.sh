#!/bin/bash

# Harnax Docker Build Script
# Usage: ./docker/build.sh

set -e

echo "🚀 Starting build..."

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
mvn clean package -pl harnax-admin -am -DskipTests

# Copy JAR to docker/dist/backend/
echo "📋 Copying JAR to docker/dist/backend/..."
mkdir -p docker/dist/backend
cp harnax-admin/target/harnax-admin-*.jar docker/dist/backend/
echo "✅ Backend JAR built successfully."

# Step 1.5: Build router JAR
echo ""
echo "📦 Step 1.5: Building router JAR..."
mvn clean package -pl harnax-session-router -am -DskipTests

# Copy JAR to docker/dist/router/
echo "📋 Copying JAR to docker/dist/router/..."
mkdir -p docker/dist/router
cp harnax-session-router/target/harnax-session-router-*.jar docker/dist/router/
echo "✅ Router JAR built successfully."

# Step 1.6: Build agent-service JAR
echo ""
echo "📦 Step 1.6: Building agent-service JAR..."
mvn clean package -pl harnax-agent/harnax-agent-service -am -DskipTests

# Copy JAR to docker/dist/agent-service/
echo "📋 Copying JAR to docker/dist/agent-service/..."
mkdir -p docker/dist/agent-service
cp harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar docker/dist/agent-service/
echo "✅ Agent-service JAR built successfully."

# Step 2: Build frontend
echo ""
echo "🎨 Step 2: Building frontend..."
cd harnax-webui
npm install
npm run build

# Copy frontend files to docker/dist/frontend/
echo "📋 Copying frontend files to docker/dist/frontend/..."
cd ..
mkdir -p docker/dist/frontend
cp -r harnax-webui/dist/* docker/dist/frontend/
echo "✅ Frontend built successfully."

# Step 3: Build Docker images
echo ""
echo "🐳 Step 3: Building Docker images..."
docker build -f docker/Dockerfile.backend -t harnax-backend:latest .
docker build -f docker/Dockerfile.router -t harnax-router:latest .
docker build -f docker/Dockerfile.agent-service -t harnax-agent-service:latest .
docker build -f docker/Dockerfile.frontend -t harnax-frontend:latest .
echo "✅ Docker images built successfully."

echo ""
echo "🎉 Build complete!"
echo ""
echo "To start the services (local mode), run:"
echo "  docker-compose -f docker/docker-compose.personal.yml up -d"
echo ""
echo "To start the services (production mode), run:"
echo "  docker-compose -f docker/docker-compose.prod.yml up -d"
echo ""
echo "To view logs:"
echo "  docker-compose -f docker/docker-compose.personal.yml logs -f"
echo ""
echo "To stop the services:"
echo "  docker-compose -f docker/docker-compose.personal.yml down"
