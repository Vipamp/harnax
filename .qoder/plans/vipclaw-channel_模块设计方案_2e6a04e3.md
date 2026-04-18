# vipclaw-channel 模块设计方案

## 一、架构设计

### 1.1 模块结构
```
vipclaw-core/
├── vipclaw-agent/          # 现有 Agent 模块
└── vipclaw-channel/        # 新增 Channel 模块
    ├── pom.xml
    └── src/main/kotlin/com/vipamp/vipclaw/channel/
        ├── ChannelSpec.kt              # Channel 配置规格
        ├── adaptor/
        │   ├── ChannelAdaptor.kt       # 适配器接口
        │   ├── wecom/                   # 企业微信适配器
        │   │   └── WeComAdaptor.kt
        │   ├── feishu/                  # 飞书适配器
        │   │   └── FeishuAdaptor.kt
        │   ├── dingtalk/                # 钉钉适配器
        │   │   └── DingTalkAdaptor.kt
        │   └── http/                    # HTTP 通用适配器
        │       └── HttpAdaptor.kt
        ├── message/
        │   ├── ChannelMessage.kt       # 统一消息模型
        │   └── MessageConverter.kt     # 消息转换器
        └── session/
            └── ChannelSessionManager.kt # Channel 会话管理
```

### 1.2 vipclaw-admin 模块扩展
```
vipclaw-admin/src/main/java/com/vipamp/vipclaw/admin/
├── entity/
│   ├── Channel.java              # Channel 实体
│   └── ChannelMessage.java       # 消息记录实体
├── mapper/
│   ├── ChannelMapper.java
│   └── ChannelMessageMapper.java
├── service/
│   ├── ChannelService.java
│   ├── ChannelMessageService.java
│   └── impl/
├── controller/
│   └── ChannelController.java    # 回调入口
└── dto/
    └── ChannelDTO.java
```

## 二、数据库设计

### 2.1 channel 表（通道配置）
```sql
CREATE TABLE `channel` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '通道名称',
    `type` VARCHAR(20) NOT NULL COMMENT '类型(wecom/feishu/dingtalk/http)',
    `agent_id` BIGINT(20) NOT NULL COMMENT '关联的智能体ID',
    `webhook_url` VARCHAR(500) DEFAULT NULL COMMENT '推送地址',
    `token` VARCHAR(500) DEFAULT NULL COMMENT '验证Token',
    `encoding_aes_key` VARCHAR(500) DEFAULT NULL COMMENT '加密密钥(企业微信)',
    `app_id` VARCHAR(100) DEFAULT NULL COMMENT '应用ID(飞书/钉钉)',
    `app_secret` VARCHAR(500) DEFAULT NULL COMMENT '应用密钥',
    `callback_key` VARCHAR(100) NOT NULL COMMENT '回调标识(用于生成回调URL)',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_callback_key` (`callback_key`)
);
```

### 2.2 channel_message 表（消息记录）
```sql
CREATE TABLE `channel_message` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `channel_id` BIGINT(20) NOT NULL COMMENT '通道ID',
    `session_id` VARCHAR(100) NOT NULL COMMENT '会话标识(用户/群组)',
    `message_id` VARCHAR(100) DEFAULT NULL COMMENT '原始消息ID',
    `role` VARCHAR(20) NOT NULL COMMENT '角色(user/assistant)',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_channel_session` (`channel_id`, `session_id`)
);
```

## 三、核心接口设计

### 3.1 回调 URL 格式
```
POST /admin/api/channel/callback/{type}/{callbackKey}

示例：
- 企业微信: /admin/api/channel/callback/wecom/abc123
- 飞书: /admin/api/channel/callback/feishu/abc123
- 钉钉: /admin/api/channel/callback/dingtalk/abc123
- HTTP: /admin/api/channel/callback/http/abc123
```

### 3.2 适配器接口
```kotlin
interface ChannelAdaptor {
    // 平台类型
    fun getType(): ChannelType
    
    // 验证回调签名
    fun verifySignature(request: HttpServletRequest): Boolean
    
    // 解析消息
    fun parseMessage(request: HttpServletRequest): ChannelMessage
    
    // 构建响应
    fun buildResponse(reply: String): Any
    
    // 推送消息到平台
    fun sendMessage(channel: Channel, session: String, message: String)
}
```

### 3.3 消息处理流程
```
1. 接收回调请求 -> 2. 验证签名 -> 3. 解析消息
       ↓
4. 查询Channel配置 -> 5. 获取关联Agent -> 6. 加载对话历史
       ↓
7. 调用Agent处理 -> 8. 保存消息记录 -> 9. 返回响应/推送消息
```

## 四、实施计划

### Task 1: 创建 vipclaw-channel 模块基础结构
- 创建 pom.xml 和基础 Kotlin 文件
- 定义 ChannelType 枚举和 ChannelSpec 数据类

### Task 2: 实现核心适配器接口和消息模型
- ChannelAdaptor 接口
- ChannelMessage 统一消息模型
- MessageConverter 消息转换器

### Task 3: 实现企业微信适配器
- 消息解析（XML格式）
- 签名验证
- 消息推送

### Task 4: 实现飞书适配器
- 消息解析（JSON格式）
- 签名验证
- 消息推送

### Task 5: 实现钉钉适配器
- 消息解析（JSON格式）
- 签名验证
- 消息推送

### Task 6: 实现 HTTP 通用适配器
- RESTful API 接口
- Token 认证

### Task 7: 扩展 vipclaw-admin 模块
- 创建 Channel、ChannelMessage 实体
- 创建 Mapper 和 Service
- 创建 ChannelController 处理回调

### Task 8: 数据库迁移脚本
- 创建 channel 表
- 创建 channel_message 表

### Task 9: 前端页面开发
- Channel 管理列表页
- Channel 创建/编辑表单
- 消息记录查看

### Task 10: 集成测试
- 各平台回调测试
- 多轮对话测试