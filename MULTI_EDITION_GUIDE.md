# VIPClaw 多版本部署指南

## 📖 目录

- [多版本架构](#多版本架构)
- [快速开始](#快速开始)
- [Maven 构建](#maven-构建)
- [Docker 部署](#docker-部署)
- [版本切换](#版本切换)
- [环境配置](#环境配置)
- [故障排查](#故障排查)

---

## 多版本架构

VIPClaw 支持三个版本,通过编译时隔离实现功能差异化:

| 版本 | 适用场景 | 核心功能 |
|------|---------|---------|
| **个人版** (personal) | 个人开发者 | 基础 AI 对话、本地 MCP、计划笔记 |
| **企业版** (enterprise) | 团队/企业 | 个人版 + 用户管理、Token 监控、Agent 共享、手机号登录 |
| **公有云版** (public) | SaaS 运营 | 企业版 + 多租户、计费系统、模型市场、技能市场 |

### 版本功能矩阵

| 功能 | 个人版 | 企业版 | 公有云版 |
|------|:------:|:------:|:--------:|
| AI 对话 | ✅ | ✅ | ✅ |
| MCP 服务 | ✅ | ✅ | ✅ |
| 计划笔记 | ✅ | ✅ | ✅ |
| 用户管理 | ❌ | ✅ | ✅ |
| Token 监控 | ❌ | ✅ | ✅ |
| Agent 共享 | ❌ | ✅ | ✅ |
| 手机号登录 | ❌ | ✅ | ✅ |
| 多租户 | ❌ | ❌ | ✅ |
| 计费系统 | ❌ | ❌ | ✅ |
| 模型市场 | ❌ | ❌ | ✅ |
| 技能市场 | ❌ | ❌ | ✅ |

---

## 快速开始

### 前置要求

- JDK 21+
- Maven 3.8+
- Node.js 20+
- Docker 20+ (可选)
- MySQL 8.0+

### 一键构建

```bash
# 仅构建 JAR
./build-all-editions.sh

# 构建 JAR + Docker 镜像
./build-all-editions.sh --docker
```

---

## Maven 构建

### 构建单个版本

```bash
# 个人版
cd vipclaw-admin
mvn clean package -Ppersonal -DskipTests

# 企业版
mvn clean package -Penterprise -DskipTests

# 公有云版
mvn clean package -Ppublic -DskipTests
```

### 运行单个版本

```bash
# 个人版
java -jar target/vipclaw-admin-*.jar --spring.profiles.active=personal

# 企业版
java -jar target/vipclaw-admin-*.jar --spring.profiles.active=enterprise

# 公有云版
java -jar target/vipclaw-admin-*.jar --spring.profiles.active=public
```

---

## Docker 部署

### 本地开发环境

```bash
# 启动个人版(默认)
EDITION=personal docker-compose up -d

# 启动企业版
EDITION=enterprise docker-compose up -d

# 启动公有云版
EDITION=public docker-compose up -d

# 查看日志
docker-compose logs -f backend

# 停止服务
docker-compose down
```

**服务访问:**
- 前端: http://localhost
- 后端 API: http://localhost:8080/api
- 数据库: localhost:3306

### 生产环境

```bash
# 构建 Docker 镜像
docker build --build-arg EDITION=public -t vipclaw-admin:public -f Dockerfile.backend .
docker build --build-arg EDITION=public -t vipclaw-frontend:public -f Dockerfile.frontend .

# 启动生产环境
EDITION=public \
DB_URL=jdbc:mysql://prod-db:3306/vipclaw \
DB_USERNAME=vipclaw \
DB_PASSWORD=your_secure_password \
docker-compose -f docker-compose.prod.yml up -d
```

**生产环境特性:**
- ✅ 资源限制(CPU/内存)
- ✅ 健康检查
- ✅ 自动重启
- ✅ SSL 支持(443 端口)
- ✅ 外部数据库连接

### Docker 镜像列表

```bash
docker images | grep vipclaw

# 输出示例:
# vipclaw-admin:personal    latest    abc123    200MB
# vipclaw-admin:enterprise  latest    def456    200MB
# vipclaw-admin:public      latest    ghi789    205MB
# vipclaw-frontend:personal latest    jkl012    25MB
# vipclaw-frontend:enterprise latest mno345    25MB
# vipclaw-frontend:public   latest    pqr678    28MB
```

---

## 版本切换

### 后端版本切换

修改 `application.yml`:

```yaml
edition:
  current: personal  # 改为 enterprise 或 public
```

或使用启动参数:

```bash
java -jar vipclaw-admin.jar --spring.profiles.active=enterprise
```

### 前端版本切换

修改 `.env` 文件:

```bash
# .env.personal
EDITION=personal

# .env.enterprise  
EDITION=enterprise

# .env.public
EDITION=public
```

重新构建:

```bash
npm run build:personal
npm run build:enterprise
npm run build:public
```

---

## 环境配置

### 配置文件说明

```
vipclaw-admin/src/main/resources/
├── application.yml              # 公共配置
├── application-personal.yml     # 个人版配置
├── application-enterprise.yml   # 企业版配置
└── application-public.yml       # 公有云版配置
```

### 环境变量

| 变量名 | 说明 | 默认值 | 示例 |
|--------|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | 版本配置 | personal | enterprise |
| `SPRING_DATASOURCE_URL` | 数据库连接 | - | jdbc:mysql://db:3306/vipclaw |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户名 | vipclaw | admin |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | vipclaw123 | secure_password |
| `LOG_LEVEL` | 日志级别 | INFO | DEBUG |

### Docker 环境变量

```bash
docker run -d \
  -e SPRING_PROFILES_ACTIVE=enterprise \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://db:3306/vipclaw \
  -e SPRING_DATASOURCE_USERNAME=vipclaw \
  -e SPRING_DATASOURCE_PASSWORD=secret \
  -e LOG_LEVEL=DEBUG \
  vipclaw-admin:enterprise
```

---

## 故障排查

### 构建失败

**问题**: Maven 构建报错

**解决**:
```bash
# 清理 Maven 缓存
rm -rf ~/.m2/repository/com/vipamp

# 重新构建
mvn clean install -DskipTests
cd vipclaw-admin
mvn clean package -Ppersonal -DskipTests
```

### Docker 启动失败

**问题**: 容器启动后立即退出

**解决**:
```bash
# 查看日志
docker-compose logs backend

# 常见原因:
# 1. 数据库连接失败 - 检查 MySQL 是否启动
# 2. 端口被占用 - 修改 ports 映射
# 3. 配置文件错误 - 检查 application.yml

# 测试数据库连接
docker exec -it vipclaw-mysql mysql -uvipclaw -pvipclaw123 vipclaw
```

### 前端版本功能不生效

**问题**: 切换版本后功能未变化

**解决**:
```bash
# 1. 清除浏览器缓存
# 2. 重新构建前端
cd vipclaw-webui
npm run build:enterprise

# 3. 检查环境变量
cat .env.enterprise

# 4. 验证 FEATURES 配置
# 打开浏览器控制台,输入:
window.FEATURES
```

### 版本隔离验证

**验证 JAR 内容**:
```bash
# 查看 JAR 中的配置文件
unzip -l dist/enterprise/vipclaw-admin-*.jar | grep application

# 查看功能开关
unzip -p dist/enterprise/vipclaw-admin-*.jar BOOT-INF/classes/application-enterprise.yml | grep features
```

**运行验证脚本**:
```bash
./verify-edition.sh
```

---

## CI/CD 集成

### GitLab CI/CD

项目已提供 `.gitlab-ci.yml` 配置,支持:

- ✅ 三版本并行构建
- ✅ 自动 Docker 镜像构建
- ✅ 多环境部署(测试/生产)
- ✅ 手动触发生产部署

### 自定义 CI/CD

参考 `.gitlab-ci.yml` 适配到其他平台:
- GitHub Actions
- Jenkins
- CircleCI
- Travis CI

---

## 开发指南

### 添加版本专属功能

**1. 创建版本专属源码目录**:
```
vipclaw-admin/src/
├── main/kotlin/com/vipamp/vipclaw/admin/
│   ├── controller/
│   │   ├── personal/        # 个人版专属
│   │   ├── enterprise/      # 企业版专属
│   │   └── public/          # 公有云版专属
```

**2. 添加 @RequiresEdition 注解**:
```kotlin
@RestController
@RequestMapping("/api/users")
@RequiresEdition("enterprise", "public")
class SysUserController {
    // 仅企业版和公有云版可用
}
```

**3. 配置 Maven Profile**:
确保 `pom.xml` 中已配置对应版本的 profile。

### 前端版本功能

**1. 使用 FEATURES 配置**:
```typescript
import { FEATURES } from '@/utils/edition';

{FEATURES.userManagement && <UserManagement />}
{FEATURES.phoneLogin && <PhoneLoginTab />}
```

**2. 路由版本控制**:
```typescript
{
  path: '/system/user',
  name: 'user',
  edition: ['enterprise', 'public'],  // 版本限制
}
```

---

## 技术支持

- 📧 技术支持: support@vipamp.com
- 📖 文档中心: https://docs.vipclaw.com
- 💬 问题反馈: https://github.com/vipamp/vipclaw/issues

---

**版本**: 1.0.0  
**更新日期**: 2026-05-01
