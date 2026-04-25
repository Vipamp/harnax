package com.vipamp.vipclaw.admin.runner

import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.service.SysUserService
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * 应用启动时自动初始化 admin 用户
 */
@Component
class AdminUserInitializer(
    private val sysUserService: SysUserService
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(AdminUserInitializer::class.java)

    override fun run(vararg args: String?) {
        // 检查 admin 用户是否已存在
        val existingUser = try {
            sysUserService.getByUsername("admin")
        } catch (e: Exception) {
            null
        }

        if (existingUser != null) {
            log.info("✅ Admin 用户已存在，跳过初始化")
            return
        }

        log.info("🚀 开始初始化 admin 用户...")

        try {
            // 创建 admin 用户
            // 注意：前端会对密码进行 SHA-256 加密，所以这里也需要先进行 SHA-256 加密
            // 这样数据库中存储的是 BCrypt(SHA-256(明文密码))，与前端创建用户的逻辑一致
            val plainPassword = "admin123"
            val sha256Password = sha256(plainPassword)

            val request = SysUserCreateRequest(
                username = "admin",
                password = sha256Password,  // 传入 SHA-256 加密后的密码，后端会再进行 BCrypt 加密
                nickname = "系统管理员",
                email = "admin@vipclaw.com",
                phone = "13800138000",
                gender = 1
            )

            sysUserService.createUser(request)
            log.info("✅ Admin 用户初始化成功！")
            log.info("   用户名: admin")
            log.info("   密码: $plainPassword")
            log.info("   请登录后立即修改密码！")

        } catch (e: Exception) {
            log.error("❌ Admin 用户初始化失败: ${e.message}", e)
        }
    }

    /**
     * SHA-256 加密
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
