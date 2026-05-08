package com.vipamp.vipclaw.admin.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 版本工具类单元测试
 */
@DisplayName("EditionUtil 单元测试")
class EditionUtilTest {

    private lateinit var editionUtil: EditionUtil

    @BeforeEach
    fun setUp() {
        editionUtil = mock()
    }

    @Test
    @DisplayName("应该返回正确的版本常量")
    fun `should return correct edition constants`() {
        assertEquals("personal", EditionUtil.EDITION_PERSONAL)
        assertEquals("enterprise", EditionUtil.EDITION_ENTERPRISE)
        assertEquals("public", EditionUtil.EDITION_PUBLIC)
    }

    @Test
    @DisplayName("应该正确判断个人版")
    fun `should correctly check personal edition`() {
        // 模拟个人版
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        assertTrue(editionUtil.isPersonal())
        assertFalse(editionUtil.isEnterprise())
        assertFalse(editionUtil.isPublic())
    }

    @Test
    @DisplayName("应该正确判断企业版")
    fun `should correctly check enterprise edition`() {
        // 模拟企业版
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        assertFalse(editionUtil.isPersonal())
        assertTrue(editionUtil.isEnterprise())
        assertFalse(editionUtil.isPublic())
    }

    @Test
    @DisplayName("应该正确判断公有云版")
    fun `should correctly check public edition`() {
        // 模拟公有云版
        whenever(editionUtil.getCurrentEdition()).thenReturn("public")
        assertFalse(editionUtil.isPersonal())
        assertFalse(editionUtil.isEnterprise())
        assertTrue(editionUtil.isPublic())
    }

    @Test
    @DisplayName("应该正确检查用户管理功能")
    fun `should correctly check user management feature`() {
        // 模拟个人版
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        assertFalse(editionUtil.isFeatureEnabled("user-management"))

        // 模拟企业版
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        assertTrue(editionUtil.isFeatureEnabled("user-management"))

        // 模拟公有云版
        whenever(editionUtil.getCurrentEdition()).thenReturn("public")
        assertTrue(editionUtil.isFeatureEnabled("user-management"))
    }

    @Test
    @DisplayName("应该正确检查手机号登录功能")
    fun `should correctly check phone login feature`() {
        // 模拟个人版
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        assertFalse(editionUtil.isFeatureEnabled("phone-login"))

        // 模拟企业版
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        assertTrue(editionUtil.isFeatureEnabled("phone-login"))

        // 模拟公有云版
        whenever(editionUtil.getCurrentEdition()).thenReturn("public")
        assertTrue(editionUtil.isFeatureEnabled("phone-login"))
    }

    @Test
    @DisplayName("应该正确检查多租户功能")
    fun `should correctly check multi tenant feature`() {
        // 模拟个人版
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        assertFalse(editionUtil.isFeatureEnabled("multi-tenant"))

        // 模拟企业版
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        assertFalse(editionUtil.isFeatureEnabled("multi-tenant"))

        // 模拟公有云版
        whenever(editionUtil.getCurrentEdition()).thenReturn("public")
        assertTrue(editionUtil.isFeatureEnabled("multi-tenant"))
    }

    @Test
    @DisplayName("应该正确检查计费功能")
    fun `should correctly check billing feature`() {
        // 模拟个人版
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        assertFalse(editionUtil.isFeatureEnabled("billing"))

        // 模拟企业版
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        assertFalse(editionUtil.isFeatureEnabled("billing"))

        // 模拟公有云版
        whenever(editionUtil.getCurrentEdition()).thenReturn("public")
        assertTrue(editionUtil.isFeatureEnabled("billing"))
    }

    @Test
    @DisplayName("未知功能应该返回 false")
    fun `should return false for unknown feature`() {
        // 模拟任何版本
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        assertFalse(editionUtil.isFeatureEnabled("unknown-feature"))
    }

    @Test
    @DisplayName("应该正确获取当前版本")
    fun `should correctly get current edition`() {
        // 模拟个人版
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        assertEquals("personal", editionUtil.getCurrentEdition())

        // 模拟企业版
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        assertEquals("enterprise", editionUtil.getCurrentEdition())

        // 模拟公有云版
        whenever(editionUtil.getCurrentEdition()).thenReturn("public")
        assertEquals("public", editionUtil.getCurrentEdition())
    }
}
