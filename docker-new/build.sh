#!/bin/bash

# =====================================================
# Harnax Docker Build Script (Cluster Mode)
# Usage: ./docker-new/build.sh [--native]
#
# Options:
#   --native    Build router service as GraalVM native image
#
# This script builds all services and Docker images
# for cluster mode deployment.
# =====================================================

set -e

# Parse arguments
NATIVE_MODE=false
for arg in "$@"; do
    case $arg in
        --native)
            NATIVE_MODE=true
            shift
            ;;
    esac
done

echo "=========================================="
echo "  Harnax Docker Build (Cluster Mode)"
if [ "$NATIVE_MODE" = true ]; then
    echo "  Native Image Mode: ENABLED (router)"
fi
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

if [ "$NATIVE_MODE" = true ]; then
    if ! command -v native-image &> /dev/null; then
        echo "ERROR: GraalVM native-image is not installed."
        echo "Please install GraalVM JDK and set GRAALVM_HOME."
        exit 1
    fi
    echo "GraalVM native-image found."
fi

echo "All prerequisites satisfied."
echo ""

# ==========================================
# Step 1: Build Admin JAR (harnax-admin)
# ==========================================
echo "Step 1/9: Building admin JAR (harnax-admin)..."
mvn spotless:apply
mvn clean package -pl harnax-admin -am -Dmaven.test.skip=true

echo "Copying JAR to docker-new/dist/harnax-admin/..."
mkdir -p docker-new/dist/harnax-admin
cp harnax-admin/target/harnax-admin-*.jar docker-new/dist/harnax-admin/
echo "Admin JAR built successfully."
echo ""

# ==========================================
# Step 2: Build Router (harnax-session-router)
# ==========================================
if [ "$NATIVE_MODE" = true ]; then
    echo "Step 2/9: Building router NATIVE IMAGE (harnax-session-router)..."
    echo "Note: Native image build may take several minutes..."
    mvn clean package -Pnative -pl harnax-session-router -am -Dmaven.test.skip=true

    echo "Copying native executable to docker-new/dist/router/..."
    mkdir -p docker-new/dist/router
    cp harnax-session-router/target/harnax-session-router docker-new/dist/router/
    echo "Router native image built successfully."
else
    echo "Step 2/9: Building router JAR (harnax-session-router)..."
    mvn clean package -pl harnax-session-router -am -Dmaven.test.skip=true

    echo "Copying JAR to docker-new/dist/router/..."
    mkdir -p docker-new/dist/router
    cp harnax-session-router/target/harnax-session-router-*.jar docker-new/dist/router/
    echo "Router JAR built successfully."
fi
echo ""

# ==========================================
# Step 3: Build Agent-Service JAR
# ==========================================
echo "Step 3/9: Building agent-service JAR..."
mvn clean package -pl harnax-agent/harnax-agent-service -am -Dmaven.test.skip=true

echo "Copying JAR to docker-new/dist/agent-service/..."
mkdir -p docker-new/dist/agent-service
cp harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar docker-new/dist/agent-service/
echo "Agent-service JAR built successfully."
echo ""

# ==========================================
# Step 4: Build Channel-Service JAR
# ==========================================
echo "Step 4/9: Building channel-service JAR..."
mvn clean package -pl harnax-channel/harnax-channel-service -am -Dmaven.test.skip=true

echo "Copying JAR to docker-new/dist/channel-service/..."
mkdir -p docker-new/dist/channel-service
cp harnax-channel/harnax-channel-service/target/harnax-channel-service-*.jar docker-new/dist/channel-service/
echo "Channel-service JAR built successfully."
echo ""

# ==========================================
# Step 5: Build Scheduler JAR (harnax-scheduler)
# ==========================================
echo "Step 5/9: Building scheduler JAR (harnax-scheduler)..."
mvn clean package -pl harnax-scheduler -am -Dmaven.test.skip=true

echo "Copying JAR to docker-new/dist/harnax-scheduler/..."
mkdir -p docker-new/dist/harnax-scheduler
cp harnax-scheduler/target/harnax-scheduler-*.jar docker-new/dist/harnax-scheduler/
echo "Scheduler JAR built successfully."
echo ""

# ==========================================
# Step 6: Build Frontend
# ==========================================
echo "Step 6/9: Building frontend (harnax-webui)..."
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
# Step 7: Build Sandbox Image (CLI plugins)
# ==========================================
if command -v go &> /dev/null; then
    echo "Step 7/9: Building sandbox image (harnax-sandbox)..."
    bash sandbox-plugins/build.sh
    echo "Sandbox image built successfully."
else
    echo "Step 7/9: SKIPPED sandbox image (Go not installed)."
    echo "  Install Go and run: bash sandbox-plugins/build.sh"
fi
echo ""

# ==========================================
# Step 8: Build Docker Images
# ==========================================
echo "Step 8/9: Building Docker images..."
docker build -f docker-new/Dockerfile.admin -t harnax-admin:latest .

if [ "$NATIVE_MODE" = true ]; then
    docker build -f docker-new/Dockerfile.router-native -t harnax-router:latest .
else
    docker build -f docker-new/Dockerfile.router -t harnax-router:latest .
fi

docker build -f docker-new/Dockerfile.agent-service -t harnax-agent-service:latest .
docker build -f docker-new/Dockerfile.channel-service -t harnax-channel-service:latest .
docker build -f docker-new/Dockerfile.scheduler -t harnax-scheduler:latest .
docker build -f docker-new/Dockerfile.frontend -t harnax-frontend:latest .
echo "Docker images built successfully."
echo ""

# ==========================================
# Step 9: Summary
# ==========================================
echo "=========================================="
echo "  Build Complete!"
if [ "$NATIVE_MODE" = true ]; then
    echo "  Router: Native Image"
else
    echo "  Router: JVM Image"
fi
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
echo "  Scheduler:       http://localhost:8084"
echo "  MinIO Console:   http://localhost:9001"
echo "  MySQL:           localhost:3306"
echo "  Redis:           localhost:6379"
