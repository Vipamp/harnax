package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.dto.McpConfigEntry
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.exception.BizException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

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

    // 与被测类注入的那个保持一致：裸 ObjectMapper 绑不了 Kotlin 数据类的构造参数
    private val objectMapper = jacksonObjectMapper()
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

        @Test
        @DisplayName("serializeWithEncryption - 掩码值沿用库里的密文")
        fun `serializeWithEncryption should carry over stored ciphertext for masked value`() {
            // Given - 详情接口只回显掩码，编辑表单没动这个字段时原样提交回来
            val stored = encryptor.serializeWithEncryption(
                listOf(McpConfigEntry(key = "Authorization", value = "Bearer sk-real-token", secret = true)),
            )
            val masked = listOf(McpConfigEntry(key = "Authorization", value = "Bea****oken", secret = true))

            // When
            val json = encryptor.serializeWithEncryption(masked, stored)

            // Then - 真凭据还在，没有被掩码本身覆盖
            assertEquals("Bearer sk-real-token", encryptor.decryptToMap(json)["Authorization"])
        }

        @Test
        @DisplayName("serializeWithEncryption - 短掩码 ****** 同样沿用库里的密文")
        fun `serializeWithEncryption should carry over ciphertext for the fixed mask`() {
            val stored = encryptor.serializeWithEncryption(
                listOf(McpConfigEntry(key = "X-API-Key", value = "sk-1", secret = true)),
            )

            val json = encryptor.serializeWithEncryption(
                listOf(McpConfigEntry(key = "X-API-Key", value = "******", secret = true)),
                stored,
            )

            assertEquals("sk-1", encryptor.decryptToMap(json)["X-API-Key"])
        }

        @Test
        @DisplayName("serializeWithEncryption - 掩码值无可沿用的密文时要求重新填写")
        fun `serializeWithEncryption should ask re-enter when no stored ciphertext matches`() {
            // Given - 换了 key 名字，旧密文对不上，静默保留任何值都更糟
            val entries = listOf(McpConfigEntry(key = "X-API-Key", value = "******", secret = true))

            val exception = assertThrows<BizException> { encryptor.serializeWithEncryption(entries, null) }

            assertTrue(exception.message!!.contains("X-API-Key"))
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
        @DisplayName("decrypt - 解密还原明文")
        fun `decrypt should round-trip a value encrypted by aesUtil`() {
            val plain = "hnx_sk_live_single_value"
            val encrypted = aesUtil.encrypt(plain)

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
        @DisplayName("serializeToolEnvParams - 掩码defaultValue沿用库里的密文")
        fun `serializeToolEnvParams should carry over stored ciphertext for masked defaultValue`() {
            val stored = encryptor.serializeToolEnvParams(
                listOf(ToolEnvParamEntry(envParamName = "API_KEY", secret = true, defaultValue = "sk-real-value")),
            )

            val json = encryptor.serializeToolEnvParams(
                listOf(ToolEnvParamEntry(envParamName = "API_KEY", secret = true, defaultValue = "sk****alue")),
                stored,
            )

            assertEquals("sk-real-value", encryptor.decryptToolEnvParamsToMap(json)["API_KEY"])
        }

        @Test
        @DisplayName("serializeToolEnvParams - 掩码defaultValue无密文可承接时报错")
        fun `serializeToolEnvParams should ask re-enter when no stored ciphertext matches`() {
            val entries = listOf(
                ToolEnvParamEntry(envParamName = "NEW_KEY", secret = true, defaultValue = "sk****alue"),
            )

            val exception = assertThrows<BizException> {
                encryptor.serializeToolEnvParams(entries, null)
            }
            assertTrue(exception.message!!.contains("NEW_KEY"), "错误信息要指名是哪个参数")
            assertTrue(exception.message!!.contains("re-enter"))
        }

        @Test
        @DisplayName("serializeToolEnvParams - 空白敏感defaultValue不加密")
        fun `serializeToolEnvParams should not encrypt blank secret defaultValue`() {
            val entries = listOf(
                ToolEnvParamEntry(envParamName = "API_KEY", secret = true, defaultValue = ""),
            )

            val json = encryptor.serializeToolEnvParams(entries)

            val stored = encryptor.deserializeToolEnvEntries(json!!)
            assertEquals("", stored.first().defaultValue, "空白值原样存，不占用密文")
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

    @Nested
    @DisplayName("单列凭据解析测试")
    inner class ResolveSecretTests {

        @Test
        @DisplayName("resolveSecret - 未提供时沿用库中密文")
        fun `resolveSecret should carry the stored ciphertext when nothing was provided`() {
            assertEquals("stored", encryptor.resolveSecret(null, "stored"))
        }

        @Test
        @DisplayName("resolveSecret - 掩码回写不销毁凭据")
        fun `resolveSecret should carry the stored ciphertext for a masked value`() {
            assertEquals("stored", encryptor.resolveSecret("sk-****abcd", "stored"))
        }

        @Test
        @DisplayName("resolveSecret - 掩码但库里没有可沿用的值")
        fun `resolveSecret should reject a masked value with nothing to carry over`() {
            val error = assertThrows<BizException> { encryptor.resolveSecret("sk-****abcd", null) }
            assertTrue(error.message!!.contains("please re-enter it"), error.message)
        }

        @Test
        @DisplayName("resolveSecret - 空串表示清空，成为公开客户端")
        fun `resolveSecret should clear the secret for a blank value`() {
            assertNull(encryptor.resolveSecret("", "stored"))
            assertNull(encryptor.resolveSecret("   ", "stored"))
        }

        @Test
        @DisplayName("resolveSecret - 新值加密入库并可解密回来")
        fun `resolveSecret should encrypt a fresh value and trim it`() {
            val encrypted = encryptor.resolveSecret("  s3cret  ", "stored")

            assertNotNull(encrypted)
            assertFalse(encrypted!!.contains("s3cret"))
            assertEquals("s3cret", aesUtil.decrypt(encrypted!!))
        }

        @Test
        @DisplayName("resolveSecret - 没有库存值时也能直接加密新值")
        fun `resolveSecret should accept a fresh value with nothing stored`() {
            assertEquals("s3cret", aesUtil.decrypt(encryptor.resolveSecret("s3cret", null)!!))
        }
    }
}
