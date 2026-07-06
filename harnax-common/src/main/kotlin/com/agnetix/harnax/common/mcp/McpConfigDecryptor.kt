package com.agnetix.harnax.common.mcp

/**
 * MCP 配置解密器接口
 * 将数据库中存储的加密 JSON 字符串解析为明文 Key-Value Map
 * 跨模块使用：harnax-agent-utils/harness-core 消费，harnax-admin 实现
 */
fun interface McpConfigDecryptor {
    /**
     * 将加密的 JSON 字符串反序列化 + 解密后返回明文 Map
     *
     * @param encryptedJson 数据库中存储的 JSON 字符串（secret 条目的 value 已加密）
     * @return 明文的 Key-Value Map
     */
    fun decryptToMap(encryptedJson: String?): Map<String, String>
}
