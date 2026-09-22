package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.skill.loader.SkillFileParser
import com.agnetix.harnax.common.cli.CliPackageArchive
import com.agnetix.harnax.common.cli.CliPackageEntry
import com.agnetix.harnax.common.cli.CliPackageLayout
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

/** A package that will not be registered, with every reason it was refused already spelled out. */
class CliPackageException(message: String) : RuntimeException(message)

/**
 * The `plugin.yaml` of a package, as the platform reads it.
 *
 * [envParams] and [runtimeEnv] are kept typed rather than pre-serialised: the columns they land in go
 * through the secret encryptor on the way to the database, which is a Spring bean and has no business
 * inside a parser that is supposed to be callable from a plain unit test.
 */
data class CliPackageManifest(
    val name: String,
    val version: String,
    val description: String,
    val checkCommand: String,
    val depsApt: List<String> = emptyList(),
    val envParams: List<ToolEnvParamEntry> = emptyList(),
    val runtimeEnv: Map<String, String> = emptyMap(),
)

/**
 * A package that passed validation, with everything the registrar needs to write its two rows and
 * nothing it would have to derive again.
 *
 * [payloadFiles] is the list the image build later re-derives from the stored object; carrying it
 * here keeps the digest that named the image and the tree that goes into it the result of one pass.
 */
data class ParsedCliPackage(
    val manifest: CliPackageManifest,
    val packageDigest: String,
    val payloadDigest: String,
    val skillMd: String,
    val skillDescription: String,
    val skillAssets: Map<String, String>,
    val payloadFiles: List<CliPackageEntry>,
)

/**
 * Turns a `.harnaxcli.zip` on disk into a [ParsedCliPackage].
 *
 * Pure by design (spec §3.3): no database, no object store, no process spawning. Nothing inside the
 * archive is ever executed or handed to a shell — the parser only reads bytes out of the zip, which is
 * what invariant I4 buys us: registering a package cannot run anything from it, not even a badly
 * behaved `checkCommand`.
 *
 * Every rule is enforced *here* rather than at image-build time, because a package that will produce a
 * broken sandbox is far cheaper to refuse on startup: the operator sees one ERROR line naming the file
 * instead of a session that fails minutes later with an exit code.
 */
object CliPackageParser {
    /** Manifest beyond this size is a mistake, not a CLI description. */
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_SKILL_BYTES = 1024 * 1024

    /** Mirrors the per-resource and per-skill budgets the skill loaders already apply. */
    private const val MAX_ASSET_BYTES = 512 * 1024
    private const val MAX_SKILL_ASSET_BYTES = 4 * 1024 * 1024
    private const val MAX_APT_DEPS = 50

    private val ENV_NAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    private val PLACEHOLDER_PATTERN = Regex("^\\$\\{([A-Za-z0-9._-]+)\\}$")

    /** The only platform values a package may ask for (design D7). */
    private val SUPPORTED_PLATFORM_SLOTS = setOf("platform.adminUrl", "platform.internalToken")

    private val KNOWN_MANIFEST_KEYS = setOf(
        "name",
        "version",
        "description",
        "checkCommand",
        "deps",
        "envParams",
        "runtimeEnv",
    )

    fun parse(file: File): ParsedCliPackage {
        val issues = mutableListOf<String>()
        checkFileName(file, issues)

        val archive = try {
            CliPackageArchive.open(file)
        } catch (e: Exception) {
            throw CliPackageException("${file.name}: unreadable package (${e.message})")
        }
        return archive.use {
            parse(it, file, issues)
        }
    }

    private fun parse(
        archive: CliPackageArchive,
        file: File,
        issues: MutableList<String>,
    ): ParsedCliPackage {
        val manifest = readManifest(archive, issues)
        val declaredName = manifest?.getString("name")?.takeIf { it.isNotBlank() }
        val stem = file.name.removeSuffix(CliPackageLayout.FILE_SUFFIX)
        if (manifest != null && declaredName != null && !stem.startsWith("$declaredName-")) {
            issues += "file name \"${file.name}\" does not start with the declared name plus a version, " +
                "as in \"$declaredName-1.4.0${CliPackageLayout.FILE_SUFFIX}\" — rename the file, the declared name is the identity"
        }

        val parsedManifest = manifest?.let { toManifest(it, declaredName, issues) }

        val skillMd = readSkill(archive, parsedManifest?.name, issues)
        val assets = readSkillAssets(archive, issues)
        val payloadFiles = readPayload(archive, issues)
        checkLayout(archive, issues)

        if (issues.isNotEmpty()) {
            throw CliPackageException(
                "${file.name}: refused with ${issues.size} problem(s):\n  - " + issues.joinToString("\n  - "),
            )
        }
        val result = parsedManifest
            ?: throw CliPackageException("${file.name}: plugin.yaml is missing")

        return ParsedCliPackage(
            manifest = result,
            packageDigest = CliPackageLayout.packageDigest(file),
            payloadDigest = CliPackageLayout.payloadDigest(archive, result.depsApt),
            skillMd = requireNotNull(skillMd),
            skillDescription = SkillFileParser.parseMeta(requireNotNull(skillMd), result.name).description,
            skillAssets = assets,
            payloadFiles = payloadFiles,
        )
    }

    // ==================== file name ====================

    private fun checkFileName(
        file: File,
        issues: MutableList<String>,
    ) {
        if (!file.name.endsWith(CliPackageLayout.FILE_SUFFIX)) {
            issues += "file name must end with ${CliPackageLayout.FILE_SUFFIX}"
            return
        }
        val stem = file.name.removeSuffix(CliPackageLayout.FILE_SUFFIX)
        val separator = stem.lastIndexOf('-')
        if (separator <= 0 || separator == stem.length - 1) {
            issues += "file name must be <name>-<version>${CliPackageLayout.FILE_SUFFIX}, got \"${file.name}\""
        }
    }

    // ==================== plugin.yaml ====================

    private fun readManifest(
        archive: CliPackageArchive,
        issues: MutableList<String>,
    ): Map<*, *>? {
        if (!archive.has(CliPackageLayout.MANIFEST_ENTRY)) {
            issues += "no ${CliPackageLayout.MANIFEST_ENTRY} at the archive root — a package without a manifest cannot be registered"
            return null
        }
        val bytes = archive.readBounded(CliPackageLayout.MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
        if (bytes == null) {
            issues += "${CliPackageLayout.MANIFEST_ENTRY} is over the $MAX_MANIFEST_BYTES limit — " +
                "the manifest describes a CLI, it is not where a package parks data"
            return null
        }
        val text = String(bytes, Charsets.UTF_8)
        return try {
            val loaded = yaml().load<Any?>(text)
            if (loaded == null) {
                issues += "${CliPackageLayout.MANIFEST_ENTRY} is empty"
                null
            } else if (loaded !is Map<*, *>) {
                issues += "${CliPackageLayout.MANIFEST_ENTRY} must be a YAML mapping, found ${loaded.javaClass.simpleName}"
                null
            } else {
                loaded
            }
        } catch (e: Exception) {
            issues += "${CliPackageLayout.MANIFEST_ENTRY} is not valid YAML: ${yamlFailure(e)}"
            null
        }
    }

    /**
     * The line of a YAML failure an author can act on.
     *
     * SnakeYAML formats the context and the problem on separate lines, and the first one is only
     * "while constructing a mapping" — which describes nothing, and in particular does not say that a
     * duplicate key is what was rejected.
     */
    private fun yamlFailure(e: Exception): String {
        val problem = generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<org.yaml.snakeyaml.error.MarkedYAMLException>()
            .firstOrNull()
            ?.problem
        return problem ?: e.message?.lineSequence()?.first() ?: e.javaClass.simpleName
    }

    /**
     * Safe loader: no unknown tags become objects, duplicate keys are an error rather than a silent
     * last-wins, and the depth/size caps stop a pathological manifest from costing more than it is worth.
     */
    private fun yaml(): Yaml {
        val options = LoaderOptions().apply {
            codePointLimit = MAX_MANIFEST_BYTES
            nestingDepthLimit = 20
            setAllowDuplicateKeys(false)
        }
        return Yaml(SafeConstructor(options))
    }

    private fun toManifest(
        raw: Map<*, *>,
        declaredName: String?,
        issues: MutableList<String>,
    ): CliPackageManifest? {
        raw.keys.mapNotNull { it as? String }.toSet().let { keys ->
            val unknown = keys - KNOWN_MANIFEST_KEYS
            if (unknown.isNotEmpty()) {
                issues += "unknown plugin.yaml key(s) ${unknown.sorted()}: the manifest is the only metadata source, " +
                    "so an unrecognised key is a typo that would otherwise be dropped"
            }
        }

        val name = declaredName ?: run {
            issues += "plugin.yaml: name is required"
            null
        }
        if (name != null && !CliPackageLayout.NAME_PATTERN.matches(name)) {
            issues += "name \"$name\" must match ${CliPackageLayout.NAME_PATTERN.pattern} " +
                "(lowercase letters, digits and dashes; it is also the skill name and the file-name stem)"
        }
        val version = raw.getString("version").takeUnless { it.isNullOrBlank() } ?: run {
            issues += "plugin.yaml: version is required"
            null
        }
        if (version != null && !CliPackageLayout.VERSION_PATTERN.matches(version)) {
            issues += "version \"$version\" must match ${CliPackageLayout.VERSION_PATTERN.pattern}"
        }
        val description = raw.getString("description").takeUnless { it.isNullOrBlank() } ?: run {
            issues += "plugin.yaml: description is required"
            null
        }
        val checkCommand = raw.getString("checkCommand").takeUnless { it.isNullOrBlank() } ?: run {
            issues += "plugin.yaml: checkCommand is required"
            null
        }

        val deps = readDeps(raw, issues)
        val envParams = readEnvParams(raw, issues)
        val runtimeEnv = readRuntimeEnv(raw, issues)

        if (name == null || version == null || description == null || checkCommand == null) return null
        return CliPackageManifest(
            name = name,
            version = version,
            description = description,
            checkCommand = checkCommand,
            depsApt = deps,
            envParams = envParams,
            runtimeEnv = runtimeEnv,
        )
    }

    private fun readDeps(
        raw: Map<*, *>,
        issues: MutableList<String>,
    ): List<String> {
        val deps = raw["deps"] as? Map<*, *> ?: return emptyList()
        (deps.keys.mapNotNull { it as? String } - "apt").forEach { key ->
            issues += "plugin.yaml deps has an unsupported section \"$key\" — only deps.apt exists"
        }
        val list = deps["apt"] as? List<*> ?: return emptyList()
        val packages = list.mapNotNull { it as? String }
        if (packages.size != list.size) {
            issues += "plugin.yaml deps.apt must be a list of package names"
        }
        if (packages.size > MAX_APT_DEPS) {
            issues += "plugin.yaml deps.apt lists ${packages.size} packages, over the $MAX_APT_DEPS limit"
        }
        packages.filterNot { CliPackageLayout.APT_PACKAGE_PATTERN.matches(it) }.forEach {
            issues += "plugin.yaml deps.apt entry \"$it\" is not a plain apt package name — " +
                "it is interpolated into an apt-get command line, so only [a-z0-9+.-] and an optional =version are allowed"
        }
        val duplicates = packages.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            issues += "plugin.yaml deps.apt lists ${duplicates.sorted()} more than once"
        }
        return packages.distinct()
    }

    private fun readEnvParams(
        raw: Map<*, *>,
        issues: MutableList<String>,
    ): List<ToolEnvParamEntry> {
        val list = raw["envParams"] as? List<*> ?: return emptyList()
        val entries = list.mapNotNull { item ->
            val map = item as? Map<*, *>
            if (map == null) {
                issues += "plugin.yaml envParams entries must be mappings, found $item"
                return@mapNotNull null
            }
            val paramName = map.getString("envParamName")
            if (paramName.isNullOrBlank()) {
                issues += "plugin.yaml envParams entry is missing envParamName"
                return@mapNotNull null
            }
            if (!ENV_NAME_PATTERN.matches(paramName)) {
                issues += "plugin.yaml envParams name \"$paramName\" must match ${ENV_NAME_PATTERN.pattern}"
                return@mapNotNull null
            }
            val secret = map.getBoolean("secret")
            val defaultValue = map.getString("defaultValue")
            if (secret && !defaultValue.isNullOrBlank()) {
                issues += "plugin.yaml envParams \"$paramName\" is marked secret and carries a defaultValue — " +
                    "a package is distributed as a plain zip, so credentials belong in env-var bindings, not here"
            }
            ToolEnvParamEntry(
                envParamName = paramName,
                description = map.getString("description")?.takeUnless { it.isNullOrBlank() },
                required = map.getBoolean("required"),
                secret = secret,
                defaultValue = if (secret) null else defaultValue,
            )
        }
        val duplicates = entries.map { it.envParamName }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            issues += "plugin.yaml envParams declares ${duplicates.sorted()} more than once"
        }
        return entries
    }

    private fun readRuntimeEnv(
        raw: Map<*, *>,
        issues: MutableList<String>,
    ): Map<String, String> {
        val map = raw["runtimeEnv"] as? Map<*, *> ?: return emptyMap()
        val resolved = LinkedHashMap<String, String>()
        map.forEach { (key, value) ->
            val name = key as? String
            if (name == null || !ENV_NAME_PATTERN.matches(name)) {
                issues += "plugin.yaml runtimeEnv key \"$key\" must match ${ENV_NAME_PATTERN.pattern}"
                return@forEach
            }
            if (value !is String) {
                issues += "plugin.yaml runtimeEnv $name must be a string, found $value"
                return@forEach
            }
            if (value.isBlank()) {
                issues += "plugin.yaml runtimeEnv $name has an empty value"
                return@forEach
            }
            val slot = PLACEHOLDER_PATTERN.matchEntire(value)?.groupValues?.get(1)
            when {
                slot == null -> {
                    if (value.contains("\${")) {
                        issues += "plugin.yaml runtimeEnv $name writes \"$value\" — a placeholder has to be the whole " +
                            "value, as in $name: \${platform.adminUrl}"
                    } else {
                        resolved[name] = value
                    }
                }

                slot !in SUPPORTED_PLATFORM_SLOTS ->
                    issues +=
                        "plugin.yaml runtimeEnv $name asks for $value, which the platform does not publish " +
                        "(supported: ${SUPPORTED_PLATFORM_SLOTS.sorted().joinToString(", ")})"

                else -> resolved[name] = value
            }
        }
        return resolved
    }

    // ==================== skill/ ====================

    /**
     * Reads `skill/SKILL.md` and enforces I6: the skill inside the package *is* the CLI's skill, so a
     * frontmatter `name` that disagrees with the plugin name would make one row answer to two identities.
     */
    private fun readSkill(
        archive: CliPackageArchive,
        pluginName: String?,
        issues: MutableList<String>,
    ): String? {
        if (!archive.has(CliPackageLayout.SKILL_ENTRY)) {
            issues += "no ${CliPackageLayout.SKILL_ENTRY} — a CLI ships its own skill, and one without it leaves the " +
                "agent with a binary it has been told nothing about"
            return null
        }
        val bytes = archive.readBounded(CliPackageLayout.SKILL_ENTRY, MAX_SKILL_BYTES)
        if (bytes == null) {
            issues += "${CliPackageLayout.SKILL_ENTRY} is over the $MAX_SKILL_BYTES limit — " +
                "a skill teaches one CLI, and past this size it is documentation that belongs next to the binary"
            return null
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.isBlank()) {
            issues += "${CliPackageLayout.SKILL_ENTRY} is empty"
            return null
        }
        if (pluginName != null) {
            val skillName = SkillFileParser.parseMeta(text, pluginName).name
            if (skillName != pluginName) {
                issues += "${CliPackageLayout.SKILL_ENTRY} declares name \"$skillName\" while plugin.yaml declares " +
                    "\"$pluginName\" — the skill takes the CLI's name, so remove the frontmatter name or fix it"
            }
        }
        return text
    }

    private fun readSkillAssets(
        archive: CliPackageArchive,
        issues: MutableList<String>,
    ): Map<String, String> {
        val assets = LinkedHashMap<String, String>()
        var total = 0L
        archive.entriesUnder(CliPackageLayout.SKILL_ASSET_PREFIX)
            .filterNot { it.directory }
            .forEach { entry ->
                val key = entry.archivePath.removePrefix(CliPackageLayout.SKILL_ASSET_PREFIX)
                CliPackageLayout.checkRelativePath(key)?.let { reason ->
                    issues += "$reason (entry ${entry.archivePath}) — a skill's resource keys are the paths they " +
                        "are written to, so one has to name exactly one location"
                    return@forEach
                }
                if (entry.size > MAX_ASSET_BYTES) {
                    issues += "${entry.archivePath} is ${entry.size} bytes, over the $MAX_ASSET_BYTES per-asset limit"
                    return@forEach
                }
                if (total + entry.size > MAX_SKILL_ASSET_BYTES) {
                    issues += "skill assets exceed the $MAX_SKILL_ASSET_BYTES byte budget (${entry.archivePath} is the one that tipped it)"
                    return@forEach
                }
                val bytes = archive.readBounded(entry.archivePath, MAX_ASSET_BYTES)
                    ?: run {
                        issues += "${entry.archivePath} ships more than the $MAX_ASSET_BYTES per-asset limit"
                        return@forEach
                    }
                val text = decodeUtf8(bytes)
                    ?: run {
                        issues += "${entry.archivePath} is not UTF-8 text — a skill's resources ship as text, binaries belong in payload/"
                        return@forEach
                    }
                assets[key] = text
                total += entry.size
            }
        return assets
    }

    // ==================== payload/ ====================

    private fun readPayload(
        archive: CliPackageArchive,
        issues: MutableList<String>,
    ): List<CliPackageEntry> {
        val files = archive.entriesUnder(CliPackageLayout.PAYLOAD_PREFIX).filterNot { it.directory }
        if (files.isEmpty()) {
            issues += "no files under ${CliPackageLayout.PAYLOAD_PREFIX} — the payload is what the image copies in, " +
                "a CLI with nothing to install does not need a package"
            return emptyList()
        }
        if (files.size > CliPackageLayout.MAX_PAYLOAD_FILES) {
            issues += "payload has ${files.size} files, over the ${CliPackageLayout.MAX_PAYLOAD_FILES} limit"
        }
        val totalBytes = archive.totalSizeUnder(CliPackageLayout.PAYLOAD_PREFIX)
        if (totalBytes > CliPackageLayout.MAX_PAYLOAD_BYTES) {
            issues += "payload unpacks to $totalBytes bytes, over the ${CliPackageLayout.MAX_PAYLOAD_BYTES} limit"
        }
        files.forEach { entry ->
            val relative = entry.archivePath.removePrefix(CliPackageLayout.PAYLOAD_PREFIX)
            CliPackageLayout.checkPayloadPath(relative)?.let { reason ->
                issues += "$reason (entry ${entry.archivePath})"
            }
            if (entry.isSymbolicLink) {
                issues += "${entry.archivePath} is a symbolic link — extraction writes file content, never links, so it " +
                    "would arrive as a text file holding a path"
            }
            if (entry.hasSpecialPermissionBits) {
                issues += "${entry.archivePath} carries setuid/setgid/sticky bits (mode ${Integer.toOctalString(entry.mode)})"
            }
        }
        checkStoredModes(archive, files, issues)
        return files
    }

    /**
     * A package whose payload modes came from a non-UNIX packer is refused rather than defaulted.
     *
     * `java.util.zip` never saw the bits, so anything not recorded in the central directory would be
     * written as 0644 and every packaged binary would lose its execute bit — which surfaces as the
     * check command exiting 126, a symptom that reads like "wrong command" and not like "lost mode".
     */
    private fun checkStoredModes(
        archive: CliPackageArchive,
        files: List<CliPackageEntry>,
        issues: MutableList<String>,
    ) {
        val offenders = archive.entriesWithoutStoredMode.filter { path ->
            path.startsWith(CliPackageLayout.PAYLOAD_PREFIX)
        }
        if (offenders.isNotEmpty()) {
            val listed = offenders.take(5).joinToString(", ") + if (offenders.size > 5) ", … (${offenders.size} total)" else ""
            issues += "$listed carry no unix mode, so they would land 0644 and fail as \"permission denied\" — " +
                "repack on a filesystem that stores permissions (zip -X, or the platform packer)"
        }
        if (offenders.size < files.size && files.none { it.isExecutable }) {
            issues += "no payload file carries an execute bit — nothing the check command could run would start"
        }
    }

    // ==================== archive layout ====================

    /**
     * Anything the three documented locations do not cover is refused.
     *
     * Loose files would be silently ignored (so the author would keep shipping them) while `__MACOSX`
     * directories from a Finder-compressed tree would inflate the digest that names the stored object.
     */
    private fun checkLayout(
        archive: CliPackageArchive,
        issues: MutableList<String>,
    ) {
        val unexpected = archive.entries
            .map { it.archivePath }
            .filterNot { path ->
                path == CliPackageLayout.MANIFEST_ENTRY ||
                    path == "skill/" ||
                    path.startsWith("skill/") ||
                    path == CliPackageLayout.PAYLOAD_PREFIX ||
                    path.startsWith(CliPackageLayout.PAYLOAD_PREFIX)
            }
        if (unexpected.isNotEmpty()) {
            issues += "unexpected entr${if (unexpected.size == 1) "y" else "ies"} outside plugin.yaml, skill/ and payload/: " +
                unexpected.take(5).joinToString(", ") + if (unexpected.size > 5) " (+${unexpected.size - 5} more)" else ""
        }
        archive.duplicateEntryNames.forEach { name ->
            issues += "$name appears more than once in the archive — one name has to mean one entry, because the " +
                "digests hash every copy while reading answers with the first and extraction keeps the last"
        }
    }

    // ==================== misc readers ====================

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: Exception) {
        null
    }

    private fun Map<*, *>.getString(key: String): String? = (this[key] as? String)?.trim()

    private fun Map<*, *>.getBoolean(key: String): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }
}
