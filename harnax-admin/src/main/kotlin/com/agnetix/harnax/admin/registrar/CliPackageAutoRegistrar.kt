package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.skill.SkillContentScanner
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
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * The single lifecycle entry point for CLIs: a package that is in the directory is registered, one that
 * left it is pruned. No page and no API ever writes the `cli` table (design D2).
 *
 * Each `.harnaxcli.zip` becomes one `cli` row plus the `skill` row it ships, and its archive goes to
 * MinIO under its own digest. Convergence strategy, run on every startup:
 *
 * - New package: object uploaded, skill row inserted into the managed repository, `cli` row inserted
 *   with `status = 1`. The two rows commit together, so a package that fails halfway leaves no orphan
 *   skill row that nothing points at and nothing prunes.
 * - Package already registered: every manifest-owned column is overwritten, so a bumped version or a
 *   reworded `SKILL.md` converges in place and keeps the row id — and with it `agent_cli_binding`.
 *   The archive is re-uploaded only when `packageDigest` changed (D15): an unchanged package costs one
 *   indexed read.
 * - Package gone from the directory: its `cli` row is hard-deleted and its shipped skill is logically
 *   deleted with the bindings ([pruneMissingPackages]) — `active = 0`, the convention every skill delete
 *   uses, so a re-registered package gets a fresh skill row rather than the old one. Only rows the
 *   registrar itself wrote are candidates, so a leftover of the retired CLI page survives here and is
 *   retired by V38 instead.
 * - Two packages declaring one name: the higher manifest version wins, because an upgrade that left the
 *   old file on the shelf must not depend on the directory listing order. Same name, same version, two
 *   files: neither is registered, since nothing says which one the operator means.
 *
 * Three behaviours are deliberate and easy to break from the outside:
 *
 * - `status` is never overwritten. It is the operator's kill switch (D9), and resetting it on restart
 *   would re-arm a disabled CLI the next time the container bounces. The shipped skill follows it (I5).
 * - One broken package is one ERROR line and no more: it does not stop the others, and it blocks the
 *   prune of the whole run, because a run that could not read every package has no evidence about
 *   which rows are stale.
 * - A missing object store is *not* swallowed the same way. Packages that cannot be stored are a
 *   deployment mistake, so startup fails with it spelled out (D13) rather than the platform silently
 *   running with no CLIs at all.
 */
@Component
class CliPackageAutoRegistrar(
    private val cliMapper: CliMapper,
    private val skillMapper: SkillMapper,
    private val skillRepositoryMapper: SkillRepositoryMapper,
    private val agentCliBindingMapper: AgentCliBindingMapper,
    private val agentSkillBindingMapper: AgentSkillBindingMapper,
    private val teamSkillBindingMapper: TeamSkillBindingMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
    private val minioClients: ObjectProvider<MinioClient>,
    transactionManager: PlatformTransactionManager,
    @Value("\${harnax.cli.package-dir:}") packageDir: String,
    @Value("\${minio.cli-package-bucket:harnax-cli-packages}") private val cliPackageBucket: String,
    @Value("\${harnax.cli.archive-retention-days:7}") private val archiveRetentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(CliPackageAutoRegistrar::class.java)
    private val objectMapper = ObjectMapper()
    private val dir = File(packageDir)

    // `register` and `pruneMissingPackages` are private members this class calls itself, so an
    // `@Transactional` annotation on them would never be seen by the proxy.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @EventListener(ApplicationReadyEvent::class)
    fun syncCliPackages() {
        if (dir.path.isBlank()) {
            log.info("[CliPackageAutoRegistrar] No package directory configured, nothing to register")
            return
        }
        if (!dir.isDirectory) {
            // Configured but absent means the volume is not mounted. Registering nothing and pruning
            // everything would be a far worse answer than saying so and leaving the rows alone.
            log.error(
                "[CliPackageAutoRegistrar] Package directory {} does not exist — no package registered and no row pruned",
                dir,
            )
            return
        }

        val packages = dir.listFiles { file: File -> file.isFile && file.name.endsWith(CliPackageLayout.FILE_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (packages.isEmpty()) {
            log.warn("[CliPackageAutoRegistrar] Package directory {} holds no {} file", dir, CliPackageLayout.FILE_SUFFIX)
        } else if (minioClients.ifAvailable == null) {
            throw IllegalStateException(
                "${packages.size} CLI package(s) in $dir but MinIO is not enabled — set minio.enabled=true " +
                    "and the minio.* connection properties, or empty the package directory",
            )
        }

        val registered = mutableSetOf<String>()
        var failCount = 0
        // Every package is parsed before any is registered, because two files may declare the same name —
        // an upgrade that left the old package on the shelf — and then the winner has to be the newer
        // manifest version rather than wherever the directory listing happened to put the two files.
        val parsed = mutableListOf<Pair<File, ParsedCliPackage>>()
        for (file in packages) {
            try {
                parsed += file to CliPackageParser.parse(file)
            } catch (e: Exception) {
                failCount++
                log.error("[CliPackageAutoRegistrar] Failed to register package {}: {}", file.name, e.message, e)
            }
        }

        for ((name, group) in parsed.groupBy { it.second.manifest.name }) {
            if (group.size == 1) {
                if (!registerOne(group.single(), registered)) failCount++
                continue
            }
            val top = group.maxWith { a, b -> compareVersions(a.second.manifest.version, b.second.manifest.version) }
            val tied = group.filter { compareVersions(it.second.manifest.version, top.second.manifest.version) == 0 }
            if (tied.size > 1) {
                // Same name, same version, different files: nothing here says which one the operator
                // means, so neither is registered and the run says so. Registering neither keeps the
                // name out of `registered`, and `failCount` then blocks the prune — the row that is
                // already live for this name survives the ambiguity instead of being read as retired.
                failCount++
                log.error(
                    "[CliPackageAutoRegistrar] Name '{}' is declared by {} packages at version {} ({}) — registering neither",
                    name,
                    tied.size,
                    top.second.manifest.version,
                    tied.joinToString(", ") { it.first.name },
                )
                continue
            }
            log.error(
                "[CliPackageAutoRegistrar] Name '{}' is declared by {} packages; registering {} (version {}) and skipping {}",
                name,
                group.size,
                tied.single().first.name,
                top.second.manifest.version,
                group.filter { it !== tied.single() }.joinToString(", ") { "${it.first.name} (${it.second.manifest.version})" },
            )
            if (!registerOne(tied.single(), registered)) failCount++
        }
        log.info(
            "[CliPackageAutoRegistrar] Sync complete: {} registered, {} failed",
            registered.size,
            failCount,
        )

        pruneMissingPackages(registered, failCount)
        reclaimOrphanArchives()
    }

    /**
     * Registers one parsed package and records its name as live.
     *
     * A failure costs one ERROR line and one more `failCount`, nothing else: the other packages still
     * register, and the run still converges as far as it could read.
     */
    private fun registerOne(
        entry: Pair<File, ParsedCliPackage>,
        registered: MutableSet<String>,
    ): Boolean = try {
        registered += register(entry.first, entry.second)
        true
    } catch (e: Exception) {
        log.error("[CliPackageAutoRegistrar] Failed to register package {}: {}", entry.first.name, e.message, e)
        false
    }

    /** Registers one package, answering with the name it now owns. */
    private fun register(
        file: File,
        parsed: ParsedCliPackage,
    ): String {
        val manifest = parsed.manifest
        val existing = cliMapper.selectByName(manifest.name)
        val objectKey = "${manifest.name}/${parsed.packageDigest}${CliPackageLayout.FILE_SUFFIX}"
        val stored = existing != null &&
            existing.packageDigest == parsed.packageDigest &&
            existing.packageObject == objectKey
        if (stored) {
            log.debug("[CliPackageAutoRegistrar] {} unchanged ({}), skipping upload", manifest.name, parsed.packageDigest)
        } else {
            // Before the rows, deliberately. Uploaded afterwards it could fail while the committed
            // `cli` row already names the object key — and `stored` above would then read that row as
            // done, so the missing archive would never be re-tried. A stray object from a failed run
            // costs disk; a row pointing at nothing costs every agent bound to that CLI.
            upload(file, objectKey)
        }

        transactionTemplate.executeWithoutResult {
            val repository = skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)
                ?: throw CliPackageException(
                    "managed skill repository '${BuiltinRepository.CLI_SKILLS}' is missing — cannot register the shipped skill",
                )
            // The operator's kill switch survives this run: a disabled CLI keeps its skill disabled even
            // though the package body just changed underneath it (I5). Read under the row's lock rather than
            // from `existing` above — `toggleCliStatus` writes that same column, and the locked read makes
            // it block until this transaction commits, so the `cli` row and its skill cannot disagree.
            val locked = cliMapper.selectByNameForUpdate(manifest.name)
            val skillId = upsertSkill(repository, manifest.name, manifest.version, parsed, locked?.status ?: ENABLED)

            cliMapper.upsertCliPackage(
                Cli().apply {
                    name = manifest.name
                    description = manifest.description
                    version = manifest.version
                    checkCommand = manifest.checkCommand
                    this.skillId = skillId
                    packageDigest = parsed.packageDigest
                    payloadDigest = parsed.payloadDigest
                    packageObject = objectKey
                    depsApt = manifest.depsApt.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }
                    runtimeEnv = manifest.runtimeEnv.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }
                    envParams = secretFieldEncryptor.serializeToolEnvParams(manifest.envParams)
                },
            )
        }
        log.info(
            "[CliPackageAutoRegistrar] Registered CLI package {} {} (payload {}, {})",
            manifest.name,
            manifest.version,
            parsed.payloadDigest.take(12),
            if (stored) "unchanged" else "uploaded",
        )
        return manifest.name
    }

    /**
     * Upserts the skill the package ships, under the package name (I6).
     *
     * The content scanner is the same one the GIT/NPM/ZIP loaders run: a package is as untrusted as a
     * third-party source, and its `SKILL.md` reaches every bound agent's prompt. A hit stores the row
     * disabled with an ERROR naming the rule, which is also the only feedback channel this path has.
     */
    private fun upsertSkill(
        repository: SkillRepository,
        name: String,
        version: String,
        parsed: ParsedCliPackage,
        cliStatus: Int,
    ): Long {
        val findings = SkillContentScanner.scan(parsed.skillMd, parsed.skillAssets)
        if (findings.isNotEmpty()) {
            log.error(
                "[CliPackageAutoRegistrar] Skill {} was stored disabled by the content scan: {}",
                name,
                findings.joinToString("; ") { "${it.resource}: ${it.reason}" },
            )
        }
        val desiredStatus = if (findings.isEmpty()) cliStatus else DISABLED
        val resourcesJson = objectMapper.writeValueAsString(parsed.skillAssets)

        val existing = skillMapper.selectByNameAndRepo(name, repository.id)
        if (existing == null) {
            val skill = Skill().apply {
                tenantId = repository.tenantId
                this.name = name
                repositoryId = repository.id
                description = parsed.skillDescription
                skillmd = parsed.skillMd
                resources = resourcesJson
                this.version = version
                status = desiredStatus
                isPublic = PUBLIC
                creator = SYSTEM_CREATOR
                active = 1
            }
            skillMapper.insert(skill)
            return skill.id
        }

        existing.description = parsed.skillDescription
        existing.skillmd = parsed.skillMd
        existing.resources = resourcesJson
        existing.version = version
        skillMapper.updateById(existing)
        // updateById deliberately leaves status alone, so the status follow-up needs its own statement
        if (existing.status != desiredStatus) {
            skillMapper.updateStatus(existing.id, desiredStatus)
        }
        return existing.id
    }

    private fun upload(
        file: File,
        objectKey: String,
    ) {
        val client = minioClients.ifAvailable
            ?: throw IllegalStateException("MinIO is not enabled, so package ${file.name} cannot be stored")
        val exists = client.bucketExists(BucketExistsArgs.builder().bucket(cliPackageBucket).build())
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(cliPackageBucket).build())
        }
        client.putObject(
            PutObjectArgs.builder()
                .bucket(cliPackageBucket)
                .`object`(objectKey)
                .stream(file.inputStream(), file.length(), -1L)
                .contentType(CONTENT_TYPE)
                .build(),
        )
        log.info("[CliPackageAutoRegistrar] Stored {} ({} bytes) as {}/{}", file.name, file.length(), cliPackageBucket, objectKey)
    }

    /**
     * Removes rows for packages the directory no longer holds, with their shipped skills and bindings.
     *
     * Three brakes, because this is the only place the platform deletes a CLI an agent may still be
     * configured with: a run that failed to read any package has no evidence to prune on; the registrar
     * only ever deletes a row it wrote itself, which an empty `packageDigest` proves it did not (rows
     * created by the retired CLI page are retired by V38, not pruned here); and a stale set as large as
     * the live set reads far more like an unmounted volume than like the operator retiring half the
     * platform, so it is refused with an ERROR instead of executed.
     */
    private fun pruneMissingPackages(
        registered: Set<String>,
        failCount: Int,
    ) {
        if (failCount > 0) {
            log.error(
                "[CliPackageAutoRegistrar] Skipping prune: {} package(s) failed to register, so the live set is not trustworthy",
                failCount,
            )
            return
        }
        val rows = cliMapper.selectAll()
        // Only rows a package wrote are candidates, and only among them is the ratio below meaningful:
        // leftovers of the retired CLI page would otherwise count as "live" and talk an empty directory
        // out of the brake that exists to catch one.
        val owned = rows.filter { it.packageDigest.isNotEmpty() }
        val stale = owned.filter { it.name !in registered }
        if (stale.isEmpty()) {
            return
        }
        val live = owned.size - stale.size
        if (stale.size >= live) {
            log.error(
                "[CliPackageAutoRegistrar] Skipping prune: {} row(s) not in the directory vs only {} registered — " +
                    "this looks like a missing package directory, not retired packages. Stale: {}",
                stale.size,
                live,
                stale.map { "${it.name}(id=${it.id})" },
            )
            return
        }

        val cliIds = stale.map { it.id }
        val skillIds = stale.mapNotNull { it.skillId }
        transactionTemplate.executeWithoutResult {
            agentCliBindingMapper.deleteByCliIds(cliIds)
            if (skillIds.isNotEmpty()) {
                agentSkillBindingMapper.deleteBySkillIds(skillIds)
                teamSkillBindingMapper.deleteBySkillIds(skillIds)
                skillIds.forEach { skillMapper.deleteById(it) }
            }
            cliMapper.deleteByIds(cliIds)
        }
        removeArchivesOf(stale)
        log.warn(
            "[CliPackageAutoRegistrar] Removed {} CLI row(s) whose package left the directory: {}",
            stale.size,
            stale.map { "${it.name}(id=${it.id})" },
        )
    }

    /**
     * Reclaims the archives no `cli` row names any more, i.e. what an in-place upgrade left behind.
     *
     * [removeArchivesOf] only sees a key when the row naming it is deleted, so a package that stayed and
     * merely changed digest left its previous object in the bucket forever: nothing referenced it, and
     * nothing else looked at it. This is the third and last accumulating artifact of CLI-04 — the runtime
     * reclaims the payload tree and the sandbox image for the same selection, and each side can only see
     * its own storage.
     *
     * Two things make a deletion here safe. The key has to be one this class writes (`<name>/<sha256>.harnaxcli.zip`),
     * so an object the operator put in the bucket by hand is out of reach; and the object has to be older
     * than [archiveRetentionDays], because a row that stopped naming a key seconds ago does not mean no
     * running agent-service is still downloading it — the row is admin's view, not the fleet's.
     *
     * The listing is read in full before anything is deleted, so an object store that cannot answer costs
     * no bytes at all. An entry whose own metadata cannot be read is kept on its own, since that is one bad
     * listing row rather than a reason to leave the whole bucket unreclaimed.
     */
    private fun reclaimOrphanArchives() {
        val client = minioClients.ifAvailable ?: return
        val listing = try {
            client.listObjects(ListObjectsArgs.builder().bucket(cliPackageBucket).recursive(true).build()).toList()
        } catch (e: Exception) {
            log.warn("[CliPackageAutoRegistrar] Keeping every stored archive: {} could not be listed ({})", cliPackageBucket, e.message)
            return
        }
        val referenced = cliMapper.selectPackageObjects().toSet()
        val cutoff = Instant.now().minus(Duration.ofDays(archiveRetentionDays))
        var removed = 0
        for (entry in listing) {
            val item = try {
                entry.get()
            } catch (e: Exception) {
                log.warn("[CliPackageAutoRegistrar] Keeping an archive whose listing entry could not be read: {}", e.message)
                continue
            }
            val objectName = item.objectName()
            val written = item.lastModified() ?: continue
            if (!isPackageKey(objectName) || objectName in referenced) continue
            if (written.toInstant().isAfter(cutoff)) continue
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(cliPackageBucket).`object`(objectName).build())
                removed++
                log.info("[CliPackageAutoRegistrar] Reclaimed stored archive {}", objectName)
            } catch (e: Exception) {
                log.warn("[CliPackageAutoRegistrar] Stored archive {} could not be removed: {}", objectName, e.message)
            }
        }
        if (removed > 0) {
            log.info("[CliPackageAutoRegistrar] Reclaimed {} unreferenced CLI archive(s) from {}", removed, cliPackageBucket)
        }
    }

    /**
     * Whether one object key is a package archive this class wrote.
     *
     * [CliPackageLayout.NAME_PATTERN] and [CliPackageLayout.DIGEST_PATTERN] are the same two patterns the
     * upload path's key is built from, so this admits nothing the registrar could not have produced.
     */
    private fun isPackageKey(key: String): Boolean {
        if (!key.endsWith(CliPackageLayout.FILE_SUFFIX)) return false
        val slash = key.lastIndexOf('/')
        if (slash <= 0) return false
        val digest = key.substring(slash + 1).removeSuffix(CliPackageLayout.FILE_SUFFIX)
        return CliPackageLayout.NAME_PATTERN.matches(key.substring(0, slash)) && CliPackageLayout.DIGEST_PATTERN.matches(digest)
    }

    /**
     * Deletes the stored archives of just-pruned rows.
     *
     * Runs after the commit and never inside the transaction: an object store cannot roll back, so a
     * prune whose deletes were undone has to leave every archive alone. Re-reading `cli` for what is
     * still referenced is defensive — `uk_cli_name` means no two rows can name the same key today.
     *
     * What this does *not* reclaim is the archive an in-place upgrade left behind when only the digest
     * changed: that row is still live, and nothing on admin's side knows whether a running agent-service
     * is still fetching the previous key. Reclaiming it needs the runtime reference contract, which is
     * CLI-04.
     *
     * Best effort in both directions — a refused delete is logged and the pruned rows stay pruned, since
     * a leftover archive costs disk while an aborted prune costs the operator the kill switch they asked
     * for. (The object store is always present by the time this runs: a directory holding packages with
     * no MinIO fails startup before the prune is reached.)
     */
    private fun removeArchivesOf(pruned: List<Cli>) {
        if (pruned.isEmpty()) return
        val client = minioClients.ifAvailable ?: return
        val stillReferenced = cliMapper.selectAll().map { it.packageObject }.toSet()
        for (row in pruned) {
            val key = row.packageObject
            if (key.isBlank() || key in stillReferenced) continue
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(cliPackageBucket).`object`(key).build())
                log.info("[CliPackageAutoRegistrar] Removed stored archive {}", key)
            } catch (e: Exception) {
                log.warn("[CliPackageAutoRegistrar] Stored archive {} could not be removed: {}", key, e.message)
            }
        }
    }

    /**
     * Compares two manifest versions, negative when [a] is the older one.
     *
     * Segment-wise, numbers compared as numbers, so `1.0.10` is newer than `1.0.9` — which a string
     * compare gets backwards, and the loser of that comparison is a package that quietly never installs.
     * A pre-release suffix is only another segment here, so `1.0.0-rc1` outranks `1.0.0`; that inversion
     * is acceptable because both files declare the same name and the only question is which one wins.
     */
    private fun compareVersions(
        a: String,
        b: String,
    ): Int {
        val left = a.split(VERSION_SEPARATOR)
        val right = b.split(VERSION_SEPARATOR)
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(index) ?: return -1
            val r = right.getOrNull(index) ?: return 1
            val numeric = l.toLongOrNull()
            val other = r.toLongOrNull()
            val order = if (numeric != null && other != null) numeric.compareTo(other) else l.compareTo(r)
            if (order != 0) return order
        }
        return 0
    }

    companion object {
        private const val ENABLED = 1
        private const val DISABLED = 0
        private const val PUBLIC = 1
        private const val SYSTEM_CREATOR = "SYSTEM"
        private const val CONTENT_TYPE = "application/zip"

        /** Manifest versions are already pattern-checked by the parser; this only splits them. */
        private val VERSION_SEPARATOR = Regex("[._+-]")
    }
}
