package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.admin.skill.SkillContentScanner
import com.agnetix.harnax.common.cli.CliPackageLayout
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.attribute.FileTime

/**
 * The parser carries the whole safety story of the package model (spec §3.3), so this suite is the
 * acceptance list of §7 rather than a smoke test.
 *
 * Archives are written by commons-compress instead of hand-rolled bytes on purpose: the platform reads
 * unix modes with its own walk of the zip central directory, and an archive produced by a *different*
 * implementation is the only fixture that would notice a miscounted header field.
 */
class CliPackageParserTest {
    @TempDir
    lateinit var dir: File

    // ==================== happy path ====================

    @Test
    fun `a well formed package registers with its manifest, skill and payload intact`() {
        val parsed = CliPackageParser.parse(packageFile())

        assertEquals("demo", parsed.manifest.name)
        assertEquals("1.4.0", parsed.manifest.version)
        assertEquals("Demo command line", parsed.manifest.description)
        assertEquals("demo --version", parsed.manifest.checkCommand)
        assertEquals(listOf("ca-certificates"), parsed.manifest.depsApt)
        assertEquals("http://admin:8080", parsed.manifest.runtimeEnv["DEMO_URL"])
        assertEquals("\${platform.internalToken}", parsed.manifest.runtimeEnv["DEMO_TOKEN"])
        assertEquals(1, parsed.manifest.envParams.size)
        with(parsed.manifest.envParams.first()) {
            assertEquals("DEMO_PROFILE", envParamName)
            assertEquals("profile", description)
            assertFalse(secret)
            assertFalse(required)
        }

        assertEquals("Demo command line skill", parsed.skillDescription)
        assertEquals(mapOf("cheatsheet.md" to "# Cheatsheet\n"), parsed.skillAssets)

        val binary = parsed.payloadFiles.single()
        assertEquals("payload/usr/local/bin/demo", binary.archivePath)
        assertTrue(binary.isExecutable)
        // The mode had to survive the central-directory walk: packed 0755, read back 0755.
        assertEquals(S_IFREG or 0b111_101_101, binary.mode)

        assertTrue(parsed.packageDigest.matches(HEX64), parsed.packageDigest)
        assertTrue(parsed.payloadDigest.matches(HEX64), parsed.payloadDigest)
    }

    @Test
    fun `directory entries in the tree are accepted`() {
        val parsed = CliPackageParser.parse(
            packageFile(entries = dirEntries() + manifestEntries() + skillEntries() + payloadEntries()),
        )
        assertEquals(1, parsed.payloadFiles.size)
        assertEquals(0, parsed.skillAssets.size)
    }

    // ==================== manifest rules ====================

    @Nested
    inner class ManifestRules {
        @Test
        fun `a package with no manifest is refused`() {
            assertRefused(packageFile(entries = payloadEntries() + skillEntries()), "plugin.yaml")
        }

        @Test
        fun `every missing required field is reported in one pass`() {
            val reason = refuse(
                packageFile(
                    manifest = """
                        name: demo
                        version: 1.4.0
                    """.trimIndent(),
                ),
            )
            listOf("description", "checkCommand").forEach { field ->
                assertTrue("$field is required" in reason, "$field missing from:\n$reason")
            }
        }

        @Test
        fun `a name that is not a file-name-safe slug is refused`() {
            assertRefused(packageFile(manifest = manifest(name = "Demo CLI")), "must match")
        }

        @Test
        fun `an unrecognised manifest key is refused instead of silently dropped`() {
            assertRefused(packageFile(manifest = VALID_MANIFEST + "checkcommand: demo\n"), "unknown plugin.yaml key")
        }

        @Test
        fun `a duplicated key is refused`() {
            assertRefused(packageFile(manifest = VALID_MANIFEST + "version: 2.0.0\n"), "duplicate")
        }

        @Test
        fun `a manifest over the size cap is refused`() {
            assertRefused(packageFile(manifest = VALID_MANIFEST + "#".repeat(70 * 1024)), "is over the 65536 limit")
        }

        @Test
        fun `the file name stem must agree with the declared name`() {
            assertRefused(packageFile(fileName = "other-tool-1.4.0.harnaxcli.zip"), "the declared name is the identity")
        }

        @Test
        fun `a file that is not named like a package is refused`() {
            assertRefused(packageFile(fileName = "demo-1.4.0.zip"), CliPackageLayout.FILE_SUFFIX)
        }

        @Test
        fun `apt entries are limited to characters the shell cannot reinterpret`() {
            listOf("ca-certificates; rm -rf /", "--option", "ca certificates", "\$(curl evil)", "CA-Cert").forEach { pkg ->
                assertRefused(
                    packageFile(manifest = manifest(apt = "[\"$pkg\"]")),
                    "not a plain apt package name",
                )
            }
        }

        @Test
        fun `a pinned apt version is accepted`() {
            val parsed = CliPackageParser.parse(packageFile(manifest = manifest(apt = "[ca-certificates=20240101]")))
            assertEquals(listOf("ca-certificates=20240101"), parsed.manifest.depsApt)
        }

        @Test
        fun `a secret env param cannot ship its value inside the package`() {
            assertRefused(
                packageFile(
                    manifest = VALID_MANIFEST.replace(
                        "- { envParamName: DEMO_PROFILE, description: profile }",
                        "- { envParamName: DEMO_KEY, secret: true, defaultValue: s3cret }",
                    ),
                ),
                "credentials belong in env-var bindings",
            )
        }

        @Test
        fun `runtimeEnv takes a published slot or a literal, never a mix`() {
            assertRefused(
                packageFile(manifest = VALID_MANIFEST.replace("DEMO_URL: http://admin:8080", "DEMO_URL: http://x/\${platform.adminUrl}")),
                "the whole value",
            )
            assertRefused(
                packageFile(manifest = VALID_MANIFEST.replace("DEMO_TOKEN: \${platform.internalToken}", "DEMO_TOKEN: \${platform.sshKey}")),
                "does not publish",
            )
        }

        private fun manifest(
            name: String = "demo",
            apt: String = "[ca-certificates]",
        ): String = VALID_MANIFEST
            .replace("name: demo", "name: $name")
            .replace("apt: [ca-certificates]", "apt: $apt")
    }

    // ==================== skill rules ====================

    @Nested
    inner class SkillRules {
        @Test
        fun `a package without a skill is refused`() {
            assertRefused(packageFile(entries = manifestEntries() + payloadEntries()), "a CLI ships its own skill")
        }

        @Test
        fun `a skill that renames itself is refused`() {
            val skill = "---\nname: something-else\ndescription: d\n---\n\nBody\n"
            assertRefused(
                packageFile(entries = manifestEntries() + textEntries("skill/SKILL.md" to skill) + payloadEntries()),
                "the skill takes the CLI's name",
            )
        }

        @Test
        fun `a binary asset is refused because skill resources ship as text`() {
            assertRefused(
                packageFile(
                    entries = manifestEntries() + skillEntries() + payloadEntries() +
                        listOf(entry("${CliPackageLayout.SKILL_ASSET_PREFIX}logo.png", byteArrayOf(0, 1, 2, -1), FILE_MODE)),
                ),
                "not UTF-8 text",
            )
        }

        @Test
        fun `a skill over the size cap is refused`() {
            assertRefused(
                packageFile(
                    entries = manifestEntries() +
                        listOf(entry(CliPackageLayout.SKILL_ENTRY, (VALID_SKILL + "x".repeat(1024 * 1024)).toByteArray(), FILE_MODE)) +
                        payloadEntries(),
                ),
                "is over the 1048576 limit",
            )
        }

        @Test
        fun `an asset shipping more than the per-asset cap is refused`() {
            assertRefused(
                packageFile(
                    entries = manifestEntries() + skillEntries() + payloadEntries() +
                        listOf(entry("${CliPackageLayout.SKILL_ASSET_PREFIX}big.md", ByteArray(600 * 1024), FILE_MODE)),
                ),
                "is 614400 bytes, over the 524288 per-asset limit",
            )
        }

        @Test
        fun `an asset whose key leaves its own tree is refused`() {
            // The key is the path the runtime writes the file to, so one spelling of one location is
            // not enough: `skill/assets/../x.md` would land outside the skill directory.
            assertRefused(
                packageFile(
                    entries = manifestEntries() + skillEntries() + payloadEntries() +
                        listOf(entry("${CliPackageLayout.SKILL_ASSET_PREFIX}../escape.md", "# escape\n".toByteArray(), FILE_MODE)),
                ),
                "escapes its root",
            )
        }
    }

    // ==================== payload rules ====================

    @Nested
    inner class PayloadRules {
        @Test
        fun `an empty payload is refused`() {
            assertRefused(packageFile(entries = manifestEntries() + skillEntries()), "no files under payload/")
        }

        @Test
        fun `a path that escapes the container root is refused`() {
            listOf("payload/../../etc/passwd", "payload/usr/../../shadow").forEach { path ->
                assertRefused(packageFile(withPayload = entry(path, BINARY, EXEC_MODE)), "escapes its root")
            }
        }

        @Test
        fun `a denied destination reached by another spelling is refused too`() {
            // The deny list compares one spelling, and resolveInside would happily accept another:
            // `usr/./bin/docker` and `usr//bin/su` land exactly where the package is not allowed.
            listOf(
                "payload/usr/./bin/docker" to "canonical form",
                "payload/./etc/cron.d/evil" to "canonical form",
                "payload/usr//bin/su" to "empty segment",
            ).forEach { (path, reason) ->
                assertRefused(packageFile(withPayload = entry(path, BINARY, EXEC_MODE)), reason)
            }
        }

        @Test
        fun `an archive that repeats one entry name is refused`() {
            // One name means three different things here: the digest hashes both copies, reading answers
            // with the first, extraction keeps the last. Registration cannot pick a side.
            assertRefused(
                packageFile(entries = manifestEntries() + skillEntries() + payloadEntries() + payloadEntries()),
                "appears more than once",
            )
        }

        @Test
        fun `a denied destination is refused even when it is well formed`() {
            listOf(
                "payload/etc/cron.d/evil",
                "payload/root/.ssh/authorized_keys",
                "payload/usr/bin/docker",
                "payload/usr/local/bin/docker",
                "payload/sbin/init",
                "payload/var/run/docker.sock",
            ).forEach { path ->
                assertRefused(packageFile(withPayload = entry(path, BINARY, FILE_MODE)), "denied target")
            }
        }

        @Test
        fun `a neighbour of a denied binary keeps its name`() {
            // The deny list names files, not prefixes: refusing `usr/bin/docker-compose` because
            // `usr/bin/docker` is on the list would read to the author as a parser bug.
            val parsed = CliPackageParser.parse(
                packageFile(entries = manifestEntries() + skillEntries() + listOf(entry("payload/usr/bin/docker-compose", BINARY, EXEC_MODE))),
            )

            assertEquals("payload/usr/bin/docker-compose", parsed.payloadFiles.single().archivePath)
        }

        @Test
        fun `a payload written by a packer that stores no unix mode is refused`() {
            // Windows `zip` records no UNIX creator, so the binary would land as 0644 and fail with 126.
            assertRefused(
                packageFile(entries = manifestEntries() + skillEntries() + listOf(entry("payload/usr/local/bin/demo", BINARY, null))),
                "carry no unix mode",
            )
        }

        @Test
        fun `a symbolic link is refused rather than turned into a file holding a path`() {
            assertRefused(
                packageFile(
                    entries = manifestEntries() + skillEntries() + payloadEntries() +
                        listOf(entry("payload/usr/local/bin/evil", "/etc/shadow".toByteArray(), S_IFLNK or 0b111_101_101)),
                ),
                "symbolic link",
            )
        }

        @Test
        fun `setuid and setgid bits are refused`() {
            listOf(0b100_000_000_000, 0b010_000_000_000).forEach { special ->
                assertRefused(
                    packageFile(
                        entries = manifestEntries() + skillEntries() +
                            listOf(entry("payload/usr/local/bin/demo", BINARY, S_IFREG or special or 0b111_101_101)),
                    ),
                    "setuid/setgid/sticky",
                )
            }
        }

        @Test
        fun `a payload with no execute bit anywhere is refused`() {
            assertRefused(
                packageFile(entries = manifestEntries() + skillEntries() + listOf(entry("payload/usr/local/bin/demo", BINARY, FILE_MODE))),
                "no payload file carries an execute bit",
            )
        }

        @Test
        fun `stray files outside the three documented locations are refused`() {
            assertRefused(
                packageFile(entries = manifestEntries() + skillEntries() + payloadEntries() + listOf(entry("README.md", BINARY, FILE_MODE))),
                "unexpected",
            )
        }

        @Test
        fun `a payload at the file count limit is accepted and one file over it is refused`() {
            // The limit keeps `docker build` from unpacking a tree of a million inodes. Both sides are
            // pinned because the boundary is a `>` over a count the packer controls, and an off-by-one
            // there only shows up as a real package that cannot be registered.
            val atLimit = payloadTree(CliPackageLayout.MAX_PAYLOAD_FILES)
            val parsed = CliPackageParser.parse(packageFile(entries = manifestEntries() + skillEntries() + atLimit))
            assertEquals(CliPackageLayout.MAX_PAYLOAD_FILES, parsed.payloadFiles.size)

            assertRefused(
                packageFile(entries = manifestEntries() + skillEntries() + payloadTree(CliPackageLayout.MAX_PAYLOAD_FILES + 1)),
                "over the ${CliPackageLayout.MAX_PAYLOAD_FILES} limit",
            )
        }

        private fun payloadTree(files: Int): List<Entry> = (1..files).map {
            entry("payload/usr/local/bin/f$it", BINARY, EXEC_MODE)
        }
    }

    // ==================== digests ====================

    @Nested
    inner class DigestRules {
        @Test
        fun `packing the same content again moves only the package digest`() {
            val first = CliPackageParser.parse(packageFile())
            // The same files in a different order and with different timestamps: a new zip, the same tree.
            val repacked = CliPackageParser.parse(
                packageFile(
                    fileName = "demo-1.4.0-repacked.harnaxcli.zip",
                    time = T2,
                    entries = payloadEntries() + assetEntries() + skillEntries() + manifestEntries(),
                ),
            )
            assertEquals(first.payloadDigest, repacked.payloadDigest)
            assertTrue(first.packageDigest != repacked.packageDigest, "a re-packed zip is a different object")
        }

        @Test
        fun `the same bytes give the same digests`() {
            val bytes = zipBytes(defaultEntries(), T1)
            val a = CliPackageParser.parse(writeBytes("demo-1.4.0.harnaxcli.zip", bytes))
            val b = CliPackageParser.parse(writeBytes("demo-1.4.0.harnaxcli.zip", bytes))
            assertEquals(a.packageDigest, b.packageDigest)
            assertEquals(a.payloadDigest, b.payloadDigest)
        }

        @Test
        fun `a packer that also records the payload directories gives the same image fingerprint`() {
            // `zip -r`, which is how this repository packs its own CLI, writes an entry for every
            // directory it walks; other packers write only files. The tree that reaches the image is the
            // same either way, so a directory entry must not contribute — if it did, switching packer
            // would rebuild every CLI image and every running sandbox with it.
            val before = CliPackageParser.parse(packageFile())
            val dirs = listOf(
                entry("payload/", ByteArray(0), S_IFDIR or 0b111_101_101),
                entry("payload/usr/local/bin/", ByteArray(0), S_IFDIR or 0b111_101_101),
            )
            val after = CliPackageParser.parse(
                packageFile(entries = manifestEntries() + dirs + skillEntries() + assetEntries() + payloadEntries()),
            )

            assertEquals(before.payloadDigest, after.payloadDigest)
            assertTrue(before.packageDigest != after.packageDigest, "the archive itself is different bytes")
            assertEquals(1, after.payloadFiles.size)
        }

        @Test
        fun `editing the skill leaves the image fingerprint alone`() {
            val before = CliPackageParser.parse(packageFile())
            val skill = "---\nname: demo\ndescription: Demo command line skill\n---\n\nRewritten guidance\n"
            val after = CliPackageParser.parse(
                packageFile(entries = manifestEntries() + textEntries("skill/SKILL.md" to skill) + assetEntries() + payloadEntries()),
            )
            assertEquals(before.payloadDigest, after.payloadDigest)
            assertTrue(before.packageDigest != after.packageDigest)
        }

        @Test
        fun `a new binary moves the image fingerprint`() {
            val before = CliPackageParser.parse(packageFile())
            val after = CliPackageParser.parse(
                packageFile(entries = manifestEntries() + skillEntries() + listOf(entry("payload/usr/local/bin/demo", "rebuilt".toByteArray(), EXEC_MODE))),
            )
            assertTrue(before.payloadDigest != after.payloadDigest)
        }

        @Test
        fun `a mode change alone moves the image fingerprint too`() {
            val before = CliPackageParser.parse(packageFile())
            val after = CliPackageParser.parse(
                packageFile(entries = manifestEntries() + skillEntries() + listOf(entry("payload/usr/local/bin/demo", BINARY, S_IFREG or 0b111_101_000))),
            )
            assertTrue(before.payloadDigest != after.payloadDigest)
        }

        @Test
        fun `an extra apt dependency is part of the image fingerprint`() {
            val before = CliPackageParser.parse(packageFile())
            val after = CliPackageParser.parse(packageFile(manifest = VALID_MANIFEST.replace("apt: [ca-certificates]", "apt: [ca-certificates, curl]")))
            assertTrue(before.payloadDigest != after.payloadDigest)
        }
    }

    @Test
    fun `a file that is not a zip is refused with a readable reason`() {
        assertRefused(writeBytes("demo-1.4.0.harnaxcli.zip", "not a zip at all".toByteArray()), "unreadable package")
    }

    /**
     * The package this repository ships is built by `harnax-cli/Makefile` out of two hand-written
     * documents, and nothing reads them until admin scans its package directory on a restart. A renamed
     * manifest key or a skill that calls itself something else therefore fails in a container log.
     */
    @Test
    fun `the CLI package this repository ships parses`() {
        val manifest = shippedDocument("plugin.yaml").readText()
        val skill = shippedDocument("SKILL.md").readText()
        val name = declaredScalar(manifest, "name")
        val version = declaredScalar(manifest, "version")

        val parsed = CliPackageParser.parse(
            packageFile(
                fileName = "$name-$version${CliPackageLayout.FILE_SUFFIX}",
                entries = manifestEntry(manifest) +
                    entry(CliPackageLayout.SKILL_ENTRY, skill.toByteArray(), FILE_MODE) +
                    // The real payload is the compiled binary; only its name and mode are rules here.
                    entry("payload/usr/local/bin/$name", BINARY, EXEC_MODE),
            ),
        )

        assertEquals(name, parsed.manifest.name)
        assertEquals(version, parsed.manifest.version)
        // The token the CLI authenticates with inside a sandbox comes only from a published slot. A
        // literal or a misspelt placeholder ships as a credential that never resolves.
        assertEquals("\${platform.internalToken}", parsed.manifest.runtimeEnv["HARNAX_TOKEN"])
        val findings = SkillContentScanner.scan(parsed.skillMd, parsed.skillAssets)
        assertTrue(findings.isEmpty(), "shipped skill is quarantined on registration: ${findings.map { "${it.resource}:${it.ruleId}" }}")
    }

    private fun shippedDocument(name: String): File {
        var walk: File? = File(System.getProperty("user.dir")).absoluteFile
        while (walk != null) {
            val candidate = File(walk, "harnax-cli/$name")
            if (candidate.isFile) return candidate
            walk = walk.parentFile
        }
        throw IllegalStateException("harnax-cli/$name is missing, so the package this suite accepts is not the one that ships")
    }

    private fun declaredScalar(
        manifest: String,
        key: String,
    ): String = Regex("^$key: (.+)$", RegexOption.MULTILINE).find(manifest)?.groupValues?.get(1)?.trim()
        ?: throw IllegalStateException("harnax-cli/plugin.yaml declares no $key")

    // ==================== fixtures ====================

    private fun refuse(file: File): String = assertThrows<CliPackageException> { CliPackageParser.parse(file) }.message!!

    private fun assertRefused(
        file: File,
        expected: String,
    ) {
        val reason = refuse(file)
        assertTrue(expected in reason, "expected \"$expected\" in:\n$reason")
    }

    /**
     * Builds a package. [entries] replaces the whole tree; [withPayload] keeps the default tree and
     * adds one more payload file, which is what most payload-rule tests need.
     */
    private fun packageFile(
        fileName: String = "demo-1.4.0.harnaxcli.zip",
        manifest: String = VALID_MANIFEST,
        time: FileTime = T1,
        entries: List<Entry>? = null,
        withPayload: Entry? = null,
    ): File {
        val tree = entries
            ?: manifestEntry(manifest) + assetEntries() + skillEntries() + payloadEntries() + listOfNotNull(withPayload)
        return writeBytes(fileName, zipBytes(tree.map { it.copy(time = time) }, time))
    }

    private fun defaultEntries(): List<Entry> = manifestEntry(VALID_MANIFEST) + assetEntries() + skillEntries() + payloadEntries()

    private fun writeBytes(
        fileName: String,
        bytes: ByteArray,
    ): File = File(dir, fileName).apply { writeBytes(bytes) }

    private fun zipBytes(
        entries: List<Entry>,
        time: FileTime,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipArchiveOutputStream(out).use { zos ->
            entries.forEach { spec ->
                val entry = ZipArchiveEntry(spec.name)
                spec.mode?.let { entry.unixMode = it }
                entry.lastModifiedTime = time
                zos.putArchiveEntry(entry)
                zos.write(spec.bytes)
                zos.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val S_IFREG = 0x8000
        const val S_IFDIR = 0x4000
        const val S_IFLNK = 0xA000
        const val FILE_MODE = S_IFREG or 0b110_100_100
        const val EXEC_MODE = S_IFREG or 0b111_101_101
        val T1 = FileTime.fromMillis(1_700_000_000_000L)
        val T2 = FileTime.fromMillis(1_800_000_000_000L)
        val BINARY = "demo binary".toByteArray()
        val HEX64 = Regex("^[0-9a-f]{64}$")

        const val VALID_MANIFEST = """
name: demo
version: 1.4.0
description: Demo command line
checkCommand: demo --version
deps:
  apt: [ca-certificates]
envParams:
  - { envParamName: DEMO_PROFILE, description: profile }
runtimeEnv:
  DEMO_URL: http://admin:8080
  DEMO_TOKEN: ${'$'}{platform.internalToken}

"""

        const val VALID_SKILL = """---
name: demo
description: Demo command line skill
---

# Demo

Use `demo` to do demo things.
"""

        fun entry(
            name: String,
            bytes: ByteArray,
            mode: Int?,
        ) = Entry(name, bytes, mode)

        fun manifestEntry(text: String) = listOf(entry(CliPackageLayout.MANIFEST_ENTRY, text.toByteArray(), FILE_MODE))

        fun manifestEntries() = manifestEntry(VALID_MANIFEST)

        fun skillEntries() = listOf(entry(CliPackageLayout.SKILL_ENTRY, VALID_SKILL.toByteArray(), FILE_MODE))

        fun assetEntries() = listOf(entry("${CliPackageLayout.SKILL_ASSET_PREFIX}cheatsheet.md", "# Cheatsheet\n".toByteArray(), FILE_MODE))

        fun payloadEntries() = listOf(entry("payload/usr/local/bin/demo", BINARY, EXEC_MODE))

        fun dirEntries() = listOf(entry("skill/", ByteArray(0), S_IFDIR or 0b111_101_101), entry("payload/", ByteArray(0), S_IFDIR or 0b111_101_101))

        fun textEntries(vararg bodies: Pair<String, String>) = bodies.map { (name, body) -> entry(name, body.toByteArray(), FILE_MODE) }
    }
}

private data class Entry(
    val name: String,
    val bytes: ByteArray,
    val mode: Int?,
    val time: FileTime = FileTime.fromMillis(1_700_000_000_000L),
)
