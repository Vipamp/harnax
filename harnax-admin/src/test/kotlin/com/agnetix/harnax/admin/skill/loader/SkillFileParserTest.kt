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
 * 覆盖 SKILL.md 元信息解析（YAML frontmatter、描述回退）、描述提取
 * （标题跳过、空内容、超长截断）以及 resources 目录加载（不存在目录、嵌套文件、相对路径）
 *
 * 说明：extractDescription 只负责正文，会先剥离 frontmatter 再跳过标题、分隔线、
 * 表格行与引用前缀，返回第一个有效行（最多 500 字符），无有效内容时回退为空字符串。
 * parseMeta 在此之上叠加 frontmatter 优先级，并保证 description 永不为空——
 * agentscope 的 AgentSkill.builder() 拒绝空描述，否则整个技能会被静默丢弃。
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
        @DisplayName("extractDescription - 剥离 frontmatter 后返回正文首个有效行")
        fun `extractDescription should strip frontmatter before reading the body`() {
            // Given - frontmatter 属于元信息，不应被当成正文描述
            val skillmd = """
                ---
                name: my-skill
                description: from yaml
                ---
                Body description
            """.trimIndent()

            // When
            val description = SkillFileParser.extractDescription(skillmd)

            // Then - 跳过 --- 块与 YAML 键值，取正文第一行
            assertEquals("Body description", description)
        }

        @Test
        @DisplayName("extractDescription - 跳过分隔线、表格行与引用前缀")
        fun `extractDescription should skip rules table rows and quote markers`() {
            // Given
            val skillmd = """
                # Title
                ---
                | col a | col b |
                > quoted description
            """.trimIndent()

            // When & Then
            assertEquals("quoted description", SkillFileParser.extractDescription(skillmd))
        }

        @Test
        @DisplayName("extractDescription - frontmatter 未闭合时按普通正文处理")
        fun `extractDescription should treat an unclosed frontmatter block as body`() {
            // Given - 只有起始 --- 没有结束 ---
            val skillmd = "---\nname: my-skill\n"

            // When & Then - 无法定位结束分隔符，整篇视为正文；--- 又被分隔线规则跳过
            assertEquals("name: my-skill", SkillFileParser.extractDescription(skillmd))
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
    @DisplayName("parseMeta 元信息解析测试")
    inner class ParseMetaTests {

        @Test
        @DisplayName("parseMeta - frontmatter 中的 name 与 description 优先生效")
        fun `parseMeta should prefer frontmatter fields`() {
            // Given
            val skillmd = """
                ---
                name: declared-name
                description: declared description
                ---
                # Heading

                Body line
            """.trimIndent()

            // When
            val meta = SkillFileParser.parseMeta(skillmd, "dir-name")

            // Then
            assertEquals("declared-name", meta.name)
            assertEquals("declared description", meta.description)
        }

        @Test
        @DisplayName("parseMeta - 无 frontmatter 时回退目录名与正文首行")
        fun `parseMeta should fall back to directory name and body line`() {
            // Given
            val skillmd = "# Title\n\nBody description\n"

            // When
            val meta = SkillFileParser.parseMeta(skillmd, "dir-name")

            // Then
            assertEquals("dir-name", meta.name)
            assertEquals("Body description", meta.description)
        }

        @Test
        @DisplayName("parseMeta - 只有标题时 description 回退为技能名，保证不为空")
        fun `parseMeta should never return a blank description`() {
            // Given - agentscope 拒绝空 description，此前会导致整个技能被静默丢弃
            val skillmd = "# Heading Only\n"

            // When
            val meta = SkillFileParser.parseMeta(skillmd, "heading-skill")

            // Then
            assertEquals("heading-skill", meta.name)
            assertEquals("heading-skill", meta.description)
        }

        @Test
        @DisplayName("parseMeta - frontmatter 的 description 为空串时回退正文")
        fun `parseMeta should ignore a blank frontmatter description`() {
            // Given
            val skillmd = "---\nname: my-skill\ndescription:\n---\nBody description\n"

            // When
            val meta = SkillFileParser.parseMeta(skillmd, "dir-name")

            // Then
            assertEquals("my-skill", meta.name)
            assertEquals("Body description", meta.description)
        }

        @Test
        @DisplayName("parseMeta - 支持块标量与带引号的取值")
        fun `parseMeta should read block scalars and quoted values`() {
            // Given
            val skillmd = """
                ---
                name: "quoted-name"
                description: |
                  first line
                  second line
                ---
                Body
            """.trimIndent()

            // When
            val meta = SkillFileParser.parseMeta(skillmd, "dir-name")

            // Then
            assertEquals("quoted-name", meta.name)
            assertEquals("first line second line", meta.description)
        }
    }

    @Nested
    @DisplayName("stripFrontmatter 正文剥离测试")
    inner class StripFrontmatterTests {

        @Test
        @DisplayName("stripFrontmatter - 移除起始 --- 块并保留正文")
        fun `stripFrontmatter should remove the leading block`() {
            // Given
            val skillmd = "---\nname: my-skill\n---\nBody\n"

            // When & Then
            assertEquals("Body\n", SkillFileParser.stripFrontmatter(skillmd))
        }

        @Test
        @DisplayName("stripFrontmatter - 无 frontmatter 时原样返回")
        fun `stripFrontmatter should return input unchanged without frontmatter`() {
            // Given
            val skillmd = "# Title\nBody\n"

            // When & Then
            assertEquals(skillmd, SkillFileParser.stripFrontmatter(skillmd))
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
            // 键统一使用 / 分隔，与 agentscope 的资源路径保持一致
            assertEquals("""{"key":"value"}""", resources["sub/config.json"])
        }
    }
}
