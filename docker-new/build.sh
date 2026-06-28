#!/bin/bash

# =====================================================
# Harnax Docker Build Script (Cluster Mode)
# Usage: ./docker-new/build.sh
#
# This script builds all services and Docker images
# for cluster mode deployment.
# =====================================================

set -e

echo "=========================================="
echo "  Harnax Docker Build (Cluster Mode)"
echo "=========================================="
echo ""

# Check prerequisites
echo "Checking prerequisites..."

if ! command -v mvn &> /dev/null; then
    echo "ERROR: Maven is not installed. Please install Maven first."
    exit 1
fi

if ! command -v node &> /dev/null; then
    echo "ERROR: Node.js is not installed. Please install Node.js first."
    exit 1
fi

if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker is not installed. Please install Docker first."
    exit 1
fi

echo "All prerequisites satisfied."
echo ""

# ==========================================
# Step 1: Build Admin JAR (harnax-admin)
# ==========================================
echo "Step 1/7: Building admin JAR (harnax-admin)..."
mvn spotless:apply
mvn clean package -pl harnax-admin -am -Dmaven.test.skip=true

echo "Copying JAR to docker-new/dist/harnax-admin/..."
mkdir -p docker-new/dist/harnax-admin
cp harnax-admin/target/harnax-admin-*.jar docker-new/dist/harnax-admin/
echo "Admin JAR built successfully."
echo ""

# ==========================================
# Step 2: Build Router JAR (harnax-session-router)
# ==========================================
echo "Step 2/7: Building router JAR (harnax-session-router)..."
mvn clean package -pl harnax-session-router -am -Dmaven.test.skip=true

echo "Copying JAR to docker-new/dist/router/..."
mkdir -p docker-new/dist/router
cp harnax-session-router/target/harnax-session-router-*.jar docker-new/dist/router/
echo "Router JAR built successfully."
echo ""

# ==========================================
# Step 3: Build Agent-Service JAR
# ==========================================
echo "Step 3/7: Building agent-service JAR..."
mvn clean package -pl harnax-agent/harnax-agent-service -am -Dmaven.test.skip=true

echo "Copying JAR to docker-new/dist/agent-service/..."
mkdir -p docker-new/dist/agent-service
cp harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar docker-new/dist/agent-service/
echo "Agent-service JAR built successfully."
echo ""

# ==========================================
# Step 4: Build Channel-Service JAR
# ==========================================
echo "Step 4/7: Building channel-service JAR..."
mvn clean package -pl harnax-channel/harnax-channel-service -am -Dmaven.test.skip=true

echo "Copying JAR to docker-new/dist/channel-service/..."
mkdir -p docker-new/dist/channel-service
cp harnax-channel/harnax-channel-service/target/harnax-channel-service-*.jar docker-new/dist/channel-service/
echo "Channel-service JAR built successfully."
echo ""

# ==========================================
# Step 5: Build Frontend
# ==========================================
echo "Step 5/7: Building frontend (harnax-webui)..."
cd harnax-webui
npm install
npm run build

echo "Copying frontend files to docker-new/dist/frontend/..."
cd ..
mkdir -p docker-new/dist/frontend
cp -r harnax-webui/dist/* docker-new/dist/frontend/
echo "Frontend built successfully."
echo ""

# ==========================================
# Step 6: Build Docker Images
# ==========================================
echo "Step 6/7: Building Docker images..."
docker build -f docker-new/Dockerfile.admin -t harnax-admin:latest .
docker build -f docker-new/Dockerfile.router -t harnax-router:latest .
docker build -f docker-new/Dockerfile.agent-service -t harnax-agent-service:latest .
docker build -f docker-new/Dockerfile.channel-service -t harnax-channel-service:latest .
docker build -f docker-new/Dockerfile.frontend -t harnax-frontend:latest .
echo "Docker images built successfully."
echo ""

# ==========================================
# Step 7: Summary
# ==========================================
echo "=========================================="
echo "  Build Complete!"
echo "=========================================="
echo ""
echo "Docker images:"
docker images | grep harnax
echo ""
echo "To start the services (cluster mode), run:"
echo "  docker-compose -f docker-new/docker-compose.yml up -d"
echo ""
echo "To view logs:"
echo "  docker-compose -f docker-new/docker-compose.yml logs -f"
echo ""
echo "To stop the services:"
echo "  docker-compose -f docker-new/docker-compose.yml down"
echo ""
echo "Service ports:"
echo "  Frontend:        http://localhost:80"
echo "  Admin:           http://localhost:8080"
echo "  Router:          http://localhost:8081"
echo "  Agent-Service:   http://localhost:8082"
echo "  Channel-Service: http://localhost:8083"
echo "  MinIO Console:   http://localhost:9001"
echo "  MySQL:           localhost:3306"
echo "  Redis:           localhost:6379"
