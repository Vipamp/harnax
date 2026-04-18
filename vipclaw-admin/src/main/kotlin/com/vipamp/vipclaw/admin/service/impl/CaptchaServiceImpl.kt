package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.CaptchaResponse
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.service.CaptchaService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * 验证码服务实现类
 */
@Service
class CaptchaServiceImpl : CaptchaService {

    private val log = LoggerFactory.getLogger(CaptchaServiceImpl::class.java)

    // 简化实现:使用内存存储验证码(实际项目中应使用 Redis)
    private val captchaStore = ConcurrentHashMap<String, CaptchaInfo>()

    companion object {
        // 验证码宽度
        private const val WIDTH = 120
        // 验证码高度
        private const val HEIGHT = 40
        // 验证码字符数
        private const val CODE_LENGTH = 4
        // 验证码过期时间(5 分钟)
        private const val EXPIRE_TIME: Long = 300
        // 验证码字符集合(去除容易混淆的字符)
        private const val CHAR_SET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }

    override fun generateCaptcha(): CaptchaResponse {
        return try {
            // 1. 生成随机验证码
            val code = generateRandomCode()

            // 2. 生成验证码图片
            val image = createCaptchaImage(code)

            // 3. 转换为 Base64
            val base64Image = convertToBase64(image)

            // 4. 生成唯一 key
            val captchaKey = generateCaptchaKey()

            // 5. 存储验证码信息
            captchaStore[captchaKey] = CaptchaInfo(code, System.currentTimeMillis())

            log.info("生成验证码,captchaKey: {}", captchaKey)

            // 6. 返回响应
            CaptchaResponse.builder()
                .imageBase64(base64Image)
                .captchaKey(captchaKey)
                .expiresIn(EXPIRE_TIME)
                .build()
        } catch (e: Exception) {
            log.error("生成验证码失败", e)
            throw BizException("生成验证码失败:${e.message}")
        }
    }

    override fun validateCaptcha(captchaKey: String?, code: String?): Boolean {
        if (captchaKey == null || code == null) {
            return false
        }

        val captchaInfo = captchaStore[captchaKey]
            ?: run {
                log.warn("验证码不存在,captchaKey: {}", captchaKey)
                return false
            }

        // 检查是否过期
        val currentTime = System.currentTimeMillis()
        if (currentTime - captchaInfo.createTime > TimeUnit.SECONDS.toMillis(EXPIRE_TIME)) {
            captchaStore.remove(captchaKey)
            log.warn("验证码已过期,captchaKey: {}", captchaKey)
            return false
        }

        // 验证验证码(忽略大小写)
        val valid = captchaInfo.code.equals(code, ignoreCase = true)

        // 验证后删除验证码(一次性使用)
        captchaStore.remove(captchaKey)

        log.info("验证码验证{},captchaKey: {}, code: {}", if (valid) "成功" else "失败", captchaKey, code)

        return valid
    }

    /**
     * 生成随机验证码
     */
    private fun generateRandomCode(): String {
        val random = Random()
        return (1..CODE_LENGTH)
            .map { CHAR_SET[random.nextInt(CHAR_SET.length)] }
            .joinToString("")
    }

    /**
     * 创建验证码图片
     */
    private fun createCaptchaImage(code: String): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        // 设置背景色
        g.color = Color.WHITE
        g.fillRect(0, 0, WIDTH, HEIGHT)

        // 设置字体
        g.font = java.awt.Font("Arial", java.awt.Font.BOLD, 24)

        // 绘制干扰线
        drawDisturbanceLines(g)

        // 绘制验证码文字
        drawCodeString(g, code)

        // 绘制干扰点
        drawDisturbancePoints(g)

        g.dispose()
        return image
    }

    /**
     * 绘制干扰线
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
     * 绘制验证码文字
     */
    private fun drawCodeString(g: Graphics2D, code: String) {
        val random = Random()
        val fontSize = HEIGHT - 8
        val charWidth = WIDTH / CODE_LENGTH

        for (i in code.indices) {
            g.color = getRandomColor()
            g.drawString(
                code[i].toString(),
                i * charWidth + 10,
                (fontSize + random.nextInt(5) + 5).toFloat()
            )
        }
    }

    /**
     * 绘制干扰点
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
     * 获取随机颜色
     */
    private fun getRandomColor(): Color {
        val random = Random()
        return Color(random.nextInt(200), random.nextInt(200), random.nextInt(200))
    }

    /**
     * 将图片转换为 Base64
     */
    private fun convertToBase64(image: BufferedImage): String {
        val baos = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        val imageBytes = baos.toByteArray()
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        return "data:image/png;base64,$base64"
    }

    /**
     * 生成验证码 key
     */
    private fun generateCaptchaKey(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    /**
     * 验证码信息内部类
     */
    private data class CaptchaInfo(
        val code: String,
        val createTime: Long
    )
}
