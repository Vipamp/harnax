package com.agnetix.harnax.admin.runner

import com.agnetix.harnax.admin.dto.SysUserCreateRequest
import com.agnetix.harnax.admin.service.SysUserService
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * Auto initialize admin user on application startup
 */
@Component
class AdminUserInitializer(
    private val sysUserService: SysUserService,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(AdminUserInitializer::class.java)

    override fun run(vararg args: String) {
        // Check if admin user already exists
        val existingUser = try {
            sysUserService.getByUsername("admin")
        } catch (e: Exception) {
            null
        }

        if (existingUser != null) {
            log.info("✅ Admin user already exists, skip initialization")
            return
        }

        log.info("🚀 Start initializing admin user...")

        try {
            // Create admin user
            // Note: Frontend encrypts password with SHA-256, so we also need to encrypt with SHA-256 first
            // This way the database stores BCrypt(SHA-256(plain password)), consistent with frontend user creation logic
            val plainPassword = "admin123"
            val sha256Password = sha256(plainPassword)

            val request = SysUserCreateRequest(
                username = "admin",
                password = sha256Password, // Pass SHA-256 encrypted password, backend will apply BCrypt encryption again
                nickname = "System Administrator",
                email = "admin@harnax.com",
                phone = "13800138000",
                gender = 1,
            )

            sysUserService.createUser(request)
            log.info("✅ Admin user initialized successfully!")
            log.info("   Username: admin")
            log.info("   Password: $plainPassword")
            log.info("   Please change password immediately after login!")
        } catch (e: Exception) {
            log.error("❌ Admin user initialization failed: ${e.message}", e)
        }
    }

    /**
     * SHA-256 encryption
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
