package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.CaptchaResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.CaptchaService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Captcha service implementation
 */
@Service
class CaptchaServiceImpl(
    // Test-only master code for UI automation; empty (default) = disabled. Never set in production.
    @Value("\${captcha.test-master-code:}") private val testMasterCode: String,
) : CaptchaService {

    private val log = LoggerFactory.getLogger(CaptchaServiceImpl::class.java)

    // Simplified implementation: use in-memory storage for captcha (should use Redis in actual project)
    private val captchaStore = ConcurrentHashMap<String, CaptchaInfo>()

    companion object {
        // Captcha width
        private const val WIDTH = 120

        // Captcha height
        private const val HEIGHT = 40

        // Captcha character count
        private const val CODE_LENGTH = 4

        // Captcha expiration time (5 minutes)
        private const val EXPIRE_TIME: Long = 300

        // Captcha character set (excluding confusing characters)
        private const val CHAR_SET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }

    override fun generateCaptcha(): CaptchaResponse = try {
        // 1. Generate random captcha code
        val code = generateRandomCode()

        // 2. Generate captcha image
        val image = createCaptchaImage(code)

        // 3. Convert to Base64
        val base64Image = convertToBase64(image)

        // 4. Generate unique key
        val captchaKey = generateCaptchaKey()

        // 5. Store captcha info
        captchaStore[captchaKey] = CaptchaInfo(code, System.currentTimeMillis())

        log.info("Captcha generated, captchaKey: {}", captchaKey)

        // 6. Return response
        CaptchaResponse(base64Image, captchaKey, EXPIRE_TIME)
    } catch (e: Exception) {
        log.error("Failed to generate captcha", e)
        throw BizException("Failed to generate captcha: ${e.message}")
    }

    override fun validateCaptcha(captchaKey: String, code: String): Boolean {
        if (testMasterCode.isNotBlank() && code == testMasterCode) {
            log.warn("Captcha bypassed via test master code, captchaKey: {}", captchaKey)
            return true
        }

        val captchaInfo = captchaStore[captchaKey]
            ?: run {
                log.warn("Captcha not found, captchaKey: {}", captchaKey)
                return false
            }

        // Check if expired
        val currentTime = System.currentTimeMillis()
        if (currentTime - captchaInfo.createTime > TimeUnit.SECONDS.toMillis(EXPIRE_TIME)) {
            captchaStore.remove(captchaKey)
            log.warn("Captcha expired, captchaKey: {}", captchaKey)
            return false
        }

        // Validate captcha (case insensitive)
        val valid = captchaInfo.code.equals(code, ignoreCase = true)

        // Delete captcha after validation (one-time use)
        captchaStore.remove(captchaKey)

        log.info("Captcha validation {}, captchaKey: {}, code: {}", if (valid) "successful" else "failed", captchaKey, code)

        return valid
    }

    /**
     * Generate random captcha code
     */
    private fun generateRandomCode(): String {
        val random = Random()
        return (1..CODE_LENGTH)
            .map { CHAR_SET[random.nextInt(CHAR_SET.length)] }
            .joinToString("")
    }

    /**
     * Create captcha image
     */
    private fun createCaptchaImage(code: String): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        // Set background color
        g.color = Color.WHITE
        g.fillRect(0, 0, WIDTH, HEIGHT)

        // Set font
        g.font = java.awt.Font("Arial", java.awt.Font.BOLD, 24)

        // Draw interference lines
        drawDisturbanceLines(g)

        // Draw captcha text
        drawCodeString(g, code)

        // Draw interference points
        drawDisturbancePoints(g)

        g.dispose()
        return image
    }

    /**
     * Draw interference lines
     */
    private fun drawDisturbanceLines(g: Graphics2D) {
        val random = Random()
        for (i in 0 until 5) {
            g.color = getRandomColor()
            val x1 = random.nextInt(WIDTH)
            val y1 = random.nextInt(HEIGHT)
            val x2 = random.nextInt(WIDTH)
            val y2 = random.nextInt(HEIGHT)
            g.drawLine(x1, y1, x2, y2)
        }
    }

    /**
     * Draw captcha text
     */
    private fun drawCodeString(g: Graphics2D, code: String) {
        val random = Random()
        val fontSize = HEIGHT - 8
        val charWidth = WIDTH / CODE_LENGTH

        for (i in code.indices) {
            g.color = getRandomColor()
            g.drawString(
                code[i].toString(),
                (i * charWidth + 10).toFloat(),
                (fontSize + random.nextInt(5) + 5).toFloat(),
            )
        }
    }

    /**
     * Draw interference points
     */
    private fun drawDisturbancePoints(g: Graphics2D) {
        val random = Random()
        for (i in 0 until 100) {
            g.color = getRandomColor()
            val x = random.nextInt(WIDTH)
            val y = random.nextInt(HEIGHT)
            g.drawRect(x, y, 1, 1)
        }
    }

    /**
     * Get random color
     */
    private fun getRandomColor(): Color {
        val random = Random()
        return Color(random.nextInt(200), random.nextInt(200), random.nextInt(200))
    }

    /**
     * Convert image to Base64
     */
    private fun convertToBase64(image: BufferedImage): String {
        val baos = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        val imageBytes = baos.toByteArray()
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        return "data:image/png;base64,$base64"
    }

    /**
     * Generate captcha key
     */
    private fun generateCaptchaKey(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * Captcha info inner class
     */
    private data class CaptchaInfo(
        val code: String,
        val createTime: Long,
    )
}
