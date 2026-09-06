package com.agnetix.harnax.admin.dto

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.junit.jupiter.api.*
import kotlin.test.*

/**
 * Skill 相关请求体的 Bean Validation 测试
 *
 * 这些 DTO 上的长度限制对应的是 MySQL 的列宽：`skill_repository.name` 和 `skill.name` 是
 * varchar(100)，`skill_repository.url` 是 varchar(500)，`branch` 和 `version` 是 varchar(100)。
 * 注解一旦没生效，超限的值就会一路走到 MySQL，回一句「Data too long for column」——既不说是哪个
 * 字段，也不说是哪条配置错了。
 *
 * 而在 Kotlin data class 上，裸写的 `@Size` 会落到构造参数（param）目标，Bean Validation 只读
 * 字段和 getter，等于这条限制只存在于 OpenAPI 文档里。所以这里除了验证限制本身，也顺带钉住
 * `@field:` 这个写法。
 */
class SkillRequestValidationTest {

    private lateinit var validator: Validator
    private lateinit var factory: ValidatorFactory

    @BeforeEach
    fun setUp() {
        factory = Validation.buildDefaultValidatorFactory()
        validator = factory.validator
    }

    @AfterEach
    fun tearDown() {
        factory.close()
    }

    /** 取出某个字段上的校验错误，没有则返回 null */
    private fun violationsOn(request: Any, field: String) = validator
        .validate(request)
        .filter { it.propertyPath.toString() == field }

    @Nested
    @DisplayName("SkillRepositoryCreateRequest 验证测试")
    inner class RepositoryCreateValidationTests {

        @Test
        fun `验证通过 - 常规 Git 仓库`() {
            val request = SkillRepositoryCreateRequest(
                name = "team-skills",
                url = "https://github.com/example/skills",
                branch = "main",
            )

            assertTrue(validator.validate(request).isEmpty(), "常规取值应该验证通过")
        }

        @Test
        fun `验证失败 - 仓库名超过 100 字符`() {
            val request = SkillRepositoryCreateRequest(
                name = "a".repeat(101),
                url = "https://github.com/example/skills",
            )

            val violations = violationsOn(request, "name")
            assertTrue(violations.isNotEmpty(), "仓库名超过 varchar(100) 时应该验证失败")
            assertEquals("Repository name length must be between 1-100", violations.first().message)
        }

        @Test
        fun `验证失败 - URL 超过 500 字符`() {
            val request = SkillRepositoryCreateRequest(
                name = "team-skills",
                url = "https://github.com/" + "p".repeat(500),
            )

            val violations = violationsOn(request, "url")
            assertTrue(violations.isNotEmpty(), "URL 超过 varchar(500) 时应该验证失败")
            assertEquals("Repository URL length cannot exceed 500", violations.first().message)
        }

        @Test
        fun `验证通过 - URL 刚好 500 字符`() {
            val request = SkillRepositoryCreateRequest(
                name = "team-skills",
                url = "https://github.com/" + "p".repeat(481),
            )

            assertTrue(violationsOn(request, "url").isEmpty(), "刚好贴着列宽应该验证通过")
        }

        @Test
        fun `验证失败 - 分支名超过 100 字符`() {
            val request = SkillRepositoryCreateRequest(
                name = "team-skills",
                url = "https://github.com/example/skills",
                branch = "b".repeat(101),
            )

            assertTrue(violationsOn(request, "branch").isNotEmpty(), "分支名超过 varchar(100) 时应该验证失败")
        }
    }

    @Nested
    @DisplayName("SkillRepositoryUpdateRequest 验证测试")
    inner class RepositoryUpdateValidationTests {

        @Test
        fun `验证通过 - 只改描述`() {
            // 局部更新时其余字段是 null，@Size 对 null 不做判断，不能因为补了限制就把局部更新挡掉
            val request = SkillRepositoryUpdateRequest(description = "new description")

            assertTrue(validator.validate(request).isEmpty(), "只带描述的局部更新应该验证通过")
        }

        @Test
        fun `验证失败 - 改名超过 100 字符`() {
            val request = SkillRepositoryUpdateRequest(name = "a".repeat(101))

            assertTrue(violationsOn(request, "name").isNotEmpty(), "改名超过 varchar(100) 时应该验证失败")
        }

        @Test
        fun `验证失败 - 换的 URL 超过 500 字符`() {
            val request = SkillRepositoryUpdateRequest(url = "https://github.com/" + "p".repeat(500))

            assertTrue(violationsOn(request, "url").isNotEmpty(), "URL 超过 varchar(500) 时应该验证失败")
        }
    }

    @Nested
    @DisplayName("SkillSourceCreateRequest 验证测试")
    inner class SourceCreateValidationTests {

        @Test
        fun `验证通过 - 常规 Git 源`() {
            val request = SkillSourceCreateRequest(
                name = "team-skills",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/example/skills"),
            )

            assertTrue(validator.validate(request).isEmpty(), "常规取值应该验证通过")
        }

        @Test
        fun `验证失败 - 源名超过 100 字符`() {
            val request = SkillSourceCreateRequest(name = "a".repeat(101))

            assertTrue(violationsOn(request, "name").isNotEmpty(), "源名超过 varchar(100) 时应该验证失败")
        }

        @Test
        fun `验证失败 - 版本号超过 100 字符`() {
            // 版本号会同时写进 skill_repository.version 和每条 skill.version，两列都是 varchar(100)
            val request = SkillSourceCreateRequest(name = "team-skills", version = "v".repeat(101))

            assertTrue(violationsOn(request, "version").isNotEmpty(), "版本号超过 varchar(100) 时应该验证失败")
        }
    }

    @Nested
    @DisplayName("SkillSourceUpdateRequest 验证测试")
    inner class SourceUpdateValidationTests {

        @Test
        fun `验证失败 - 改名超过 100 字符`() {
            val request = SkillSourceUpdateRequest(name = "a".repeat(101))

            assertTrue(violationsOn(request, "name").isNotEmpty(), "改名超过 varchar(100) 时应该验证失败")
        }

        @Test
        fun `验证失败 - 版本号超过 100 字符`() {
            val request = SkillSourceUpdateRequest(version = "v".repeat(101))

            assertTrue(violationsOn(request, "version").isNotEmpty(), "版本号超过 varchar(100) 时应该验证失败")
        }
    }

    @Nested
    @DisplayName("Skill 请求体验证测试")
    inner class SkillValidationTests {

        @Test
        fun `验证失败 - 新建技能名超过 100 字符`() {
            val request = SkillCreateRequest(name = "a".repeat(101), repositoryId = 1L)

            assertTrue(violationsOn(request, "name").isNotEmpty(), "技能名超过 varchar(100) 时应该验证失败")
        }

        @Test
        fun `验证失败 - 改名超过 100 字符`() {
            val request = SkillUpdateRequest(name = "a".repeat(101))

            assertTrue(violationsOn(request, "name").isNotEmpty(), "改名超过 varchar(100) 时应该验证失败")
        }

        @Test
        fun `验证通过 - 只改技能正文`() {
            val request = SkillUpdateRequest(skillmd = "# Updated")

            assertTrue(validator.validate(request).isEmpty(), "只带内容的局部更新应该验证通过")
        }
    }
}
