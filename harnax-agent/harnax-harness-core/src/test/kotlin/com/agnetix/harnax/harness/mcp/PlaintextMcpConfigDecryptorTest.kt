package com.agnetix.harnax.harness.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * PlaintextMcpConfigDecryptor 单元测试
 *
 * 覆盖两种下发形态：Admin 内部 API 解密后的扁平 JSON 对象，以及库里原本的条目数组。
 * 后者是防御性兼容——正常运行时不会收到，收到说明某条下发链路漏了解密。
 */
class PlaintextMcpConfigDecryptorTest {

    private val decryptor = PlaintextMcpConfigDecryptor()

    @Nested
    @DisplayName("headers 形态（McpConfigEntry）")
    inner class HeaderShape {

        @Test
        @DisplayName("Admin 下发的扁平明文对象直接解析")
        fun `should parse a flat plain-text object`() {
            val result = decryptor.decryptToMap("""{"Authorization":"Bearer token-abc"}""")

            assertEquals(mapOf("Authorization" to "Bearer token-abc"), result)
        }

        @Test
        @DisplayName("null 与空白都归一为空 Map")
        fun `should return empty map for null or blank`() {
            assertTrue(decryptor.decryptToMap(null).isEmpty())
            assertTrue(decryptor.decryptToMap("").isEmpty())
            assertTrue(decryptor.decryptToMap("   ").isEmpty())
        }

        @Test
        @DisplayName("存储态数组仍可解析，secret 条目按原文取出")
        fun `should still parse the stored array shape`() {
            val stored = """[{"key":"Authorization","value":"cipher-text","secret":true},""" +
                """{"key":"X-Trace","value":"on","secret":false}]"""

            val result = decryptor.decryptToMap(stored)

            // secret 条目在这里已无法解密，取原文是为了让调用方至少拿到 key，并通过告警日志暴露问题
            assertEquals(mapOf("Authorization" to "cipher-text", "X-Trace" to "on"), result)
        }

        @Test
        @DisplayName("key 为空的条目被丢弃")
        fun `should skip entries without a key`() {
            val result = decryptor.decryptToMap("""[{"key":"","value":"v"},{"key":"K","value":"V"}]""")

            assertEquals(mapOf("K" to "V"), result)
        }

        @Test
        @DisplayName("缺 value 字段时退化为空串而非整个 Map 为空")
        fun `should degrade a missing value to an empty string`() {
            assertEquals(mapOf("K" to ""), decryptor.decryptToMap("""[{"key":"K"}]"""))
        }
    }

    @Nested
    @DisplayName("环境参数形态（ToolEnvParamEntry）")
    inner class EnvParamShape {

        @Test
        @DisplayName("按 envParamName / defaultValue 取值")
        fun `should read envParamName and defaultValue`() {
            val stored = """[{"envParamName":"GITHUB_TOKEN","defaultValue":"ghp_plain","secret":true}]"""

            assertEquals(mapOf("GITHUB_TOKEN" to "ghp_plain"), decryptor.decryptToolEnvParamsToMap(stored))
        }

        @Test
        @DisplayName("扁平明文对象同样适用（Admin 已解密的下发）")
        fun `should accept a flat plain-text object`() {
            val result = decryptor.decryptToolEnvParamsToMap("""{"GITHUB_TOKEN":"ghp_plain"}""")

            assertEquals(mapOf("GITHUB_TOKEN" to "ghp_plain"), result)
        }
    }

    @Nested
    @DisplayName("异常输入不抛出")
    inner class MalformedInput {

        @Test
        @DisplayName("非 JSON 文本返回空 Map")
        fun `should return empty map for non-json text`() {
            assertTrue(decryptor.decryptToMap("not json at all").isEmpty())
        }

        @Test
        @DisplayName("被截断的 JSON 返回空 Map")
        fun `should return empty map for truncated json`() {
            assertTrue(decryptor.decryptToMap("""{"Authorization":"Bearer""").isEmpty())
        }

        @Test
        @DisplayName("顶层是标量时返回空 Map")
        fun `should return empty map for a scalar payload`() {
            assertTrue(decryptor.decryptToMap("12345").isEmpty())
        }
    }
}
