package com.agnetix.harnax.admin.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * AesUtil 单元测试
 * 测试 AES-256-GCM 加密/解密功能
 *
 * @author agnetix
 * @since 2026-06-28
 */
@DisplayName("AesUtil 加密解密测试")
class AesUtilTest {

    private val testSecretKey = "change-me-32-chars-secret-key!!"
    private val aesUtil = AesUtil(testSecretKey)

    @Nested
    @DisplayName("加密测试")
    inner class EncryptTests {

        @Test
        @DisplayName("encrypt - 加密后结果不为空")
        fun `encrypt should return non-empty string`() {
            val plainText = "hnx_sk_live_test_key_123"
            val encrypted = aesUtil.encrypt(plainText)

            assertNotNull(encrypted)
            assertTrue(encrypted.isNotEmpty())
            assertNotEquals(plainText, encrypted)
        }

        @Test
        @DisplayName("encrypt - 相同明文每次加密结果不同（IV随机）")
        fun `encrypt should produce different ciphertext for same plaintext`() {
            val plainText = "hnx_sk_live_test_key_123"
            val encrypted1 = aesUtil.encrypt(plainText)
            val encrypted2 = aesUtil.encrypt(plainText)

            assertNotEquals(encrypted1, encrypted2, "每次加密应使用不同IV，结果应不同")
        }

        @Test
        @DisplayName("encrypt - 加密结果是Base64编码")
        fun `encrypt should return Base64 encoded string`() {
            val plainText = "test_api_key_value"
            val encrypted = aesUtil.encrypt(plainText)

            // Base64 字符集验证
            val base64Pattern = Regex("^[A-Za-z0-9+/]+=*$")
            assertTrue(base64Pattern.matches(encrypted), "加密结果应为Base64编码")
        }
    }

    @Nested
    @DisplayName("解密测试")
    inner class DecryptTests {

        @Test
        @DisplayName("decrypt - 解密后还原为原始明文")
        fun `decrypt should return original plaintext`() {
            val plainText = "hnx_sk_live_abcdefghijklmnopqrstuvwxyz123456"
            val encrypted = aesUtil.encrypt(plainText)
            val decrypted = aesUtil.decrypt(encrypted)

            assertEquals(plainText, decrypted)
        }

        @Test
        @DisplayName("decrypt - 多次加密解密保持一致")
        fun `decrypt should consistently return original plaintext`() {
            val plainTexts = listOf(
                "hnx_sk_live_key1",
                "hnx_sk_live_key2_with_longer_name",
                "short",
                "a".repeat(100),
            )

            for (plainText in plainTexts) {
                val encrypted = aesUtil.encrypt(plainText)
                val decrypted = aesUtil.decrypt(encrypted)
                assertEquals(plainText, decrypted, "明文长度=${plainText.length} 时应正确解密")
            }
        }

        @Test
        @DisplayName("decrypt - 无效Base64抛出异常")
        fun `decrypt should throw exception for invalid Base64`() {
            assertThrows<Exception> {
                aesUtil.decrypt("not_valid_base64!!!")
            }
        }

        @Test
        @DisplayName("decrypt - 篡改密文后解密失败")
        fun `decrypt should fail for tampered ciphertext`() {
            val plainText = "hnx_sk_live_test_key"
            val encrypted = aesUtil.encrypt(plainText)

            // 篡改密文中间部分
            val tampered = encrypted.substring(0, encrypted.length / 2) + "AAAA" + encrypted.substring(encrypted.length / 2 + 4)

            assertThrows<Exception> {
                aesUtil.decrypt(tampered)
            }
        }
    }

    @Nested
    @DisplayName("不同密钥测试")
    inner class DifferentKeyTests {

        @Test
        @DisplayName("不同密钥无法解密")
        fun `decrypt should fail with different secret key`() {
            val plainText = "hnx_sk_live_secret_data"
            val encrypted = aesUtil.encrypt(plainText)

            // 使用不同密钥创建新的 AesUtil
            val otherAesUtil = AesUtil("different-32-chars-secret-key!!!")

            assertThrows<Exception> {
                otherAesUtil.decrypt(encrypted)
            }
        }
    }

    @Nested
    @DisplayName("密钥长度分支测试（copyOf(32) 补零/截断语义）")
    inner class KeyLengthTests {

        @Test
        @DisplayName("短密钥（<32字节）被补零 - 加密解密往返成功")
        fun `short key should be zero-padded and round-trip successfully`() {
            val shortKeyAesUtil = AesUtil("short-key")
            val plainText = "hnx_sk_live_short_key_data"

            val encrypted = shortKeyAesUtil.encrypt(plainText)
            val decrypted = shortKeyAesUtil.decrypt(encrypted)

            assertEquals(plainText, decrypted)
        }

        @Test
        @DisplayName("超长密钥（>32字节）被截断 - 前32字节相同的两个密钥可互相解密")
        fun `over-long keys sharing first 32 bytes should decrypt each other`() {
            // 记录现状：copyOf(32) 只保留前 32 字节，超出部分被静默丢弃。
            // 因此前 32 字节相同、后缀不同的两个密钥实际上是同一个 AES 密钥。
            val prefix32 = "0123456789abcdef0123456789abcdef" // 恰好 32 字节
            val aesUtilA = AesUtil(prefix32 + "-suffix-A")
            val aesUtilB = AesUtil(prefix32 + "-completely-different-suffix-B")
            val plainText = "hnx_sk_live_truncation_semantics"

            val encryptedByA = aesUtilA.encrypt(plainText)
            val encryptedByB = aesUtilB.encrypt(plainText)

            assertEquals(plainText, aesUtilB.decrypt(encryptedByA), "B 应能解密 A 的密文（截断后密钥相同）")
            assertEquals(plainText, aesUtilA.decrypt(encryptedByB), "A 应能解密 B 的密文（截断后密钥相同）")
        }

        @Test
        @DisplayName("空字符串密钥不抛异常 - 补零后仍可加解密")
        fun `empty string key should not throw and remain usable`() {
            val emptyKeyAesUtil = AesUtil("")
            val plainText = "hnx_sk_live_empty_key_data"

            val encrypted = emptyKeyAesUtil.encrypt(plainText)
            val decrypted = emptyKeyAesUtil.decrypt(encrypted)

            assertEquals(plainText, decrypted)
        }
    }
}
