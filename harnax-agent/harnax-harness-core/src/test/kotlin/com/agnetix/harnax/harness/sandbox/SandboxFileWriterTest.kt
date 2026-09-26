package com.agnetix.harnax.harness.sandbox

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one channel into a container, and the one path guard every caller shares. Both are asserted
 * against [VirtualSandbox], which models the shell these commands rely on rather than echoing them back.
 */
class SandboxFileWriterTest {

    @Test
    fun `a payload bigger than one chunk arrives whole`() {
        val sandbox = VirtualSandbox()
        // 200,000 bytes base64 to 266,668 characters: five appends at 64 KiB each, then one decode.
        val content = "a".repeat(200_000)

        SandboxFileWriter.write(sandbox, "/workspace/big.txt", content.toByteArray())

        assertEquals(content, sandbox.files.getValue("/workspace/big.txt"))
        assertEquals(5, sandbox.commands.count { it.startsWith("printf") }, sandbox.commands.toString())
        // Exactly one command opens the target, and it is the last one.
        assertEquals(1, sandbox.commands.count { it.startsWith("base64 -d") })
    }

    @Test
    fun `an empty payload still creates the file`() {
        val sandbox = VirtualSandbox()

        SandboxFileWriter.write(sandbox, "/workspace/empty.txt", ByteArray(0))

        assertEquals("", sandbox.files.getValue("/workspace/empty.txt"))
        assertEquals(1, sandbox.commands.count { it.startsWith("printf") }, "the temp file needs one append to exist")
    }

    @Test
    fun `a decode that fails leaves no temp file behind`() {
        val sandbox = VirtualSandbox(refusesWhen = { it.startsWith("base64 -d") })

        val failure = assertThrows(IllegalStateException::class.java) {
            SandboxFileWriter.write(sandbox, "/workspace/x.txt", "data".toByteArray())
        }

        assertTrue(failure.message!!.contains("/workspace/x.txt"), failure.message)
        assertTrue(sandbox.pendingTemps.isEmpty(), sandbox.pendingTemps.toString())
        assertFalse(sandbox.files.containsKey("/workspace/x.txt"))
    }

    @Test
    fun `reading a file that is not there is null, not an exception`() {
        val sandbox = VirtualSandbox()

        assertNull(SandboxFileWriter.read(sandbox, "/workspace/missing.txt"))

        SandboxFileWriter.write(sandbox, "/workspace/there.txt", "content".toByteArray())
        assertArrayEquals("content".toByteArray(), SandboxFileWriter.read(sandbox, "/workspace/there.txt"))
    }

    @Test
    fun `removing a directory takes its tree and a plain file only itself`() {
        val sandbox = VirtualSandbox()
        SandboxFileWriter.write(sandbox, "/workspace/skills/a/SKILL.md", "a".toByteArray())
        SandboxFileWriter.write(sandbox, "/workspace/skills/a/notes.md", "n".toByteArray())
        SandboxFileWriter.write(sandbox, "/workspace/skills/b/SKILL.md", "b".toByteArray())

        SandboxFileWriter.delete(sandbox, "/workspace/skills/a", recursively = true)
        SandboxFileWriter.delete(sandbox, "/workspace/nothing-here")

        assertEquals(listOf("/workspace/skills/b/SKILL.md"), sandbox.pathsUnder("/workspace/skills"))
    }

    @Test
    fun `the path guard keeps a quoted argument literal and inside its directory`() {
        listOf("scripts/run.sh", "./notes.md", "a b/c.md", "/workspace/x.md").forEach { accepted ->
            assertEquals(accepted.removePrefix("./").removePrefix("/"), SandboxFileWriter.safeRelativePath(accepted))
        }
        listOf(
            "../escape.sh",
            "a/../b",
            "./",
            "",
            " '; rm -rf / #",
            "\$(reboot)",
            "back`tick",
            "new\nline",
        ).forEach { refused ->
            assertNull(SandboxFileWriter.safeRelativePath(refused), "$refused should not be trusted")
        }
    }
}
