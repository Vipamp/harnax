package com.agnetix.harnax.admin.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
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
    private val cipherAlgorithm = "AES/GCM/NoPadding"
    private val keySpec = SecretKeySpec(secretKey.toByteArray().copyOf(32), "AES")
    private val secureRandom = SecureRandom()

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
}
