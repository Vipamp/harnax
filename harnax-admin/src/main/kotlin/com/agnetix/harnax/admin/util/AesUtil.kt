package com.agnetix.harnax.admin.util

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption utility for encrypting/decrypting permanent API key raw values.
 * Used to store rawKey in api_key.raw_key_encrypted column.
 */
@Component
class AesUtil(
    @Value("\${harnax.aes.secret-key:change-me-32-chars-secret-key!!}")
    private val secretKey: String,
) {
    private val log = LoggerFactory.getLogger(AesUtil::class.java)

    private val cipherAlgorithm = "AES/GCM/NoPadding"
    private val keySpec = SecretKeySpec(normalizedKey(), "AES")
    private val secureRandom = SecureRandom()

    /**
     * The key material, exactly as [keySpec] sees it.
     *
     * `copyOf(32)` pads a short key with zero bytes and drops everything past 32, in silence: a
     * 20-character key is therefore 12 bytes weaker than it looks, and two deployments that think
     * they configured the same secret can end up with different keys. Say so once at startup -
     * including that fixing it changes the key, so what was already written stops opening.
     */
    private fun normalizedKey(): ByteArray {
        val bytes = secretKey.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size != AES_KEY_BYTES) {
            log.warn(
                "harnax.aes.secret-key is {} bytes but AES-256 needs {}, so it is {} to {} bytes. " +
                    "Everything already encrypted with this key was written with the padded form and cannot be " +
                    "decrypted after the key is corrected - set HARNAX_AES_SECRET_KEY to exactly {} bytes and " +
                    "re-enter the affected secrets (model api keys, tool and MCP headers, api keys, MCP OAuth grants)",
                bytes.size,
                AES_KEY_BYTES,
                if (bytes.size < AES_KEY_BYTES) "padded with zero bytes" else "truncated",
                AES_KEY_BYTES,
                AES_KEY_BYTES,
            )
        }
        if (secretKey == PLACEHOLDER_KEY) {
            log.warn(
                "harnax.aes.secret-key is still the placeholder shipped in application.yml: every secret this " +
                    "service encrypts (API keys, tool and MCP headers, MCP OAuth grants) is readable to anyone " +
                    "with the source tree. Set HARNAX_AES_SECRET_KEY before this deployment holds real credentials",
            )
        }
        return bytes.copyOf(AES_KEY_BYTES)
    }

    /**
     * Encrypt plaintext using AES-256-GCM.
     * Returns Base64-encoded string containing IV + ciphertext.
     */
    fun encrypt(plainText: String): String {
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(cipherAlgorithm)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray())
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    /**
     * Decrypt Base64-encoded ciphertext (IV + ciphertext) back to plaintext.
     */
    fun decrypt(encryptedBase64: String): String {
        val decoded = Base64.getDecoder().decode(encryptedBase64)
        val iv = decoded.copyOfRange(0, 12)
        val data = decoded.copyOfRange(12, decoded.size)
        val cipher = Cipher.getInstance(cipherAlgorithm)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data))
    }

    companion object {
        private const val AES_KEY_BYTES = 32

        /** The default in `application.yml`, which is public and therefore not a secret. */
        private const val PLACEHOLDER_KEY = "change-me-32-chars-secret-key!!"
    }
}
