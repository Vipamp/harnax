# VIPClaw 项目说明

这是一个 Maven 多模块项目，包含后端管理服务和前端 Web UI。

## 项目结构

```
vipclaw/
├── vipclaw-admin/          # 后端模块 (Spring Boot 3 + Java 21)
└── vipclaw-webui/          # 前端模块 (Ant Design Pro)
```

## 快速开始

### 1. 后端服务 (vipclaw-admin)

**环境要求:**
- Java 21+
- Maven 3.6+

**启动步骤:**

```bash
# 进入后端模块目录
cd vipclaw-admin

# 使用 Maven 启动 Spring Boot 应用
mvn spring-boot:run
```

后端服务将在 http://localhost:8080/api 启动

**可用的 API 端点:**
- `GET /api/health` - 健康检查
- `GET /api/users` - 获取所有用户
- `GET /api/users/{id}` - 获取指定用户
- `POST /api/users` - 创建用户
- `PUT /api/users/{id}` - 更新用户
- `DELETE /api/users/{id}` - 删除用户

### 2. 前端服务 (vipclaw-webui)

**环境要求:**
- Node.js 18+
- npm 8+

**启动步骤:**

```bash
# 进入前端模块目录
cd vipclaw-webui

# 安装依赖
npm install

# 启动开发服务器
npm start
```

前端服务将在 http://localhost:8000 启动

**注意：** 前端配置了代理，会自动将 `/api` 请求转发到后端服务 (http://localhost:8080)

## 开发说明

### 后端技术栈
- Spring Boot 3.2.0
- Java 21
- Spring Web
- Spring Validation

### 前端技术栈
- React 18
- Ant Design 5
- Umi 4
- TypeScript 5

## 构建生产版本

### 后端
```bash
cd vipclaw-admin
mvn clean package
```

### 前端
```bash
cd vipclaw-webui
npm run build
```

## 注意事项

1. 确保先启动后端服务，再启动前端服务
2. 前端通过代理连接后端，确保后端在 8080 端口运行
3. TypeScript 编译错误在首次安装依赖前是正常的，执行 `npm install` 后会自动解决
