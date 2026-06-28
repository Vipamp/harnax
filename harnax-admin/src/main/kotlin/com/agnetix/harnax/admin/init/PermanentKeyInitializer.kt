package com.agnetix.harnax.admin.init

import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * Initializes permanent API keys for existing users and SYSTEM keys for internal services.
 * Runs once on application startup.
 */
@Component
class PermanentKeyInitializer(
    private val sysUserMapper: SysUserMapper,
    private val apiKeyService: ApiKeyService,
    private val apiKeyMapper: ApiKeyMapper,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(PermanentKeyInitializer::class.java)

    override fun run(vararg args: String) {
        // 1. Initialize SYSTEM keys (e.g. channel-service)
        try {
            apiKeyService.initSystemKeys()
            log.info("System API keys initialized successfully")
        } catch (e: Exception) {
            log.error("Failed to initialize system API keys: {}", e.message, e)
        }

        // 2. Initialize permanent keys for existing users who don't have one
        try {
            val allUsers = sysUserMapper.selectAllActive()
            var count = 0
            for (user in allUsers) {
                val existing = apiKeyMapper.selectPermanentKeyByUserId(user.id)
                if (existing == null) {
                    try {
                        apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId)
                        count++
                        log.info("Permanent key initialized for user: {}", user.username)
                    } catch (e: Exception) {
                        log.error("Failed to initialize permanent key for user: {}", user.username, e)
                    }
                }
            }
            if (count > 0) {
                log.info("Permanent API keys initialized for {} existing users", count)
            }
        } catch (e: Exception) {
            log.error("Failed to initialize permanent API keys for existing users: {}", e.message, e)
        }
    }
}
