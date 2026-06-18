package com.agnetix.harnax.auth

/**
 * API Key 存储接口
 */
interface ApiKeyStore {
    /**
     * 根据 key hash 查找 API Key 信息
     */
    fun findByKeyHash(keyHash: String): ApiKeyInfo?
}
