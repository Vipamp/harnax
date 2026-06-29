package com.agnetix.harnax.admin.skill.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Unit tests for LocalFileContentStore.
 */
class LocalFileContentStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: LocalFileContentStore

    @BeforeEach
    fun setUp() {
        store = LocalFileContentStore(tempDir.toString())
    }

    @Nested
    @DisplayName("Save Tests")
    inner class SaveTests {

        @Test
        fun `save should create directory structure and write SKILL md`() {
            val content = SkillContent(
                skillmd = "# Test Skill\n\nDescription here.",
                resources = emptyMap(),
            )

            val path = store.save(1L, "my-skill", content)

            assertEquals("1/my-skill", path)
            assertTrue(store.exists(path))
        }

        @Test
        fun `save should write resource files`() {
            val content = SkillContent(
                skillmd = "# Skill with Resources",
                resources = mapOf(
                    "config.yaml" to "key: value".toByteArray(),
                    "scripts/run.sh" to "#!/bin/bash\necho hello".toByteArray(),
                ),
            )

            val path = store.save(2L, "resource-skill", content)

            assertTrue(store.exists(path))
            val loaded = store.load(path)
            assertEquals(2, loaded.resources.size)
            assertTrue(loaded.resources.containsKey("config.yaml"))
            assertTrue(loaded.resources.containsKey("scripts/run.sh"))
        }

        @Test
        fun `save should overwrite existing content`() {
            val original = SkillContent(skillmd = "# Original", resources = emptyMap())
            val path = store.save(1L, "my-skill", original)

            val updated = SkillContent(skillmd = "# Updated", resources = emptyMap())
            store.save(1L, "my-skill", updated)

            val loaded = store.load(path)
            assertEquals("# Updated", loaded.skillmd)
        }

        @Test
        fun `save should handle unicode content`() {
            val content = SkillContent(
                skillmd = "# 测试技能\n\n这是一个测试技能的描述。",
                resources = mapOf("readme.txt" to "中文内容".toByteArray()),
            )

            val path = store.save(1L, "unicode-skill", content)
            val loaded = store.load(path)

            assertEquals("# 测试技能\n\n这是一个测试技能的描述。", loaded.skillmd)
            assertArrayEquals("中文内容".toByteArray(), loaded.resources["readme.txt"])
        }
    }

    @Nested
    @DisplayName("Load Tests")
    inner class LoadTests {

        @Test
        fun `load should return saved content`() {
            val content = SkillContent(
                skillmd = "# My Skill\n\nFull description.",
                resources = mapOf("data.json" to """{"key":"val"}""".toByteArray()),
            )
            val path = store.save(1L, "load-test", content)

            val loaded = store.load(path)

            assertEquals("# My Skill\n\nFull description.", loaded.skillmd)
            assertEquals(1, loaded.resources.size)
            assertArrayEquals("""{"key":"val"}""".toByteArray(), loaded.resources["data.json"])
        }

        @Test
        fun `load should throw when path does not exist`() {
            val exception = assertThrows<RuntimeException> {
                store.load("999/nonexistent")
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `load should return empty resources when no resources directory`() {
            val content = SkillContent(skillmd = "# No Resources", resources = emptyMap())
            val path = store.save(1L, "no-resources", content)

            val loaded = store.load(path)

            assertEquals("# No Resources", loaded.skillmd)
            assertTrue(loaded.resources.isEmpty())
        }
    }

    @Nested
    @DisplayName("Delete Tests")
    inner class DeleteTests {

        @Test
        fun `delete should remove stored content`() {
            val content = SkillContent(skillmd = "# To Delete", resources = emptyMap())
            val path = store.save(1L, "delete-me", content)
            assertTrue(store.exists(path))

            store.delete(path)

            assertFalse(store.exists(path))
        }

        @Test
        fun `delete should not throw when path does not exist`() {
            assertDoesNotThrow {
                store.delete("999/nonexistent")
            }
        }

        @Test
        fun `delete should remove all resource files`() {
            val content = SkillContent(
                skillmd = "# With Resources",
                resources = mapOf(
                    "a.txt" to "aaa".toByteArray(),
                    "b.txt" to "bbb".toByteArray(),
                ),
            )
            val path = store.save(1L, "delete-resources", content)
            assertTrue(store.exists(path))

            store.delete(path)

            assertFalse(store.exists(path))
        }
    }

    @Nested
    @DisplayName("Exists Tests")
    inner class ExistsTests {

        @Test
        fun `exists should return true for saved content`() {
            val content = SkillContent(skillmd = "# Exists", resources = emptyMap())
            val path = store.save(1L, "exists-test", content)

            assertTrue(store.exists(path))
        }

        @Test
        fun `exists should return false for nonexistent path`() {
            assertFalse(store.exists("999/nonexistent"))
        }

        @Test
        fun `exists should return false after delete`() {
            val content = SkillContent(skillmd = "# Temp", resources = emptyMap())
            val path = store.save(1L, "temp", content)
            store.delete(path)

            assertFalse(store.exists(path))
        }

        @Test
        fun `exists should return false when directory exists but SKILL md is missing`() {
            // Create directory manually without SKILL.md
            val dir = tempDir.resolve("1").resolve("no-skillmd")
            java.nio.file.Files.createDirectories(dir)

            assertFalse(store.exists("1/no-skillmd"))
        }
    }

    @Nested
    @DisplayName("Boundary Tests")
    inner class BoundaryTests {

        @Test
        fun `save should handle skill name with special characters`() {
            val content = SkillContent(
                skillmd = "# Special Name",
                resources = emptyMap(),
            )

            val path = store.save(1L, "my-skill_v2.0", content)

            assertTrue(store.exists(path))
            assertEquals("1/my-skill_v2.0", path)
        }

        @Test
        fun `save should handle binary resource files`() {
            val binaryData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val content = SkillContent(
                skillmd = "# Binary Skill",
                resources = mapOf("image.png" to binaryData),
            )

            val path = store.save(1L, "binary-skill", content)
            val loaded = store.load(path)

            assertArrayEquals(binaryData, loaded.resources["image.png"])
        }

        @Test
        fun `save should handle deeply nested resource paths`() {
            val content = SkillContent(
                skillmd = "# Deep Skill",
                resources = mapOf(
                    "level1/level2/level3/level4/deep.txt" to "deep content".toByteArray(),
                ),
            )

            val path = store.save(1L, "deep-skill", content)
            val loaded = store.load(path)

            assertTrue(loaded.resources.containsKey("level1/level2/level3/level4/deep.txt"))
            assertEquals("deep content", String(loaded.resources["level1/level2/level3/level4/deep.txt"]!!))
        }

        @Test
        fun `save should handle empty skillmd with resources`() {
            val content = SkillContent(
                skillmd = "",
                resources = mapOf("data.json" to """{"key":"value"}""".toByteArray()),
            )

            val path = store.save(1L, "empty-md-skill", content)
            val loaded = store.load(path)

            assertEquals("", loaded.skillmd)
            assertEquals(1, loaded.resources.size)
        }

        @Test
        fun `save should handle very large skillmd content`() {
            val largeContent = buildString {
                repeat(5000) { i ->
                    append("Line $i: This is a test line to make the content large.\n")
                }
            }
            val content = SkillContent(
                skillmd = largeContent,
                resources = emptyMap(),
            )

            val path = store.save(1L, "large-skill", content)
            val loaded = store.load(path)

            assertEquals(largeContent, loaded.skillmd)
            assertTrue(loaded.skillmd.length > 200000)
        }

        @Test
        fun `save should handle many resource files`() {
            val resources = (1..100).associate { i ->
                "file$i.txt" to "content $i".toByteArray()
            }
            val content = SkillContent(
                skillmd = "# Many Resources",
                resources = resources,
            )

            val path = store.save(1L, "many-resources", content)
            val loaded = store.load(path)

            assertEquals(100, loaded.resources.size)
            assertTrue(loaded.resources.containsKey("file1.txt"))
            assertTrue(loaded.resources.containsKey("file100.txt"))
        }

        @Test
        fun `save should isolate different repositories`() {
            val content1 = SkillContent(skillmd = "# Repo 1", resources = emptyMap())
            val content2 = SkillContent(skillmd = "# Repo 2", resources = emptyMap())

            val path1 = store.save(1L, "skill", content1)
            val path2 = store.save(2L, "skill", content2)

            val loaded1 = store.load(path1)
            val loaded2 = store.load(path2)

            assertEquals("# Repo 1", loaded1.skillmd)
            assertEquals("# Repo 2", loaded2.skillmd)
        }

        @Test
        fun `delete should only delete specified skill`() {
            val content1 = SkillContent(skillmd = "# Skill 1", resources = emptyMap())
            val content2 = SkillContent(skillmd = "# Skill 2", resources = emptyMap())

            val path1 = store.save(1L, "skill1", content1)
            val path2 = store.save(1L, "skill2", content2)

            store.delete(path1)

            assertFalse(store.exists(path1))
            assertTrue(store.exists(path2))
        }

        @Test
        fun `save and load should preserve special characters in resources`() {
            val content = SkillContent(
                skillmd = "# Special Chars",
                resources = mapOf(
                    "unicode.txt" to "中文 日本語 한국어 العربية".toByteArray(),
                    "symbols.txt" to "!@#$%^&*()_+-=[]{}|;':\",./<>?".toByteArray(),
                ),
            )

            val path = store.save(1L, "special-chars", content)
            val loaded = store.load(path)

            assertEquals("中文 日本語 한국어 العربية", String(loaded.resources["unicode.txt"]!!))
            assertEquals("!@#$%^&*()_+-=[]{}|;':\",./<>?", String(loaded.resources["symbols.txt"]!!))
        }
    }
}
