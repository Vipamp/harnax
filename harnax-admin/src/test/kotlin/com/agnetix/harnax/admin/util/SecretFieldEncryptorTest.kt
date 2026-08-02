package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.dto.McpConfigEntry
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.ObjectMapper

/**
 * SecretFieldEncryptor 单元测试
 * 测试 MCP 配置敏感字段的加解密往返、空值处理和错误密文场景
 *
 * @author agnetix
 * @since 2026-06-28
 */
@DisplayName("SecretFieldEncryptor 敏感字段加解密测试")
class SecretFieldEncryptorTest {

    private val aesUtil = AesUtil("change-me-32-chars-secret-key!!")
    private val objectMapper = ObjectMapper()
    private val encryptor = SecretFieldEncryptor(aesUtil, objectMapper)

    @Nested
    @DisplayName("序列化加密测试")
    inner class SerializeWithEncryptionTests {

        @Test
        @DisplayName("serializeWithEncryption - null列表返回null")
        fun `serializeWithEncryption should return null for null entries`() {
            assertNull(encryptor.serializeWithEncryption(null))
        }

        @Test
        @DisplayName("serializeWithEncryption - 空列表返回null")
        fun `serializeWithEncryption should return null for empty entries`() {
            assertNull(encryptor.serializeWithEncryption(emptyList()))
        }

        @Test
        @DisplayName("serializeWithEncryption - 非敏感字段保持明文")
        fun `serializeWithEncryption should keep plain value for non-secret entry`() {
            val entries = listOf(McpConfigEntry(key = "Content-Type", value = "application/json", secret = false))

            val json = encryptor.serializeWithEncryption(entries)

            assertNotNull(json)
            assertTrue(json!!.contains("application/json"), "非敏感值应以明文存储")
        }

        @Test
        @DisplayName("serializeWithEncryption - 敏感字段被加密")
        fun `serializeWithEncryption should encrypt secret entry value`() {
            val entries = listOf(McpConfigEntry(key = "Authorization", value = "Bearer sk-secret", secret = true))

            val json = encryptor.serializeWithEncryption(entries)

            assertNotNull(json)
            assertFalse(json!!.contains("Bearer sk-secret"), "敏感值不应以明文出现在JSON中")
        }

        @Test
        @DisplayName("serializeWithEncryption - 敏感字段值为空白时不加密")
        fun `serializeWithEncryption should not encrypt blank secret value`() {
            val entries = listOf(McpConfigEntry(key = "API_KEY", value = "", secret = true))

            val json = encryptor.serializeWithEncryption(entries)

            assertNotNull(json)
            val deserialized = encryptor.deserializeEntries(json)
            assertEquals("", deserialized[0].value)
        }
    }

    @Nested
    @DisplayName("解密为 Map 测试")
    inner class DecryptToMapTests {

        @Test
        @DisplayName("decryptToMap - 加解密往返还原明文")
        fun `decryptToMap should round-trip encrypted secret values`() {
            val entries = listOf(
                McpConfigEntry(key = "Authorization", value = "Bearer sk-secret", secret = true),
                McpConfigEntry(key = "Content-Type", value = "application/json", secret = false),
            )

            val json = encryptor.serializeWithEncryption(entries)
            val map = encryptor.decryptToMap(json)

            assertEquals(2, map.size)
            assertEquals("Bearer sk-secret", map["Authorization"])
            assertEquals("application/json", map["Content-Type"])
        }

        @Test
        @DisplayName("decryptToMap - null输入返回空Map")
        fun `decryptToMap should return empty map for null json`() {
            assertTrue(encryptor.decryptToMap(null).isEmpty())
        }

        @Test
        @DisplayName("decryptToMap - 空白输入返回空Map")
        fun `decryptToMap should return empty map for blank json`() {
            assertTrue(encryptor.decryptToMap("").isEmpty())
            assertTrue(encryptor.decryptToMap("   ").isEmpty())
        }

        @Test
        @DisplayName("decryptToMap - 敏感值为错误密文时返回空Map")
        fun `decryptToMap should return empty map for invalid ciphertext`() {
            val json = """[{"key":"Authorization","value":"not-valid-ciphertext","secret":true}]"""

            val map = encryptor.decryptToMap(json)

            assertTrue(map.isEmpty(), "解密失败应被捕获并返回空Map")
        }

        @Test
        @DisplayName("decryptToMap - 非法JSON返回空Map")
        fun `decryptToMap should return empty map for invalid json`() {
            assertTrue(encryptor.decryptToMap("{not-a-json").isEmpty())
        }
    }

    @Nested
    @DisplayName("单值加解密测试")
    inner class SingleValueTests {

        @Test
        @DisplayName("encrypt/decrypt - 加解密往返还原明文")
        fun `encrypt and decrypt should round-trip plain value`() {
            val plain = "hnx_sk_live_single_value"
            val encrypted = encryptor.encrypt(plain)

            assertNotEquals(plain, encrypted)
            assertEquals(plain, encryptor.decrypt(encrypted))
        }

        @Test
        @DisplayName("decrypt - 错误密文抛出异常")
        fun `decrypt should throw exception for invalid ciphertext`() {
            assertThrows<Exception> {
                encryptor.decrypt("invalid-ciphertext!!!")
            }
        }
    }

    @Nested
    @DisplayName("工具环境参数加解密测试")
    inner class ToolEnvParamTests {

        @Test
        @DisplayName("serializeToolEnvParams - null或空列表返回null")
        fun `serializeToolEnvParams should return null for null or empty entries`() {
            assertNull(encryptor.serializeToolEnvParams(null))
            assertNull(encryptor.serializeToolEnvParams(emptyList()))
        }

        @Test
        @DisplayName("serializeToolEnvParams - 敏感defaultValue被加密")
        fun `serializeToolEnvParams should encrypt secret defaultValue`() {
            val entries = listOf(
                ToolEnvParamEntry(envParamName = "API_KEY", secret = true, defaultValue = "sk-plain-value"),
            )

            val json = encryptor.serializeToolEnvParams(entries)

            assertNotNull(json)
            assertFalse(json!!.contains("sk-plain-value"), "敏感默认值不应以明文出现")
        }

        @Test
        @DisplayName("serializeToolEnvParams - 非敏感defaultValue保持明文")
        fun `serializeToolEnvParams should keep plain defaultValue for non-secret entry`() {
            val entries = listOf(
                ToolEnvParamEntry(envParamName = "REGION", secret = false, defaultValue = "us-east-1"),
            )

            val json = encryptor.serializeToolEnvParams(entries)

            assertNotNull(json)
            assertTrue(json!!.contains("us-east-1"))
        }

        @Test
        @DisplayName("decryptToolEnvParamsToMap - 加解密往返还原明文")
        fun `decryptToolEnvParamsToMap should round-trip secret defaultValue`() {
            val entries = listOf(
                ToolEnvParamEntry(envParamName = "API_KEY", secret = true, defaultValue = "sk-plain-value"),
                ToolEnvParamEntry(envParamName = "REGION", secret = false, defaultValue = "us-east-1"),
            )

            val json = encryptor.serializeToolEnvParams(entries)
            val map = encryptor.decryptToolEnvParamsToMap(json)

            assertEquals("sk-plain-value", map["API_KEY"])
            assertEquals("us-east-1", map["REGION"])
        }

        @Test
        @DisplayName("decryptToolEnvParamsToMap - defaultValue为null时返回空字符串")
        fun `decryptToolEnvParamsToMap should return empty string for null defaultValue`() {
            val json = """[{"envParamName":"OPTIONAL_KEY","secret":false,"defaultValue":null}]"""

            val map = encryptor.decryptToolEnvParamsToMap(json)

            assertEquals("", map["OPTIONAL_KEY"])
        }

        @Test
        @DisplayName("decryptToolEnvParamsToMap - null或空白输入返回空Map")
        fun `decryptToolEnvParamsToMap should return empty map for null or blank json`() {
            assertTrue(encryptor.decryptToolEnvParamsToMap(null).isEmpty())
            assertTrue(encryptor.decryptToolEnvParamsToMap(" ").isEmpty())
        }

        @Test
        @DisplayName("decryptToolEnvParamsToMap - 错误密文返回空Map")
        fun `decryptToolEnvParamsToMap should return empty map for invalid ciphertext`() {
            val json = """[{"envParamName":"API_KEY","secret":true,"defaultValue":"bad-cipher"}]"""

            assertTrue(encryptor.decryptToolEnvParamsToMap(json).isEmpty())
        }
    }

    @Nested
    @DisplayName("反序列化条目测试")
    inner class DeserializeEntriesTests {

        @Test
        @DisplayName("deserializeEntries - 正常JSON反序列化成功")
        fun `deserializeEntries should parse valid json`() {
            val json = """[{"key":"Authorization","value":"Bearer x","secret":true}]"""

            val entries = encryptor.deserializeEntries(json)

            assertEquals(1, entries.size)
            assertEquals("Authorization", entries[0].key)
            assertEquals("Bearer x", entries[0].value)
            assertTrue(entries[0].secret)
        }

        @Test
        @DisplayName("deserializeEntries - null或空白返回空列表")
        fun `deserializeEntries should return empty list for null or blank json`() {
            assertTrue(encryptor.deserializeEntries(null).isEmpty())
            assertTrue(encryptor.deserializeEntries("").isEmpty())
        }

        @Test
        @DisplayName("deserializeEntries - 非法JSON返回空列表")
        fun `deserializeEntries should return empty list for invalid json`() {
            assertTrue(encryptor.deserializeEntries("{broken").isEmpty())
        }
    }
}
