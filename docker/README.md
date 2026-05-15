# VIPClaw Docker Deployment

This directory contains all Docker-related configuration files for VIPClaw.

## Prerequisites

- **Maven 3.8+** - For backend build
- **Node.js 20+** - For frontend build
- **Docker 20+** - For containerization
- **Docker Compose 2+** - For service orchestration

## Directory Structure

```
docker/
├── Dockerfile.backend           # Backend image (JRE + JAR)
├── Dockerfile.frontend          # Frontend image (Nginx)
├── docker-compose.personal.yml  # Personal edition configuration
├── docker-compose.enterprise.yml# Enterprise edition configuration
├── docker-compose.public.yml    # Public edition configuration
├── docker-compose.prod.yml      # Production environment configuration
├── build.sh                     # One-click build script
├── nginx.conf                   # Nginx configuration
├── dist/                        # Build artifacts (not committed to git)
│   ├── backend/                 # Backend JAR files
│   └── frontend/                # Frontend static files
└── data/                        # MySQL data directory (not committed to git)
```

## Quick Start

### Build and Run (Personal Edition)

```bash
# Optional: Create .env file for custom configuration
cp docker/.env.example docker/.env
# Edit docker/.env as needed

# One-click build
chmod +x docker/build.sh
./docker/build.sh personal

# Start services
docker-compose -f docker/docker-compose.personal.yml up -d

# View logs
docker-compose -f docker/docker-compose.personal.yml logs -f

# Stop services
docker-compose -f docker/docker-compose.personal.yml down
```

### Build Other Editions

```bash
# Enterprise edition
./docker/build.sh enterprise
docker-compose -f docker/docker-compose.enterprise.yml up -d

# Public edition
./docker/build.sh public
docker-compose -f docker/docker-compose.public.yml up -d
```

## Manual Build Steps

If you prefer to build manually:

### 1. Build Backend

```bash
# Build JAR package
mvn clean package -pl vipclaw-admin -am -Ppersonal -DskipTests

# Copy to docker/dist/backend/
mkdir -p docker/dist/backend
cp vipclaw-admin/target/vipclaw-admin-*.jar docker/dist/backend/
```

### 2. Build Frontend

```bash
# Build frontend
cd vipclaw-webui
npm run build:personal

# Copy to docker/dist/frontend/
cd ..
mkdir -p docker/dist/frontend
cp -r vipclaw-webui/dist/* docker/dist/frontend/
```

### 3. Build Docker Images

```bash
# Build backend image
docker build -f docker/Dockerfile.backend -t vipclaw-backend:personal .

# Build frontend image
docker build -f docker/Dockerfile.frontend -t vipclaw-frontend:personal .
```

### 4. Start Services

```bash
docker-compose -f docker/docker-compose.personal.yml up -d
```

## Service Management

### View Service Status

```bash
docker-compose -f docker/docker-compose.personal.yml ps
```

### View Logs

```bash
# All services
docker-compose -f docker/docker-compose.personal.yml logs -f

# Specific service
docker-compose -f docker/docker-compose.personal.yml logs -f backend
docker-compose -f docker/docker-compose.personal.yml logs -f frontend
docker-compose -f docker/docker-compose.personal.yml logs -f mysql
```

### Health Checks

```bash
# Check backend health
docker-compose -f docker/docker-compose.personal.yml exec backend wget -qO- http://localhost:8080/api/health

# Check frontend health
docker-compose -f docker/docker-compose.personal.yml exec frontend wget -qO- http://localhost:80/

# Check MySQL
docker-compose -f docker/docker-compose.personal.yml exec mysql mysqladmin ping -h localhost
```

### Database Access

```bash
# Connect to MySQL
docker-compose -f docker/docker-compose.personal.yml exec mysql mysql -u vipclaw -pvipclaw123

# Show databases
docker-compose -f docker/docker-compose.personal.yml exec mysql mysql -u vipclaw -pvipclaw123 -e "SHOW DATABASES;"
```

## Production Deployment

For production environment:

```bash
# Build images first
./docker/build.sh personal

# Deploy with production configuration
docker-compose -f docker/docker-compose.prod.yml up -d
```

Production configuration uses external database and includes:
- Resource limits (CPU, Memory)
- SSL certificate support
- Production log level (INFO)

## Troubleshooting

### Backend fails to start

1. Check MySQL is running:
   ```bash
   docker-compose -f docker/docker-compose.personal.yml ps mysql
   ```

2. Check backend logs:
   ```bash
   docker-compose -f docker/docker-compose.personal.yml logs backend
   ```

3. Verify database connection:
   ```bash
   docker-compose -f docker/docker-compose.personal.yml exec mysql mysql -u vipclaw -pvipclaw123 -e "SELECT 1"
   ```

### Frontend cannot access backend API

1. Check backend is healthy:
   ```bash
   docker-compose -f docker/docker-compose.personal.yml exec backend wget -qO- http://localhost:8080/api/health
   ```

2. Check Nginx configuration:
   ```bash
   docker-compose -f docker/docker-compose.personal.yml exec frontend cat /etc/nginx/conf.d/default.conf
   ```

3. Verify network connectivity:
   ```bash
   docker-compose -f docker/docker-compose.personal.yml exec frontend wget -qO- http://backend:8080/api/health
   ```

### MySQL data persistence

Data is stored in local directory `./data/mysql` (configurable via `MYSQL_DATA_DIR` environment variable).

**Default structure:**
```
docker/
└── data/
    └── mysql/          # MySQL data files
        ├── ibdata1
        ├── ib_logfile0
        ├── ib_logfile1
        └── vipclaw/    # Database files
```

**To backup:**
```bash
# Backup MySQL data
tar czf mysql-backup-$(date +%Y%m%d).tar.gz docker/data/mysql

# Or use mysqldump
docker-compose -f docker/docker-compose.personal.yml exec mysql \
  mysqldump -u vipclaw -pvipclaw123 vipclaw > backup.sql
```

**To restore:**
```bash
# From tar backup
tar xzf mysql-backup-20260514.tar.gz -C docker/data/

# From SQL dump
docker-compose -f docker/docker-compose.personal.yml exec -T mysql \
  mysql -u vipclaw -pvipclaw123 vipclaw < backup.sql
```

### Clean up

```bash
# Stop and remove containers
docker-compose -f docker/docker-compose.personal.yml down

# Remove images
docker rmi vipclaw-backend:personal vipclaw-frontend:personal

# Remove volumes (backend logs only, MySQL data is in local directory)
docker-compose -f docker/docker-compose.personal.yml down -v

# Remove local MySQL data (WARNING: deletes all data!)
rm -rf docker/data/mysql
```

## Environment Variables

### MySQL Configuration

| Variable | Description | Default |
|----------|-------------|---------|  
| MYSQL_DATA_DIR | MySQL data directory (local path) | ./data/mysql |

### Backend

| Variable | Description | Default |
|----------|-------------|---------|  
| SPRING_PROFILES_ACTIVE | Spring profile | personal |
| SPRING_DATASOURCE_URL | Database URL | jdbc:mysql://mysql:3306/vipclaw |
| SPRING_DATASOURCE_USERNAME | Database username | vipclaw |
| SPRING_DATASOURCE_PASSWORD | Database password | vipclaw123 |
| SESSION_JDBC_URL | Session database URL | (same as SPRING_DATASOURCE_URL) |
| SESSION_DATABASE_NAME | Session database name | vipclaw |
| SESSION_USERNAME | Session database username | (same as SPRING_DATASOURCE_USERNAME) |
| SESSION_PASSWORD | Session database password | (same as SPRING_DATASOURCE_PASSWORD) |
| LOG_LEVEL | Log level | DEBUG (dev) / INFO (prod) |

### Production Only

| Variable | Description | Default |
|----------|-------------|---------|
| EDITION | Edition name | personal |
| DB_URL | External database URL | - |
| DB_USERNAME | External database username | - |
| DB_PASSWORD | External database password | - |

## Notes

- All Docker files are in the `docker/` directory to keep the root clean
- Build artifacts in `docker/dist/` are not committed to git
- Database migrations are managed by Flyway (see `vipclaw-admin/src/main/resources/db/`)
- Use `.dockerignore` to optimize build context
