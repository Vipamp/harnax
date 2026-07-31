package com.agnetix.harnax.admin.skill.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * SkillFileParser 单元测试
 * 覆盖 SKILL.md 描述提取（标题跳过、空内容、超长截断、frontmatter 边界）
 * 以及 resources 目录加载（不存在目录、嵌套文件、相对路径）
 *
 * 说明：主代码 extractDescription 并不解析 YAML frontmatter，
 * 其语义是：跳过以 # 开头的标题行，返回第一个非空行（最多 500 字符），
 * 无有效内容时回退为空字符串。本测试按主代码实际语义锁定行为。
 *
 * @author agnetix
 * @since 2026-06-28
 */
@DisplayName("SkillFileParser 技能文件解析测试")
class SkillFileParserTest {

    @Nested
    @DisplayName("extractDescription 描述提取测试")
    inner class ExtractDescriptionTests {

        @Test
        @DisplayName("extractDescription - 正常内容返回标题后第一个非空行")
        fun `extractDescription should return first non-empty line after headings`() {
            // Given
            val skillmd = """
                # My Skill

                This skill does something useful.

                More details here.
            """.trimIndent()

            // When
            val description = SkillFileParser.extractDescription(skillmd)

            // Then
            assertEquals("This skill does something useful.", description)
        }

        @Test
        @DisplayName("extractDescription - 多级标题行全部跳过")
        fun `extractDescription should skip all heading lines`() {
            // Given
            val skillmd = "# Title\n## Subtitle\n### Section\nActual description"

            // When
            val description = SkillFileParser.extractDescription(skillmd)

            // Then
            assertEquals("Actual description", description)
        }

        @Test
        @DisplayName("extractDescription - 无标题时直接返回第一个非空行")
        fun `extractDescription should return first line when no headings`() {
            // Given
            val skillmd = "\n\n  A plain description without frontmatter  \nsecond line"

            // When
            val description = SkillFileParser.extractDescription(skillmd)

            // Then - 结果应为 trim 后的内容
            assertEquals("A plain description without frontmatter", description)
        }

        @Test
        @DisplayName("extractDescription - 含 frontmatter 分隔符时返回首个非标题行（--- 行本身）")
        fun `extractDescription should treat frontmatter delimiter as plain content`() {
            // Given - 主代码不解析 YAML frontmatter，--- 是首个非空非标题行
            val skillmd = """
                ---
                name: my-skill
                description: from yaml
                ---
                Body description
            """.trimIndent()

            // When
            val description = SkillFileParser.extractDescription(skillmd)

            // Then - 锁定当前语义：返回 "---" 而不是 YAML 中的 description
            assertEquals("---", description)
        }

        @Test
        @DisplayName("extractDescription - 畸形/不完整的 YAML 内容不抛异常")
        fun `extractDescription should not throw for malformed yaml-like content`() {
            // Given
            val malformed = "key: [unclosed\n  - broken\n\t:::"

            // When & Then - 纯文本行提取，不做 YAML 解析，永不抛异常
            val description = SkillFileParser.extractDescription(malformed)
            assertEquals("key: [unclosed", description)
        }

        @Test
        @DisplayName("extractDescription - 空字符串回退为空描述")
        fun `extractDescription should return empty string for empty input`() {
            assertEquals("", SkillFileParser.extractDescription(""))
        }

        @Test
        @DisplayName("extractDescription - 仅空白字符回退为空描述")
        fun `extractDescription should return empty string for blank input`() {
            assertEquals("", SkillFileParser.extractDescription("   \n\t\n  \n"))
        }

        @Test
        @DisplayName("extractDescription - 只有标题没有正文时回退为空描述")
        fun `extractDescription should return empty string when only headings exist`() {
            // Given
            val skillmd = "# Title Only\n## Another heading\n"

            // When
            val description = SkillFileParser.extractDescription(skillmd)

            // Then - description 缺失时 fallback 为空字符串
            assertEquals("", description)
        }

        @Test
        @DisplayName("extractDescription - 超长描述截断为 500 字符")
        fun `extractDescription should truncate description to 500 chars`() {
            // Given
            val longLine = "x".repeat(800)

            // When
            val description = SkillFileParser.extractDescription("# Title\n$longLine")

            // Then
            assertEquals(500, description.length)
            assertEquals("x".repeat(500), description)
        }
    }

    @Nested
    @DisplayName("loadResources 资源加载测试")
    inner class LoadResourcesTests {

        @TempDir
        lateinit var tempDir: Path

        @Test
        @DisplayName("loadResources - 目录不存在时返回空 Map")
        fun `loadResources should return empty map when directory not exists`() {
            // Given
            val missing = tempDir.resolve("no-such-dir")

            // When
            val resources = SkillFileParser.loadResources(missing)

            // Then
            assertTrue(resources.isEmpty())
        }

        @Test
        @DisplayName("loadResources - 传入普通文件而非目录时返回空 Map")
        fun `loadResources should return empty map when path is a file`() {
            // Given
            val file = tempDir.resolve("file.txt")
            Files.writeString(file, "content")

            // When
            val resources = SkillFileParser.loadResources(file)

            // Then
            assertTrue(resources.isEmpty())
        }

        @Test
        @DisplayName("loadResources - 空目录返回空 Map")
        fun `loadResources should return empty map for empty directory`() {
            // Given
            val emptyDir = tempDir.resolve("resources")
            Files.createDirectories(emptyDir)

            // When
            val resources = SkillFileParser.loadResources(emptyDir)

            // Then
            assertTrue(resources.isEmpty())
        }

        @Test
        @DisplayName("loadResources - 读取目录下文件内容并以相对路径为键")
        fun `loadResources should read files keyed by relative path`() {
            // Given
            val resourcesDir = tempDir.resolve("resources")
            Files.createDirectories(resourcesDir)
            Files.writeString(resourcesDir.resolve("template.md"), "template content")
            val nested = resourcesDir.resolve("sub")
            Files.createDirectories(nested)
            Files.writeString(nested.resolve("config.json"), """{"key":"value"}""")

            // When
            val resources = SkillFileParser.loadResources(resourcesDir)

            // Then
            assertEquals(2, resources.size)
            assertEquals("template content", resources["template.md"])
            val nestedKey = "sub${java.io.File.separator}config.json"
            assertEquals("""{"key":"value"}""", resources[nestedKey])
        }
    }
}
