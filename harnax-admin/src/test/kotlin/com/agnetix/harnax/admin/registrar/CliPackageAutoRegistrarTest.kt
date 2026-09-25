package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.common.cli.CliPackageLayout
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
import io.minio.BucketExistsArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.Result
import io.minio.messages.Item
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import org.springframework.beans.factory.ObjectProvider
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.attribute.FileTime
import java.time.ZonedDateTime

/**
 * CliPackageAutoRegistrar Unit Tests
 *
 * The registrar is the only writer of the `cli` table, so what is covered here is the lifecycle it
 * decides: whether a row exists at all, whether its archive is re-uploaded, whether a skill follows the
 * operator's switch, whether the rows one package writes land as one unit, which of two packages claiming
 * one name wins, and whether a row is allowed to be deleted. Archive rules belong to
 * [CliPackageParserTest]; the one column this class must not touch —
 * `status` — is asserted against a real database by CliManagementIT, because the guard is a SQL column
 * list and a mocked mapper cannot see it.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliPackageAutoRegistrarTest {
    @TempDir
    lateinit var packageDir: File

    @Mock
    private lateinit var cliMapper: CliMapper

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Mock
    private lateinit var agentCliBindingMapper: AgentCliBindingMapper

    @Mock
    private lateinit var agentSkillBindingMapper: AgentSkillBindingMapper

    @Mock
    private lateinit var teamSkillBindingMapper: TeamSkillBindingMapper

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    @Mock
    private lateinit var minioClients: ObjectProvider<MinioClient>

    @Mock
    private lateinit var minio: MinioClient

    @Mock
    private lateinit var transactionManager: PlatformTransactionManager

    /** The last skill row [SkillMapper.insert] was called with, as the registrar handed it over. */
    private var lastInsertedSkill: Skill? = null

    @BeforeEach
    fun setUp() {
        `when`(minioClients.getIfAvailable()).thenReturn(minio)
        // One status per transaction, not one shared stub: a run that registers two packages and then
        // prunes opens three transactions, and the assertions below name which one rolled back.
        `when`(transactionManager.getTransaction(any())).thenAnswer { mock(TransactionStatus::class.java) }
        `when`(minio.bucketExists(any<BucketExistsArgs>())).thenReturn(true)
        `when`(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)).thenReturn(repository())
        `when`(skillMapper.selectByNameAndRepo(anyString(), anyLong())).thenReturn(null)
        `when`(skillMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<Skill>(0).also {
                it.id = SKILL_ID
                lastInsertedSkill = it
            }
            1
        }
        `when`(cliMapper.selectByName(anyString())).thenReturn(null)
        `when`(cliMapper.selectAll()).thenReturn(emptyList())
        `when`(secretFieldEncryptor.serializeToolEnvParams(anyOrNull(), anyOrNull())).thenReturn(ENVPARAMS_JSON)
    }

    // ==================== fixtures ====================

    private fun registrarAt(
        dir: File,
        retentionDays: Long = ARCHIVE_RETENTION_DAYS,
    ): CliPackageAutoRegistrar = CliPackageAutoRegistrar(
        cliMapper,
        skillMapper,
        skillRepositoryMapper,
        agentCliBindingMapper,
        agentSkillBindingMapper,
        teamSkillBindingMapper,
        secretFieldEncryptor,
        minioClients,
        transactionManager,
        dir.path,
        BUCKET,
        retentionDays,
    )

    private fun sync() = registrarAt(packageDir).syncCliPackages()

    private fun repository(): SkillRepository = SkillRepository().apply {
        id = REPO_ID
        tenantId = 1L
        name = BuiltinRepository.CLI_SKILLS
    }

    /**
     * Builds a package the parser accepts: a manifest, the skill it ships and one executable payload
     * file. [payloadBody] is what the image copies in, so it is the only argument that moves
     * `payloadDigest`.
     */
    private fun writePackage(
        name: String,
        version: String,
        skillBody: String? = null,
        payloadBody: String = "demo binary",
        depsApt: String = "ca-certificates",
        rawManifest: String? = null,
        fileName: String = "$name-$version${CliPackageLayout.FILE_SUFFIX}",
    ): File {
        val manifestText = rawManifest ?: manifest(name, version, depsApt)
        val entries =
            listOf(
                PackageEntry(CliPackageLayout.MANIFEST_ENTRY, manifestText.toByteArray(), FILE_MODE),
                PackageEntry(CliPackageLayout.SKILL_ENTRY, (skillBody ?: skillText(name)).toByteArray(), FILE_MODE),
                PackageEntry("payload/usr/local/bin/$name", payloadBody.toByteArray(), EXEC_MODE),
            )
        return File(packageDir, fileName).apply { writeBytes(zip(entries)) }
    }

    private fun manifest(
        name: String,
        version: String,
        depsApt: String,
    ): String = """
name: $name
version: $version
description: Demo command line
checkCommand: $name --version
deps:
  apt: [$depsApt]
envParams:
  - { envParamName: DEMO_PROFILE, description: profile }
runtimeEnv:
  DEMO_URL: ${'$'}{platform.adminUrl}

"""

    private fun skillText(name: String) = "---\nname: $name\ndescription: Demo command line skill\n---\n\n# $name\n\nUse it.\n"

    private fun zip(entries: List<PackageEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipArchiveOutputStream(out).use { zos ->
            entries.forEach { spec ->
                val entry = ZipArchiveEntry(spec.name)
                spec.mode?.let { entry.unixMode = it }
                entry.lastModifiedTime = TIME
                zos.putArchiveEntry(entry)
                zos.write(spec.bytes)
                zos.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    /** A row already in the database, i.e. the state a re-registration converges from. */
    private fun existingRow(
        status: Int = 1,
        packageDigest: String = "",
        payloadDigest: String = "",
    ): Cli = Cli().apply {
        id = 42L
        name = "demo"
        this.status = status
        this.packageDigest = packageDigest
        this.payloadDigest = payloadDigest
        this.packageObject = "demo/$packageDigest${CliPackageLayout.FILE_SUFFIX}"
        skillId = SKILL_ID
        version = "1.4.0"
    }

    /** A registrar-written row whose package has left the directory. */
    private fun staleRow(
        id: Long,
        name: String,
        skillId: Long?,
        packageDigest: String = STALE_DIGEST,
    ): Cli = Cli().apply {
        this.id = id
        this.name = name
        this.skillId = skillId
        this.packageDigest = packageDigest
    }

    /** A row the registrar never wrote: the `cli` page's hand-entered leftovers, which V38 retires. */
    private fun leftoverRow(
        id: Long,
        name: String,
        skillId: Long?,
    ): Cli = staleRow(id, name, skillId, packageDigest = "")

    private fun cliRows(): List<Cli> = argumentCaptor<Cli>().apply { verify(cliMapper, atLeastOnce()).upsertCliPackage(capture()) }.allValues

    private fun capturedCli(): Cli = cliRows().last()

    /**
     * What the registrar pushed to the object store, as `bucket to object key`.
     *
     * The key is the half that matters: [Cli.packageObject] is how agent-service later finds the
     * archive, so a row pointing somewhere the bytes were not written to is a CLI that cannot load.
     */
    private fun capturedUploads(): List<Pair<String, String>> = argumentCaptor<PutObjectArgs>().apply { verify(minio, atLeastOnce()).putObject(capture()) }
        .allValues
        .map { it.bucket() to it.`object`() }

    /** What the registrar asked the object store to delete, as object keys. */
    private fun capturedDeletions(): List<String> = argumentCaptor<RemoveObjectArgs>().apply { verify(minio, atLeastOnce()).removeObject(capture()) }
        .allValues
        .map { it.`object`() }

    private fun bucket(vararg entries: Result<Item>) {
        `when`(minio.listObjects(any<ListObjectsArgs>())).thenReturn(entries.toList())
    }

    /** Which archives the `cli` table still names — the sweep's other input. */
    private fun referenced(vararg keys: String) {
        `when`(cliMapper.selectPackageObjects()).thenReturn(keys.toList())
    }

    /** One listing entry: the object key and when it was last written, as the object store reports them. */
    private fun stored(
        key: String,
        daysOld: Long,
    ): Item = mock(Item::class.java).apply {
        `when`(objectName()).thenReturn(key)
        `when`(lastModified()).thenReturn(ZonedDateTime.now().minusDays(daysOld))
    }

    private fun packageKey(digest: String) = "demo/$digest${CliPackageLayout.FILE_SUFFIX}"

    // ==================== tests ====================

    @Nested
    inner class FirstRegistration {
        @Test
        fun `a package writes every manifest-owned column and stores the archive under its digest`() {
            val file = writePackage("demo", "1.4.0")
            sync()

            val cli = capturedCli()
            assertEquals("demo", cli.name)
            assertEquals("1.4.0", cli.version)
            assertEquals("Demo command line", cli.description)
            assertEquals("demo --version", cli.checkCommand)
            assertEquals(CliPackageLayout.packageDigest(file), cli.packageDigest)
            assertEquals("${cli.name}/${cli.packageDigest}${CliPackageLayout.FILE_SUFFIX}", cli.packageObject)
            assertEquals("""["ca-certificates"]""", cli.depsApt)
            assertEquals("""{"DEMO_URL":"${'$'}{platform.adminUrl}"}""", cli.runtimeEnv)
            assertEquals(ENVPARAMS_JSON, cli.envParams)
            assertEquals(SKILL_ID, cli.skillId)
            assertEquals(listOf(BUCKET to cli.packageObject), capturedUploads())
        }

        @Test
        fun `the shipped skill is inserted under the package name into the managed repository`() {
            writePackage("demo", "1.4.0")
            sync()

            val skill = requireNotNull(lastInsertedSkill)
            assertEquals("demo", skill.name)
            assertEquals(REPO_ID, skill.repositoryId)
            assertEquals(1L, skill.tenantId)
            assertEquals("Demo command line skill", skill.description)
            assertEquals("1.4.0", skill.version)
            assertEquals(1, skill.status)
            assertEquals(1, skill.isPublic)
            assertEquals("SYSTEM", skill.creator)
            assertEquals(capturedCli().skillId, skill.id)
        }

        @Test
        fun `a package is refused when the managed repository has nowhere to put its skill`() {
            `when`(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)).thenReturn(null)
            writePackage("demo", "1.4.0")
            sync()

            verify(cliMapper, never()).upsertCliPackage(any())
            verify(skillMapper, never()).insert(any())
        }

        @Test
        fun `a manifest that declares nothing optional leaves the json columns null`() {
            writePackage(
                "demo",
                "1.4.0",
                rawManifest = "name: demo\nversion: 1.4.0\ndescription: Demo command line\ncheckCommand: demo --version\n",
            )
            sync()

            val cli = capturedCli()
            assertEquals(null, cli.depsApt)
            assertEquals(null, cli.runtimeEnv)
        }
    }

    @Nested
    inner class Convergence {
        @Test
        fun `an unchanged package costs no upload yet still refreshes the row`() {
            val digest = CliPackageLayout.packageDigest(writePackage("demo", "1.4.0"))
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(packageDigest = digest))
            sync()

            verify(minio, never()).putObject(any<PutObjectArgs>())
            verify(cliMapper, times(1)).upsertCliPackage(any())
        }

        @Test
        fun `a changed package is stored again under the new digest as its object key`() {
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(packageDigest = "a".repeat(64)))
            writePackage("demo", "1.4.0")
            sync()

            val cli = capturedCli()
            assertNotEquals("a".repeat(64), cli.packageDigest)
            assertEquals("demo/${cli.packageDigest}${CliPackageLayout.FILE_SUFFIX}", cli.packageObject)
            assertEquals(listOf(BUCKET to cli.packageObject), capturedUploads())
        }

        @Test
        fun `a skill-only change moves the package digest but not the image fingerprint`() {
            writePackage("demo", "1.4.0")
            sync()
            val first = capturedCli()
            val firstSkillMd = requireNotNull(lastInsertedSkill).skillmd

            `when`(cliMapper.selectByName("demo")).thenReturn(
                existingRow(packageDigest = first.packageDigest, payloadDigest = first.payloadDigest),
            )
            packageDir.listFiles()?.forEach { it.delete() }
            writePackage("demo", "1.4.1", skillBody = skillText("demo") + "\nOne more paragraph.\n")
            sync()
            val second = capturedCli()

            assertEquals(first.payloadDigest, second.payloadDigest)
            assertNotEquals(first.packageDigest, second.packageDigest)
            assertEquals("1.4.1", second.version)
            assertNotEquals(firstSkillMd, requireNotNull(lastInsertedSkill).skillmd)
        }

        @Test
        fun `re-registering a package keeps one skill row for its name`() {
            val stored =
                Skill().apply {
                    id = SKILL_ID
                    name = "demo"
                    repositoryId = REPO_ID
                    status = 1
                }
            `when`(skillMapper.selectByNameAndRepo("demo", REPO_ID)).thenReturn(stored)
            writePackage("demo", "1.4.0")
            sync()

            verify(skillMapper, never()).insert(any())
            verify(skillMapper, times(1)).updateById(any())
        }

        @Test
        fun `a disabled cli takes its skill down with it and never re-arms either`() {
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(status = 0))
            `when`(cliMapper.selectByNameForUpdate("demo")).thenReturn(existingRow(status = 0))
            val stored =
                Skill().apply {
                    id = SKILL_ID
                    name = "demo"
                    repositoryId = REPO_ID
                    status = 1
                }
            `when`(skillMapper.selectByNameAndRepo("demo", REPO_ID)).thenReturn(stored)
            writePackage("demo", "1.4.0")
            sync()

            verify(skillMapper, times(1)).updateStatus(SKILL_ID, 0)
            verify(skillMapper, never()).updateStatus(SKILL_ID, 1)
        }

        @Test
        fun `a skill already in step with the cli is left without a status write`() {
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(status = 1))
            `when`(cliMapper.selectByNameForUpdate("demo")).thenReturn(existingRow(status = 1))
            val stored =
                Skill().apply {
                    id = SKILL_ID
                    name = "demo"
                    repositoryId = REPO_ID
                    status = 1
                }
            `when`(skillMapper.selectByNameAndRepo("demo", REPO_ID)).thenReturn(stored)
            writePackage("demo", "1.4.0")
            sync()

            verify(skillMapper, never()).updateStatus(anyLong(), anyInt())
        }

        /**
         * The kill switch the shipped skill follows is the one read under the row's lock.
         *
         * `toggleCliStatus` writes `cli.status` and the shipped skill's status in one request, while this
         * path's first read happens before its own transaction opens. A toggle landing in that window made
         * the skill follow the stale value, and the two rows then disagreed until the next restart. The
         * plain read still answers enabled here on purpose: what is pinned is that the locked row wins, not
         * that the earlier read stopped being consulted — it still decides the upload.
         */
        @Test
        fun `a kill switch flipped after the plain read is what the shipped skill follows`() {
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(status = 1))
            `when`(cliMapper.selectByNameForUpdate("demo")).thenReturn(existingRow(status = 0))
            val stored =
                Skill().apply {
                    id = SKILL_ID
                    name = "demo"
                    repositoryId = REPO_ID
                    status = 1
                }
            `when`(skillMapper.selectByNameAndRepo("demo", REPO_ID)).thenReturn(stored)
            writePackage("demo", "1.4.0")
            sync()

            verify(skillMapper, times(1)).updateStatus(SKILL_ID, 0)
            verify(skillMapper, never()).updateStatus(SKILL_ID, 1)
        }

        @Test
        fun `a content scan hit registers the cli and stores its skill disabled`() {
            writePackage("demo", "1.4.0", skillBody = skillText("demo") + "\n`rm -rf /` wipes the disk.\n")
            sync()

            assertEquals(SKILL_ID, capturedCli().skillId)
            assertEquals(0, requireNotNull(lastInsertedSkill).status)
        }
    }

    /**
     * The two rows a package writes are one unit of work, and so are the rows a prune removes.
     *
     * `register` puts the shipped skill in before the `cli` row that points at it; the prune deletes
     * bindings, the skill and the `cli` row. Neither half can land alone: a skill row no `cli.skill_id`
     * names is invisible to every later prune forever, and bindings whose `cli` row vanished are agents
     * configured with a CLI that no longer exists. The archive stays outside the transaction on purpose —
     * an object store cannot roll back, so the failure mode that leaves a stray object behind is the one
     * that never leaves a committed row pointing at nothing.
     */
    @Nested
    inner class TransactionScope {
        @Test
        fun `the shipped skill and the cli row commit together`() {
            writePackage("demo", "1.4.0")
            sync()

            // The order is the point, not the count: with the skill write outside the transaction the run
            // still commits and the orphan this fix exists to prevent comes straight back.
            val order = inOrder(transactionManager, skillMapper, cliMapper)
            order.verify(transactionManager).getTransaction(any())
            order.verify(skillMapper).insert(any())
            order.verify(cliMapper).upsertCliPackage(any())
            order.verify(transactionManager).commit(any())
            verify(transactionManager, never()).rollback(any())
        }

        /**
         * The row whose `status` the shipped skill follows is read after the transaction opens.
         *
         * `SELECT … FOR UPDATE` only holds anything while the transaction still runs, so this ordering is
         * what closes the window against `toggleCliStatus`: the toggle's `updateStatus` waits for the row's
         * lock, and by the time it lands the skill row already carries the status it read.
         */
        @Test
        fun `the cli row the kill switch is taken from is read inside the transaction`() {
            writePackage("demo", "1.4.0")
            sync()

            val order = inOrder(transactionManager, cliMapper, skillMapper)
            order.verify(transactionManager).getTransaction(any())
            order.verify(cliMapper).selectByNameForUpdate("demo")
            order.verify(skillMapper).insert(any())
            order.verify(transactionManager).commit(any())
        }

        @Test
        fun `a skill write that fails rolls back and never reaches the cli row`() {
            writePackage("demo", "1.4.0")
            `when`(skillMapper.insert(any())).thenThrow(RuntimeException("skill write failed"))
            sync()

            // A rollback can only be asked for by an exception that escaped the transaction body, so
            // this is what proves the skill write is inside it — and the cli row is the one that follows.
            verify(transactionManager).rollback(any())
            verify(transactionManager, never()).commit(any())
            verify(cliMapper, never()).upsertCliPackage(any())
        }

        @Test
        fun `a cli row that fails to write rolls its skill back and prunes nothing`() {
            writePackage("demo", "1.4.0")
            `when`(cliMapper.upsertCliPackage(any())).thenThrow(RuntimeException("cli write failed"))
            sync()

            // Without the transaction this is the orphan: an active skill row in the managed repository
            // that no cli row names, which the prune only ever reaches through cli.skill_id.
            verify(transactionManager).rollback(any())
            verify(transactionManager, never()).commit(any())
            verify(cliMapper, never()).selectAll()
        }

        @Test
        fun `the archive is stored before the transaction opens`() {
            writePackage("demo", "1.4.0")
            sync()

            val order = inOrder(minio, transactionManager)
            order.verify(minio).putObject(any<PutObjectArgs>())
            order.verify(transactionManager).getTransaction(any())
        }

        @Test
        fun `a prune that fails on its first delete rolls back and stops before the cli row`() {
            writePackage("demo", "1.4.0")
            writePackage("other", "2.0.0")
            `when`(cliMapper.selectAll()).thenReturn(
                listOf(
                    staleRow(1L, "demo", 11L),
                    staleRow(2L, "other", 12L),
                    staleRow(3L, "gone", 13L),
                ),
            )
            `when`(agentCliBindingMapper.deleteByCliIds(any())).thenThrow(RuntimeException("binding delete failed"))
            assertThrows(RuntimeException::class.java) { sync() }

            // Without the transaction the bindings of a stale row are gone while its `cli` row lives on —
            // an agent quietly missing a CLI, with no row left to prune it from.
            verify(transactionManager).rollback(any())
            verify(cliMapper, never()).deleteByIds(any())
            verify(minio, never()).removeObject(any<RemoveObjectArgs>())
        }

        @Test
        fun `a prune that fails after its skill deletes still reclaims nothing`() {
            writePackage("demo", "1.4.0")
            writePackage("other", "2.0.0")
            `when`(cliMapper.selectAll()).thenReturn(
                listOf(
                    staleRow(1L, "demo", 11L),
                    staleRow(2L, "other", 12L),
                    staleRow(3L, "gone", 13L),
                ),
            )
            `when`(cliMapper.deleteByIds(any())).thenThrow(RuntimeException("cli delete failed"))

            assertThrows(RuntimeException::class.java) { sync() }

            verify(skillMapper).deleteById(13L)
            verify(transactionManager).rollback(any())
            // The reclaim sits after the commit, so a rolled-back prune must not delete the archive the
            // row that is still live points at.
            verify(minio, never()).removeObject(any<RemoveObjectArgs>())
        }
    }

    @Nested
    inner class OneBrokenPackage {
        @Test
        fun `the rest still register and no row is pruned on evidence that is missing a package`() {
            writePackage("demo", "1.4.0")
            File(packageDir, "broken-1.0.0${CliPackageLayout.FILE_SUFFIX}").writeBytes("not a zip at all".toByteArray())
            sync()

            assertEquals("demo", capturedCli().name)
            verify(cliMapper, never()).selectAll()
            verify(cliMapper, never()).deleteByIds(any())
        }
    }

    /**
     * Two files on the shelf declaring one name is an upgrade that left the old package behind. The
     * directory listing has nothing to say about which one the operator means; the manifest version has
     * everything to say, so that decides — and a tie decides nothing at all.
     */
    @Nested
    inner class NameCollision {
        @Test
        fun `the newer manifest version wins whatever the directory listing puts first`() {
            // File names a packer would never produce, on purpose: the listing here hands the older
            // package over first, so a "first one in the directory wins" reading of this code fails.
            writePackage("demo", "1.5.0", fileName = "demo-b-1.5.0${CliPackageLayout.FILE_SUFFIX}")
            writePackage("demo", "1.4.0", fileName = "demo-a-1.4.0${CliPackageLayout.FILE_SUFFIX}")
            sync()

            argumentCaptor<Cli>().apply { verify(cliMapper, times(1)).upsertCliPackage(capture()) }
            assertEquals("1.5.0", capturedCli().version)
            assertEquals(listOf(BUCKET to capturedCli().packageObject), capturedUploads())
        }

        @Test
        fun `versions are compared as numbers not as text`() {
            writePackage("demo", "1.0.9")
            writePackage("demo", "1.0.10")
            sync()

            assertEquals("1.0.10", capturedCli().version)
        }

        @Test
        fun `one name at one version in two files registers neither`() {
            writePackage("demo", "1.4.0", payloadBody = "one")
            writePackage(
                "demo",
                "1.4.0",
                payloadBody = "two",
                fileName = "demo-1.4.0-copy${CliPackageLayout.FILE_SUFFIX}",
            )
            sync()

            verify(cliMapper, never()).upsertCliPackage(any())
            verify(minio, never()).putObject(any<PutObjectArgs>())
            // The name has no package behind it this run, so the row already live for it is not evidence
            // of a retirement: the failure count is what has to keep the prune away.
            verify(cliMapper, never()).selectAll()
        }
    }

    @Nested
    inner class Prune {
        @Test
        fun `a package that left the directory takes its row its skill and its bindings`() {
            writePackage("demo", "1.4.0")
            writePackage("other", "2.0.0")
            `when`(cliMapper.selectAll()).thenReturn(
                listOf(
                    staleRow(1L, "demo", 11L),
                    staleRow(2L, "other", 12L),
                    staleRow(3L, "gone", 13L),
                ),
            )
            sync()

            verify(agentCliBindingMapper).deleteByCliIds(listOf(3L))
            verify(agentSkillBindingMapper).deleteBySkillIds(listOf(13L))
            verify(teamSkillBindingMapper).deleteBySkillIds(listOf(13L))
            verify(skillMapper).deleteById(13L)
            verify(cliMapper).deleteByIds(listOf(3L))
        }

        @Test
        fun `a stale row without a shipped skill cascades nothing else`() {
            writePackage("demo", "1.4.0")
            writePackage("other", "2.0.0")
            `when`(cliMapper.selectAll()).thenReturn(
                listOf(staleRow(1L, "demo", 11L), staleRow(2L, "other", 12L), staleRow(3L, "gone", null)),
            )
            sync()

            verify(cliMapper).deleteByIds(listOf(3L))
            verify(agentSkillBindingMapper, never()).deleteBySkillIds(any())
            verify(teamSkillBindingMapper, never()).deleteBySkillIds(any())
            verify(skillMapper, never()).deleteById(anyLong())
        }

        @Test
        fun `a stale set as large as the live set reads as an unmounted volume and is refused`() {
            writePackage("demo", "1.4.0")
            `when`(cliMapper.selectAll()).thenReturn(
                listOf(staleRow(1L, "demo", 11L), staleRow(2L, "gone", 12L)),
            )
            sync()

            verify(cliMapper, never()).deleteByIds(any())
            verify(skillMapper, never()).deleteById(anyLong())
        }

        @Test
        fun `a row the registrar never wrote is not its own to delete`() {
            writePackage("demo", "1.4.0")
            writePackage("other", "2.0.0")
            writePackage("third", "3.0.0")
            `when`(cliMapper.selectAll()).thenReturn(
                listOf(
                    staleRow(1L, "demo", 11L),
                    staleRow(2L, "other", 12L),
                    staleRow(3L, "third", 13L),
                    staleRow(4L, "gone", 14L),
                    leftoverRow(5L, "kubectl", 15L),
                ),
            )
            sync()

            // Three live and one stale is a ratio the prune acts on — and exactly the case in which the
            // hand-entered leftovers used to be erased along with their agent bindings on a restart.
            verify(cliMapper).deleteByIds(listOf(4L))
            verify(agentCliBindingMapper).deleteByCliIds(listOf(4L))
            verify(skillMapper).deleteById(14L)
            verify(skillMapper, never()).deleteById(15L)
        }

        @Test
        fun `live rows keep their bindings when only one package retired`() {
            writePackage("demo", "1.4.0")
            writePackage("other", "2.0.0")
            writePackage("third", "3.0.0")
            `when`(cliMapper.selectAll()).thenReturn(
                listOf(
                    staleRow(1L, "demo", 11L),
                    staleRow(2L, "other", 12L),
                    staleRow(3L, "third", 13L),
                    staleRow(4L, "gone", 14L),
                ),
            )
            sync()

            verify(cliMapper).deleteByIds(listOf(4L))
            verify(skillMapper, never()).deleteById(11L)
            verify(skillMapper, never()).deleteById(12L)
            verify(skillMapper, never()).deleteById(13L)
        }
    }

    /**
     * The third thing CLI-04 found accumulating: the archive an in-place upgrade left in the object store.
     *
     * [removeArchivesOf] only clears the archive of a row it deletes, so a package that stayed and merely
     * changed digest left its previous object behind with no row naming it and no path that would ever
     * remove it. The judgment here is the same one the runtime's sweeps use — an artifact nothing
     * references any more, plus a grace window for one that is merely young — because a running
     * agent-service can still be fetching the key the row it was started from no longer points at.
     */
    @Nested
    inner class OrphanArchives {
        @Test
        fun `an archive no row names any more is removed once it is past the retention window`() {
            writePackage("demo", "1.4.0")
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(packageDigest = LIVE_DIGEST))
            referenced(packageKey(LIVE_DIGEST))
            bucket(Result(stored(packageKey(LIVE_DIGEST), 30)), Result(stored(packageKey(ORPHAN_DIGEST), 30)))

            sync()

            assertEquals(listOf(packageKey(ORPHAN_DIGEST)), capturedDeletions())
        }

        @Test
        fun `the archive a row names is kept however old it is`() {
            writePackage("demo", "1.4.0")
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(packageDigest = LIVE_DIGEST))
            referenced(packageKey(LIVE_DIGEST))
            bucket(Result(stored(packageKey(LIVE_DIGEST), 365)))

            sync()

            verify(minio, never()).removeObject(any<RemoveObjectArgs>())
        }

        /**
         * The window is what makes this safe to run at all: the key a row stopped naming may still be the
         * one an instance fetched a moment ago and has not finished installing from.
         */
        @Test
        fun `an unreferenced archive inside the retention window is left alone`() {
            writePackage("demo", "1.4.0")
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(packageDigest = LIVE_DIGEST))
            referenced(packageKey(LIVE_DIGEST))
            bucket(Result(stored(packageKey(ORPHAN_DIGEST), 1)))

            sync()

            verify(minio, never()).removeObject(any<RemoveObjectArgs>())
        }

        /** A shorter configured window is what a deployment asks for, not a constant buried in the sweep. */
        @Test
        fun `the retention window is the configured one`() {
            writePackage("demo", "1.4.0")
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(packageDigest = LIVE_DIGEST))
            referenced(packageKey(LIVE_DIGEST))
            bucket(Result(stored(packageKey(ORPHAN_DIGEST), 5)))

            registrarAt(packageDir, retentionDays = 3).syncCliPackages()

            assertEquals(listOf(packageKey(ORPHAN_DIGEST)), capturedDeletions())
        }

        /**
         * The bucket is operator-visible storage, and only keys this class itself writes are its to
         * reclaim: anything else in it — a hand-uploaded file, an object from a retired layout — survives.
         */
        @Test
        fun `an object the registrar did not write is not its own to delete`() {
            writePackage("demo", "1.4.0")
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(packageDigest = LIVE_DIGEST))
            referenced(packageKey(LIVE_DIGEST))
            bucket(
                Result(stored("notes.txt", 30)),
                Result(stored("demo/legacy${CliPackageLayout.FILE_SUFFIX}", 30)),
                Result(stored("demo/${ORPHAN_DIGEST.uppercase()}${CliPackageLayout.FILE_SUFFIX}", 30)),
            )

            sync()

            verify(minio, never()).removeObject(any<RemoveObjectArgs>())
        }

        /** An unreadable listing says nothing about what is referenced, so it must delete nothing. */
        @Test
        fun `a bucket that cannot be listed costs no deletion`() {
            writePackage("demo", "1.4.0")
            `when`(minio.listObjects(any<ListObjectsArgs>())).thenThrow(RuntimeException("connection refused"))

            sync()

            verify(minio, never()).removeObject(any<RemoveObjectArgs>())
        }

        /** One unreadable entry is that entry's bad luck, not a reason to leave the whole bucket unclaimed. */
        @Test
        fun `an entry whose metadata cannot be read is kept while the rest is still reclaimed`() {
            writePackage("demo", "1.4.0")
            `when`(cliMapper.selectByName("demo")).thenReturn(existingRow(packageDigest = LIVE_DIGEST))
            referenced(packageKey(LIVE_DIGEST))
            bucket(
                Result(RuntimeException("truncated listing")),
                Result(stored(packageKey(ORPHAN_DIGEST), 30)),
            )

            sync()

            assertEquals(listOf(packageKey(ORPHAN_DIGEST)), capturedDeletions())
        }
    }

    @Nested
    inner class MissingInfrastructure {
        @Test
        fun `packages with no object store fail startup instead of running with no cli`() {
            writePackage("demo", "1.4.0")
            `when`(minioClients.getIfAvailable()).thenReturn(null)

            val error = assertThrows(IllegalStateException::class.java) { sync() }
            assertTrue("MinIO is not enabled" in error.message.orEmpty(), error.message)
            verify(cliMapper, never()).upsertCliPackage(any())
        }

        @Test
        fun `no configured directory registers nothing and prunes nothing`() {
            registrarAt(File("")).syncCliPackages()

            verify(minio, never()).putObject(any<PutObjectArgs>())
            verify(cliMapper, never()).upsertCliPackage(any())
            verify(cliMapper, never()).selectAll()
        }

        @Test
        fun `a configured directory that is absent is treated as an unmounted volume`() {
            val absent = File(packageDir.parentFile, "not-mounted")
            `when`(cliMapper.selectAll()).thenReturn(listOf(staleRow(1L, "demo", 11L)))

            registrarAt(absent).syncCliPackages()

            verify(cliMapper, never()).upsertCliPackage(any())
            verify(cliMapper, never()).deleteByIds(any())
        }

        @Test
        fun `an empty directory attempts the prune and is stopped by the size brake`() {
            `when`(cliMapper.selectAll()).thenReturn(
                listOf(staleRow(1L, "demo", 11L), staleRow(2L, "other", 12L)),
            )
            sync()

            verify(cliMapper).selectAll()
            verify(cliMapper, never()).deleteByIds(any())
        }
    }
}

private data class PackageEntry(
    val name: String,
    val bytes: ByteArray,
    val mode: Int?,
)

private const val REPO_ID = 7L
private const val SKILL_ID = 99L
private const val BUCKET = "harnax-cli-packages"
private const val ARCHIVE_RETENTION_DAYS = 7L
private val LIVE_DIGEST = "a".repeat(64)
private val ORPHAN_DIGEST = "b".repeat(64)
private const val ENVPARAMS_JSON = "[{\"envParamName\":\"DEMO_PROFILE\"}]"
private val STALE_DIGEST = "e".repeat(64)
private const val S_IFREG = 0x8000
private const val FILE_MODE = S_IFREG or 0b110_100_100
private const val EXEC_MODE = S_IFREG or 0b111_101_101
private val TIME = FileTime.fromMillis(1_700_000_000_000L)
