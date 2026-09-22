package com.agnetix.harnax.common.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * CliPackageLayout Unit Tests
 *
 * These two path rules are the whole anti-traversal story for a package, and they are shared between
 * admin (which registers a package) and agent-service (which extracts it and names an image after it),
 * so a rule that only holds for one spelling is a rule that disagrees with [CliPackageLayout.resolveInside]
 * later. Cases here are therefore about spellings, not about the shapes they resemble.
 */
class CliPackageLayoutTest {
    @Nested
    inner class RelativePaths {
        @Test
        fun `an ordinary relative path is accepted`() {
            assertNull(CliPackageLayout.checkRelativePath("usr/local/bin/demo"))
            assertNull(CliPackageLayout.checkRelativePath("plugin.yaml"))
            assertNull(CliPackageLayout.checkRelativePath("a/b/c/d.txt"))
        }

        @Test
        fun `a parent reference is refused wherever it sits`() {
            listOf("..", "../etc/passwd", "a/../b", "a/b/..", "/../a").forEach { path ->
                val reason = CliPackageLayout.checkRelativePath(path)
                assertNotNull(reason, path)
                assertTrue("escapes" in reason!! || "relative" in reason, "$path: $reason")
            }
        }

        /**
         * Not cosmetic: `a//b` and `a/./b` name a location that [CliPackageLayout.resolveInside]
         * resolves to the same file the archive hashed under a different string, which is how a check
         * at the door and the code writing the file end up disagreeing.
         */
        @Test
        fun `a path that is not the canonical spelling of itself is refused`() {
            assertEquals("path has an empty segment: a//b", CliPackageLayout.checkRelativePath("a//b"))
            assertEquals("path is not in canonical form: ./a/b", CliPackageLayout.checkRelativePath("./a/b"))
            assertEquals("path is not in canonical form: a/./b", CliPackageLayout.checkRelativePath("a/./b"))
            assertEquals("path has an empty segment: a/b/", CliPackageLayout.checkRelativePath("a/b/"))
        }

        @Test
        fun `an absolute path and an empty one are refused`() {
            assertEquals("path must be relative: /etc/passwd", CliPackageLayout.checkRelativePath("/etc/passwd"))
            assertEquals("empty path", CliPackageLayout.checkRelativePath(""))
            assertEquals("empty path", CliPackageLayout.checkRelativePath("   "))
        }

        /** A NUL byte truncates the path the moment anything hands it to a C API. */
        @Test
        fun `a NUL byte is refused`() {
            assertEquals("path contains a NUL byte", CliPackageLayout.checkRelativePath("etc/passwd\u0000.png"))
        }

        @Test
        fun `a colon is refused because it separates an alternate data stream and a drive`() {
            assertEquals("path contains a colon: a:b", CliPackageLayout.checkRelativePath("a:b"))
        }
    }

    @Nested
    inner class PayloadPaths {
        @Test
        fun `a denied directory is refused at any depth`() {
            listOf("etc/cron.d/evil", "root/.ssh/authorized_keys", "sbin/init", "usr/sbin/x", "var/run/docker.sock").forEach {
                val reason = CliPackageLayout.checkPayloadPath(it)
                assertNotNull(reason, it)
                assertTrue("denied target" in reason!!, "$it: $reason")
            }
        }

        /**
         * The deny list holds both directory prefixes (`etc/`) and file names (`usr/bin/su`). Matched as
         * text, `usr/bin/su` would also swallow `usr/bin/su-client` and `etc/` would swallow `etc2/x`,
         * which reads to whoever packed the file as an unexplained parser bug.
         */
        @Test
        fun `a neighbour of a denied target keeps its name`() {
            listOf("usr/bin/su-client", "usr/bin/docker-compose", "etc2/x", "rootfs/etc/passwd", "sbin2/x").forEach {
                assertNull(CliPackageLayout.checkPayloadPath(it), it)
            }
        }

        @Test
        fun `a denied destination reached by another spelling is refused before it lands`() {
            listOf("usr//bin/su", "usr/./bin/docker", "./etc/cron.d/evil").forEach {
                assertNotNull(CliPackageLayout.checkPayloadPath(it), it)
            }
        }

        @Test
        fun `the rules are checked in the order that names the first problem`() {
            // `payload/../../etc/shadow` is both an escape and a denied target; reporting the escape is
            // the useful half, and it is what the shared rule has to say about the spelling.
            assertTrue(CliPackageLayout.checkPayloadPath("../../etc/shadow")!!.contains("escapes"))
        }
    }

    @Nested
    inner class ResolveInside {
        @Test
        fun `a canonical path stays inside its root and a crafted one does not get there`() {
            val root = Files.createTempDirectory("cli-layout").toFile()
            try {
                val target = CliPackageLayout.resolveInside(root, "usr/local/bin/demo")
                assertTrue(target.toPath().startsWith(root.toPath().toAbsolutePath().normalize()))

                val error = assertThrows(IllegalArgumentException::class.java) {
                    CliPackageLayout.resolveInside(root, "../../etc/shadow")
                }
                assertTrue("escapes" in error.message.orEmpty(), error.message)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    /**
     * Every one of these alphabets ends up in a place where a stray character is an instruction: the
     * version reaches a generated Dockerfile, the apt list is interpolated into `apt-get install`, and
     * both digests become path and object-key components.
     */
    @Nested
    inner class Alphabets {
        @Test
        fun `names`() {
            listOf("harnax", "ab", "a1-b2", "x".repeat(64)).forEach {
                assertTrue(CliPackageLayout.NAME_PATTERN.matches(it), it)
            }
            listOf("a", "A-b", "-ab", "1ab", "a_b", "a b", "x".repeat(65), "").forEach {
                assertFalse(CliPackageLayout.NAME_PATTERN.matches(it), it)
            }
        }

        @Test
        fun `versions`() {
            listOf("1.0.0", "1.0.0-beta+build.1", "v1", "2026.09.22").forEach {
                assertTrue(CliPackageLayout.VERSION_PATTERN.matches(it), it)
            }
            // A newline here would be a second Dockerfile instruction; a leading dash an apt option.
            listOf("1.0\nRUN curl evil", "-1.0", ".1", "1 0", "", "x".repeat(33)).forEach {
                assertFalse(CliPackageLayout.VERSION_PATTERN.matches(it), it)
            }
        }

        @Test
        fun `apt packages`() {
            listOf("ca-certificates", "nginx", "python3.11", "libc6-dev", "nginx=1.24.0-1ubuntu").forEach {
                assertTrue(CliPackageLayout.APT_PACKAGE_PATTERN.matches(it), it)
            }
            listOf(
                "nginx; rm -rf /",
                "nginx \$(curl evil)",
                "-oAcquire::https::Verify=false",
                "nginx=1.24.0;echo",
                "NGINX",
                "nginx=",
            ).forEach {
                assertFalse(CliPackageLayout.APT_PACKAGE_PATTERN.matches(it), it)
            }
        }

        @Test
        fun `digests`() {
            assertTrue(CliPackageLayout.DIGEST_PATTERN.matches("a".repeat(64)))
            assertTrue(CliPackageLayout.DIGEST_PATTERN.matches(CliPackageLayout.sha256Hex("demo".toByteArray())))
            listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64), "A".repeat(64), "e".repeat(63) + "\n").forEach {
                assertFalse(CliPackageLayout.DIGEST_PATTERN.matches(it), it)
            }
        }
    }
}
