package com.agnetix.harnax.admin.skill.loader

import com.agnetix.harnax.admin.exception.BizException
import io.agentscope.core.skill.AgentSkill
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for SkillLoader implementations and SkillLoaderRegistry.
 */
class SkillLoaderTest {

    // ==================== SkillLoaderRegistry ====================

    @Nested
    @DisplayName("SkillLoaderRegistry Tests")
    inner class RegistryTests {

        private lateinit var registry: SkillLoaderRegistry

        @BeforeEach
        fun setUp() {
            val gitLoader = GitSkillLoader()
            val npmLoader = NpmSkillLoader()
            val zipLoader = ZipSkillLoader()
            registry = SkillLoaderRegistry(listOf(gitLoader, npmLoader, zipLoader))
        }

        @ParameterizedTest
        @ValueSource(strings = ["GIT", "NPM", "ZIP"])
        fun `getLoader should return correct loader by sourceType`(sourceType: String) {
            val loader = registry.getLoader(sourceType)
            assertEquals(sourceType, loader.sourceType)
        }

        @Test
        fun `getLoader should throw BizException for unsupported type`() {
            val exception = assertThrows<BizException> {
                registry.getLoader("UNKNOWN")
            }
            assertTrue(exception.message!!.contains("Unsupported"))
        }
    }

    // ==================== ZipSkillLoader ====================

    @Nested
    @DisplayName("ZipSkillLoader Tests")
    inner class ZipLoaderTests {

        private lateinit var zipLoader: ZipSkillLoader

        @BeforeEach
        fun setUp() {
            zipLoader = ZipSkillLoader()
        }

        @Test
        fun `validateConfig should pass with valid zipPath`() {
            assertDoesNotThrow {
                zipLoader.validateConfig(mapOf("zipPath" to "/tmp/test.zip"))
            }
        }

        @Test
        fun `validateConfig should throw when zipPath is blank`() {
            assertThrows<IllegalArgumentException> {
                zipLoader.validateConfig(mapOf("zipPath" to ""))
            }
        }

        @Test
        fun `validateConfig should throw when zipPath is missing`() {
            assertThrows<IllegalArgumentException> {
                zipLoader.validateConfig(emptyMap())
            }
        }

        @Test
        fun `loadSkills should throw when zipPath file not found`(
            @TempDir tmpDir: Path,
        ) {
            val config = mapOf<String, Any>("zipPath" to "/nonexistent/file.zip")
            assertThrows<IllegalArgumentException> {
                zipLoader.loadSkills(config, tmpDir)
            }
        }

        @Test
        fun `loadSkills should parse skills from valid zip`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createTestSkillZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isNotEmpty())
            assertEquals("test-skill", skills[0].name)
            assertTrue(skills[0].skillContent.contains("# Test Skill"))
        }

        @Test
        fun `loadSkills should parse multiple skills from zip`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createMultiSkillZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertEquals(2, skills.size)
            assertTrue(skills.any { it.name == "skill-a" })
            assertTrue(skills.any { it.name == "skill-b" })
        }

        @Test
        fun `loadSkills should handle zip with resources`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createSkillZipWithResources(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isNotEmpty())
            assertTrue(skills[0].resources.isNotEmpty())
            assertTrue(skills[0].resources.containsKey("config.yaml"))
        }

        @Test
        fun `loadSkills should reject zip with path traversal`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createMaliciousZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            assertThrows<SecurityException> {
                zipLoader.loadSkills(config, tmpDir)
            }
        }

        @Test
        fun `loadSkills should return empty list for zip without SKILL md`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createEmptyZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isEmpty())
        }

        @Test
        fun `loadSkills should parse top-level SKILL md without subdirectory`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createTopLevelSkillZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isNotEmpty())
            assertTrue(skills[0].skillContent.contains("# Top Level Skill"))
        }

        @Test
        fun `loadSkills should handle nested resource directories`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createNestedResourcesZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isNotEmpty())
            assertTrue(skills[0].resources.containsKey("scripts/run.sh"))
            assertTrue(skills[0].resources.containsKey("config/settings.yaml"))
        }

        @Test
        fun `loadSkills should handle empty SKILL md content`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createEmptySkillMdZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isNotEmpty())
            assertEquals("", skills[0].skillContent)
        }

        @Test
        fun `loadSkills should handle binary resource files`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createBinaryResourceZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isNotEmpty())
            assertTrue(skills[0].resources.containsKey("image.png"))
            // Binary content should be preserved as string
            assertNotNull(skills[0].resources["image.png"])
        }

        @Test
        fun `loadSkills should reject zip with absolute path entries`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createAbsolutePathZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            assertThrows<SecurityException> {
                zipLoader.loadSkills(config, tmpDir)
            }
        }

        @Test
        fun `loadSkills should handle skill name with special characters`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createSpecialCharNameZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isNotEmpty())
            assertEquals("my-skill_v2.0", skills[0].name)
        }

        @Test
        fun `loadSkills should handle very large SKILL md content`(
            @TempDir tmpDir: Path,
        ) {
            val zipFile = createLargeSkillMdZip(tmpDir)
            val config = mapOf<String, Any>("zipPath" to zipFile.toString())
            val skills = zipLoader.loadSkills(config, tmpDir)

            assertTrue(skills.isNotEmpty())
            assertTrue(skills[0].skillContent.length > 100000)
        }

        @Test
        fun `loadSkills should throw when zip file is not a valid zip`(
            @TempDir tmpDir: Path,
        ) {
            val notZipFile = tmpDir.resolve("not-a-zip.zip")
            Files.writeString(notZipFile, "This is not a ZIP file")
            val config = mapOf<String, Any>("zipPath" to notZipFile.toString())
            assertThrows<Exception> {
                zipLoader.loadSkills(config, tmpDir)
            }
        }
    }

    // ==================== NpmSkillLoader ====================

    @Nested
    @DisplayName("NpmSkillLoader Tests")
    inner class NpmLoaderTests {

        private lateinit var npmLoader: NpmSkillLoader

        @BeforeEach
        fun setUp() {
            npmLoader = NpmSkillLoader()
        }

        @Test
        fun `validateConfig should pass with valid packageName`() {
            assertDoesNotThrow {
                npmLoader.validateConfig(mapOf("packageName" to "@harnax/skill-pack"))
            }
        }

        @Test
        fun `validateConfig should throw when packageName is blank`() {
            assertThrows<IllegalArgumentException> {
                npmLoader.validateConfig(mapOf("packageName" to ""))
            }
        }

        @Test
        fun `validateConfig should throw when packageName is missing`() {
            assertThrows<IllegalArgumentException> {
                npmLoader.validateConfig(emptyMap())
            }
        }

        @Test
        fun `validateConfig should accept config with registry`() {
            assertDoesNotThrow {
                npmLoader.validateConfig(
                    mapOf(
                        "packageName" to "@harnax/skill-pack",
                        "registry" to "https://registry.npmmirror.com",
                    ),
                )
            }
        }
    }

    // ==================== GitSkillLoader ====================

    @Nested
    @DisplayName("GitSkillLoader Tests")
    inner class GitLoaderTests {

        private lateinit var gitLoader: GitSkillLoader

        @BeforeEach
        fun setUp() {
            gitLoader = GitSkillLoader()
        }

        @Test
        fun `validateConfig should pass with valid url`() {
            assertDoesNotThrow {
                gitLoader.validateConfig(mapOf("url" to "https://github.com/example/skills"))
            }
        }

        @Test
        fun `validateConfig should throw when url is blank`() {
            assertThrows<IllegalArgumentException> {
                gitLoader.validateConfig(mapOf("url" to ""))
            }
        }

        @Test
        fun `validateConfig should throw when url is missing`() {
            assertThrows<IllegalArgumentException> {
                gitLoader.validateConfig(emptyMap())
            }
        }

        @Test
        fun `sourceType should be GIT`() {
            assertEquals("GIT", gitLoader.sourceType)
        }
    }

    // ==================== Helper: Create test ZIP files ====================

    private fun createTestSkillZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("test-skill.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("test-skill/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("test-skill/SKILL.md"))
            zos.write("# Test Skill\n\nThis is a test skill.".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createMultiSkillZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("multi-skill.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("skill-a/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("skill-a/SKILL.md"))
            zos.write("# Skill A\n\nSkill A description.".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("skill-b/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("skill-b/SKILL.md"))
            zos.write("# Skill B\n\nSkill B description.".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createSkillZipWithResources(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("skill-with-resources.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("test-skill/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("test-skill/SKILL.md"))
            zos.write("# Test Skill\n\nA skill with resources.".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("test-skill/resources/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("test-skill/resources/config.yaml"))
            zos.write("key: value".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createMaliciousZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("malicious.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../../etc/passwd"))
            zos.write("malicious content".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createEmptyZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("empty.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("some-dir/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("some-dir/readme.txt"))
            zos.write("No skills here.".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createTopLevelSkillZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("top-level.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("SKILL.md"))
            zos.write("# Top Level Skill\n\nNo subdirectory.".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createNestedResourcesZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("nested-resources.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("nested-skill/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("nested-skill/SKILL.md"))
            zos.write("# Nested Skill".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("nested-skill/resources/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("nested-skill/resources/scripts/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("nested-skill/resources/scripts/run.sh"))
            zos.write("#!/bin/bash\necho run".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("nested-skill/resources/config/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("nested-skill/resources/config/settings.yaml"))
            zos.write("key: value".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createEmptySkillMdZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("empty-skillmd.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("empty-skill/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("empty-skill/SKILL.md"))
            zos.write("".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createBinaryResourceZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("binary-resource.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("binary-skill/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("binary-skill/SKILL.md"))
            zos.write("# Binary Skill".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("binary-skill/resources/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("binary-skill/resources/image.png"))
            // Simulate binary PNG header
            zos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createAbsolutePathZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("absolute-path.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("/etc/passwd"))
            zos.write("malicious".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createSpecialCharNameZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("special-name.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("my-skill_v2.0/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("my-skill_v2.0/SKILL.md"))
            zos.write("# Special Name Skill".toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }

    private fun createLargeSkillMdZip(tmpDir: Path): Path {
        val zipPath = tmpDir.resolve("large-skill.zip")
        val largeContent = StringBuilder("# Large Skill\n\n")
        repeat(2000) { i ->
            largeContent.append("This is line $i with some padding text to make it longer.\n")
        }
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("large-skill/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("large-skill/SKILL.md"))
            zos.write(largeContent.toString().toByteArray())
            zos.closeEntry()
        }
        return zipPath
    }
}
