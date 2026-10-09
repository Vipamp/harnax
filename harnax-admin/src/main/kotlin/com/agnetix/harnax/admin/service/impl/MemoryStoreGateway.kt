package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.config.AdminMinioProperties
import com.agnetix.harnax.admin.dto.MemoryAgentResponse
import com.agnetix.harnax.admin.dto.MemoryDailyEntryResponse
import com.agnetix.harnax.admin.dto.MemoryDetailResponse
import com.agnetix.harnax.admin.dto.MemoryDraftSource
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.MemoryObjectKeys
import com.agnetix.harnax.admin.util.MemoryRecordParser
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.Result
import io.minio.errors.ErrorResponseException
import io.minio.messages.Item
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Reads, replaces and removes one owner's agent memory in the shared store bucket.
 *
 * The agent runtime owns the conversation's own layer: it writes a session's draft and ledgers through
 * `MinioBaseStore` (harnax-harness-core), which wraps every file in a JSON envelope, and it merges them into
 * a candidate it files with the review queue instead of writing the owner's layer itself. An approval is what
 * writes here, which makes this class the only path into a person's long-term `MEMORY.md`. It is therefore
 * the mirror of that write for three questions — what one owner has, how to replace the curated layer with
 * what a reviewer accepted, and how to take a memory back — and it never builds a key from anything but a
 * tenant id and a user id the request cannot influence, plus an agent id that passed
 * [MemoryObjectKeys.isValidAgentId].
 *
 * Four rules hold everywhere below:
 * 1. Every listing starts at the caller's own owner prefix — `store/tenants/<tenantId>/users/<userId>/`, or
 *    `store/users/<userId>/` when the writer's tenant-scoped switch is off — and a key outside it is never
 *    touched. A read then keeps only the keys that decode to a memory location, because an object admin cannot
 *    name is an object it cannot show. A delete keeps every key the bucket listed, because a sweep that skipped
 *    the ones this decoder refuses would leave a "deleted" owner's memory behind forever.
 * 2. The bytes only ever come from keys the object store itself returned, never from a rebuilt path. A write
 *    goes to the one key [curatedKey] produces, and a conversation-layer clear only ever addresses a key
 *    [MemoryObjectKeys.sessionSourceKey] resolved from a path the merge itself recorded.
 * 3. A storage failure is thrown, never logged and answered as "no memory", on the listing and on the read
 *    alike: an owner told they have nothing because the server was unreachable would stop looking, and an
 *    admin whose user delete left memory behind would believe the account was cleaned.
 * 4. The long-term layer is only ever replaced conditionally — against the version the merge read, carried by
 *    the object's own ETag — and a conversation's file is only ever deleted while it still holds the bytes the
 *    merge recorded. Two conversations of one agent merge against the same base; whoever approves second is
 *    told plainly that the layer moved instead of overwriting the first.
 *
 * The client and its properties arrive through [ObjectProvider] because `minio.enabled=false` is a
 * supported deployment — the client bean is conditional. Without a store there is no memory to show, and
 * that has to be an answer rather than a startup failure.
 */
@Component
class MemoryStoreGateway(
    private val minioClientProvider: ObjectProvider<MinioClient>,
    private val minioPropertiesProvider: ObjectProvider<AdminMinioProperties>,
    private val objectMapper: ObjectMapper,
    // The writer's switch, read from the same env var so one deployment value steers both sides: off means the
    // runtime keys memory `store/users/<uid>/…` and a tenant prefix here would list an empty area.
    @Value("\${harnax.memory.tenant-scoped:true}") private val tenantScoped: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(MemoryStoreGateway::class.java)

    /**
     * One object of the caller's memory bucket: where it lives, what it decodes to, when it was written.
     *
     * The whole read and delete surface works on these, so nothing downstream can address an object the
     * listing did not hand out.
     */
    private data class Stored(
        val objectKey: String,
        val location: MemoryObjectKeys.Location,
        val lastModified: ZonedDateTime?,
    )

    /** Which agents of this owner have memory, with the curated layer read and the daily ledger only dated. */
    fun listAgents(
        tenantId: Long,
        userId: String,
    ): List<MemoryAgentResponse> {
        val ownerPrefix = ownerPrefix(tenantId, userId)
        // Grouped over both layers, so an agent that has so far only written conversation layers still gets a
        // row: on the page that is the difference between "these conversations are not merged yet" and
        // "this agent has no memory at all", and the second would be a lie.
        return groupByAgent(list(ownerPrefix)).map { (agentId, objects) ->
            val longTerm = longTermOnly(objects)
            val curated = recordOf(longTerm, MemoryObjectKeys.ROOT_SEGMENT)
            MemoryAgentResponse(
                agentId = agentId,
                content = curated?.record?.content ?: "",
                lastModified = curated?.let { storageTime(it.stored.lastModified, it.record.modifiedAt) },
                // Dates only here: a list that read every ledger of every agent would fetch the owner's
                // whole memory history to show one line per agent.
                dates = longTerm
                    .filter { it.location.segment == MemoryObjectKeys.MEMORY_SEGMENT }
                    .mapNotNull { MemoryObjectKeys.dateOf(it.location.itemKey) }
                    .distinct()
                    .sorted(),
                pendingSessionLayers = pendingLayers(objects),
            )
        }
    }

    /** One agent's curated layer and all of its daily entries, read in full. Null when the agent has no memory. */
    fun readAgent(
        tenantId: Long,
        userId: String,
        agentId: String,
    ): MemoryDetailResponse? {
        if (!MemoryObjectKeys.isValidAgentId(agentId)) {
            throw BizException("Invalid agent id")
        }
        val objects = groupByAgent(longTermOnly(list(ownerPrefix(tenantId, userId))))[agentId] ?: return null
        val curated = recordOf(objects, MemoryObjectKeys.ROOT_SEGMENT)
        val entries = objects
            .filter { it.location.segment == MemoryObjectKeys.MEMORY_SEGMENT }
            .mapNotNull { stored ->
                val date = MemoryObjectKeys.dateOf(stored.location.itemKey) ?: return@mapNotNull null
                // An unreadable envelope still keeps its date: a day that is in the bucket is a day the
                // owner lived through, and dropping the line would read as "that day never happened".
                val record = readBody(stored)
                MemoryDailyEntryResponse(
                    date = date,
                    content = record?.content ?: "",
                    lastModified = storageTime(stored.lastModified, record?.modifiedAt),
                )
            }
            .sortedBy { it.date }
        return MemoryDetailResponse(
            agentId = agentId,
            content = curated?.record?.content ?: "",
            lastModified = curated?.let { storageTime(it.stored.lastModified, it.record.modifiedAt) },
            entries = entries,
        )
    }

    /**
     * Deletes one agent's memory for this owner and returns how many objects went away.
     *
     * The agent id is validated before anything is addressed, and the keys are every object the bucket lists
     * under that agent's prefix — the request contributes the one validated segment of the prefix and no path
     * of its own. Both of the agent's layers go, and so does an object whose name this decoder would refuse.
     */
    fun deleteAgent(
        tenantId: Long,
        userId: String,
        agentId: String,
    ): Int {
        if (!MemoryObjectKeys.isValidAgentId(agentId)) {
            throw BizException("Invalid agent id")
        }
        return deleteAll(
            keysUnder(agentPrefix(tenantId, userId, agentId)),
            "agent '$agentId' of user $userId in tenant $tenantId",
        )
    }

    /**
     * Deletes everything one user ever wrote, across every agent: the sweep an admin's user deletion runs.
     *
     * The runtime keys the bucket on the tenant of the agent that was talked to, so an owner who is a member
     * of several tenants has memory spread over one prefix per tenant, and a sweep of only the row's home
     * tenant leaves the rest of a "deleted" account's memory behind. Tenant and user both come from rows,
     * never from a path variable.
     *
     * The keys are raw for the same reason: an agent whose own name contains a slash writes a key no decoder
     * can place, and that memory has to leave with its owner even though no endpoint can name its agent.
     */
    fun deleteUser(
        tenantIds: Collection<Long>,
        userId: String,
    ): Int {
        // With the switch off every tenant names the same area, and one sweep of that owner is the truth.
        val prefixes = tenantIds.map { ownerPrefix(it, userId) }.distinct()
        return deleteAll(
            prefixes.flatMap { keysUnder(it) },
            "user $userId in ${prefixes.size} memory prefix(es)",
        )
    }

    /**
     * The owner's long-term curated layer: its text and the version the store answers for it.
     *
     * [version] is 0 when there is no object yet, which is the same reading `MinioBaseStore` gives the
     * promotion pass that filed the candidate — so the number a reviewer's approve is checked against is the
     * number the merge itself saw.
     */
    data class CuratedLayer(
        val content: String,
        val version: Long,
    )

    /**
     * One day of the agent's long-term ledger: its text and the version the store answers for that day.
     *
     * Same two numbers as [CuratedLayer] and the same reading of an absent object — empty text and version 0,
     * which is what a create is filed against — but deliberately its own type: the conclusion layer is what
     * gets injected into every later conversation, while a day here is history the owner reads by date, and an
     * approval checks the two preconditions separately.
     */
    data class DailyLayer(
        val content: String,
        val version: Long,
    )

    /**
     * What an approval did to one conversation's own layer.
     *
     * Three counts because all three are true answers about a bucket the runtime keeps writing into:
     * [cleared] held exactly the bytes the merge recorded and is gone, [kept] moved since that read — a later
     * turn appended to the same ledger — or is a path no merge could have read, and [absent] was already gone.
     * A kept file re-enters the next candidate, which is what makes a partial clear safe rather than lossy.
     */
    data class ClearedSources(
        val cleared: Int,
        val kept: Int,
        val absent: Int,
    )

    /**
     * The owner's `MEMORY.md` as the store holds it now.
     *
     * Absent answers as [CuratedLayer] with empty text and version 0, because that is what a create is filed
     * against. A key that cannot be addressed is thrown rather than answered that way: "no memory yet" about
     * an agent this call cannot name would let an approval create a bucket nothing can read back.
     */
    fun readCuratedLayer(
        tenantId: Long,
        userId: String,
        agentId: String,
    ): CuratedLayer {
        val record = loadWrapper(curatedKey(tenantId, userId, agentId)) ?: return CuratedLayer("", CREATE_IF_ABSENT)
        return CuratedLayer(record.content, record.version)
    }

    /**
     * Replaces the owner's `MEMORY.md`, but only while it still holds [expectedVersion].
     *
     * Two guards, in this order. The version the candidate was merged against is compared here, before
     * anything goes on the wire; and the object's own ETag travels as `If-Match` (`If-None-Match: *` for a
     * layer that does not exist yet), so a second approval that raced past the first is refused by the store
     * rather than by this process. A 412 is an answer, not a fault — false — while anything else the store
     * throws is thrown, because an approval that believed it had written would tell the owner their memory
     * had been merged when it had not.
     *
     * The new version is [expectedVersion] + 1, so a layer that has been approved twice in a row cannot
     * answer the same version to two different candidates.
     */
    fun writeCuratedIfVersion(
        tenantId: Long,
        userId: String,
        agentId: String,
        expectedVersion: Long,
        content: String,
    ): Boolean {
        val objectKey = curatedKey(tenantId, userId, agentId)
        val current = loadWrapper(objectKey)
        val version = current?.version ?: CREATE_IF_ABSENT
        if (version != expectedVersion) {
            log.warn(
                "[memory] Refused to replace {} at version {}: the layer is at {} now",
                objectKey,
                expectedVersion,
                version,
            )
            return false
        }
        val precondition = when {
            current == null -> mapOf("If-None-Match" to "*")
            current.etag != null -> mapOf("If-Match" to current.etag)
            // MinioBaseStore.putIfVersion answers the same way: with no ETag there is nothing to make the
            // write conditional on, and an unconditional write would overwrite what the read did not see.
            else -> {
                log.warn("[memory] Object {} answered no ETag, so its replacement cannot be made conditional", objectKey)
                return false
            }
        }
        return writeEnvelope(objectKey, MemoryObjectKeys.MEMORY_MD_ITEM_KEY, content, version + 1, current?.createdAt, precondition)
    }

    /**
     * The object one candidate's daily [path] says it would write in the agent's own layer, or null when no
     * merge could have produced it.
     *
     * The same reason [sessionSourceKey] is here rather than beside the pure resolver: this class owns the
     * `harnax.memory.tenant-scoped` switch and the store prefix, and a queued target and a written object have
     * to be the same file. Intake asks this before it stores a target so a proposal cannot name a path whose
     * bytes no approval can reach.
     */
    fun longTermSourceKey(
        tenantId: Long,
        userId: String,
        agentId: String,
        path: String?,
    ): String? = MemoryObjectKeys.longTermSourceKey(keyPrefix(), tenantId, userId, agentId, path, tenantScoped)

    /**
     * One day of the agent's long-term ledger as the store holds it now.
     *
     * An absent day answers as [DailyLayer] with empty text and version 0, the same reading a create is filed
     * against, and it is the common answer here rather than the rare one: the agent's ledger only grows days
     * once an approval has written them. A path that cannot be addressed is thrown rather than answered that
     * way, because "no such day yet" about a bucket this call cannot name would let an approval create an
     * object the page cannot list.
     */
    fun readDailyLayer(
        tenantId: Long,
        userId: String,
        agentId: String,
        path: String,
    ): DailyLayer {
        val objectKey = dailyKey(tenantId, userId, agentId, path)
        val record = loadWrapper(objectKey) ?: return DailyLayer("", CREATE_IF_ABSENT)
        return DailyLayer(record.content, record.version)
    }

    /**
     * Replaces one day of the agent's ledger, but only while it still holds [expectedVersion].
     *
     * The guards, the 412-is-an-answer rule and the version arithmetic are [writeCuratedIfVersion]'s, and for
     * the same reason: two conversations merged the same day, and whichever approval got there first owns that
     * day until the other one is re-merged against it. [path] is the candidate's own target path, so a day
     * whose bytes moved is refused by name and the rest of the approval can still be checked.
     */
    fun writeDailyIfVersion(
        tenantId: Long,
        userId: String,
        agentId: String,
        path: String,
        expectedVersion: Long,
        content: String,
    ): Boolean {
        val objectKey = dailyKey(tenantId, userId, agentId, path)
        val itemKey = objectKey.substringAfterLast("/")
        val current = loadWrapper(objectKey)
        val version = current?.version ?: CREATE_IF_ABSENT
        if (version != expectedVersion) {
            log.warn(
                "[memory] Refused to replace {} at version {}: the day is at {} now",
                objectKey,
                expectedVersion,
                version,
            )
            return false
        }
        val precondition = when {
            current == null -> mapOf("If-None-Match" to "*")
            current.etag != null -> mapOf("If-Match" to current.etag)
            else -> {
                log.warn("[memory] Object {} answered no ETag, so its replacement cannot be made conditional", objectKey)
                return false
            }
        }
        return writeEnvelope(objectKey, "/$itemKey", content, version + 1, current?.createdAt, precondition)
    }

    /**
     * The object one candidate's [path] says the merge read from, or null when no merge could have read it.
     *
     * Intake asks this so a proposal cannot queue a source its approval would later ignore. It is the same
     * call [clearSessionSources] makes, over the same key prefix and the same `harnax.memory.tenant-scoped`
     * switch, which is why it lives here rather than beside [MemoryObjectKeys.sessionSourceKey]: two places
     * reading that switch is how a queued source and a cleared object stop being the same file.
     */
    fun sessionSourceKey(
        tenantId: Long,
        userId: String,
        agentId: String,
        sessionId: String,
        path: String,
    ): String? = MemoryObjectKeys.sessionSourceKey(keyPrefix(), tenantId, userId, agentId, sessionId, path, tenantScoped)

    /**
     * Takes the files of one conversation's own layer that this candidate was merged out of.
     *
     * Each [sources] entry carries the path the promotion pass read through and the exact text it found, and
     * both are load-bearing here: the path resolves to the object to clear through
     * [sessionSourceKey] — a path that does not resolve is a [ClearedSources.kept], because
     * the only objects this may touch are the ones a merge actually read — and the content is what the object
     * has to still hold for the delete to be allowed. A conversation keeps writing between the merge and the
     * decision; an entry whose bytes moved belongs to a candidate nobody has seen, and clearing it would
     * throw away a conversation's memory on the strength of a proposal that does not describe it.
     *
     * Every delete is confirmed by reading the object back, and one that survives is thrown rather than
     * counted: the queue would otherwise keep a conversation marked as un-merged, and its next merge would
     * propose the same text again.
     */
    fun clearSessionSources(
        tenantId: Long,
        userId: String,
        agentId: String,
        sessionId: String,
        sources: List<MemoryDraftSource>,
    ): ClearedSources {
        var cleared = 0
        var kept = 0
        var absent = 0
        for (source in sources) {
            val path = source.path
            if (path == null) {
                log.warn("[memory] A source of conversation {} carries no path, so nothing is cleared for it", sessionId)
                kept++
                continue
            }
            val objectKey = sessionSourceKey(tenantId, userId, agentId, sessionId, path)
            if (objectKey == null) {
                log.warn("[memory] Source path '{}' of conversation {} is not a merge source, leaving that object alone", path, sessionId)
                kept++
                continue
            }
            val record = loadWrapper(objectKey)
            if (record == null) {
                absent++
                continue
            }
            if (record.content != source.content.orEmpty()) {
                log.info("[memory] Source '{}' of conversation {} moved since the merge read it, keeping it for the next candidate", path, sessionId)
                kept++
                continue
            }
            deleteAndConfirmGone(objectKey)
            cleared++
        }
        return ClearedSources(cleared, kept, absent)
    }

    /**
     * Whether this instance can reach a memory store at all.
     *
     * Lets the user-delete sweep skip quietly on a deployment that never turned MinIO on, instead of
     * failing an unrelated database operation because of it.
     */
    fun isAvailable(): Boolean = minioClientProvider.ifAvailable != null

    /** A loaded object together with its parsed envelope, so a body is fetched at most once. */
    private data class Loaded(
        val stored: Stored,
        val record: MemoryRecordParser.Record,
    )

    /**
     * The objects that are this owner's long-term layer, dropping every conversation's own bucket.
     *
     * A session's `root/MEMORY.md` is a draft that has not been merged yet, and its ledgers have not earned
     * a place in the curated memory. Handled as long-term content they would make the page say a new
     * conversation will be told something it will not. Both layers still go away together, because
     * [deleteAgent] and [deleteUser] address prefixes rather than decoded objects.
     */
    private fun longTermOnly(objects: List<Stored>): List<Stored> = objects.filter { it.location.sessionId == null }

    /**
     * How many of this agent's conversations still hold memory of their own.
     *
     * Conversations, not objects: one conversation's draft and its ledgers are the one merge that is waiting,
     * and a page that counted objects would show an agent as twice as far behind for a conversation that
     * wrote on two days. Which objects mean "not merged yet" is the writer's answer, not this one, so the
     * shape rule lives in [MemoryObjectKeys.hasUnmergedContent] beside the keys it reads.
     */
    private fun pendingLayers(objects: List<Stored>): Int = objects
        .filter { it.location.sessionId != null && MemoryObjectKeys.hasUnmergedContent(it.location) }
        .mapNotNull { it.location.sessionId }
        .distinct()
        .size

    /** The curated layer of one agent's objects, read once. */
    private fun recordOf(
        objects: List<Stored>,
        segment: String,
    ): Loaded? = objects.firstOrNull { it.location.segment == segment }?.let { stored ->
        readBody(stored)?.let { Loaded(stored, it) }
    }

    private fun ownerPrefix(
        tenantId: Long,
        userId: String,
    ): String = MemoryObjectKeys.ownerPrefix(keyPrefix(), tenantId, userId, tenantScoped)

    /** One agent's memory of this owner: both routes, both layers, and every conversation's own bucket. */
    private fun agentPrefix(
        tenantId: Long,
        userId: String,
        agentId: String,
    ): String = MemoryObjectKeys.agentPrefix(keyPrefix(), tenantId, userId, agentId, tenantScoped)

    /**
     * The owner's long-term `MEMORY.md`, addressed by the one agent id this class will accept.
     *
     * The single source of that key for everything write-side below, so no write can address a bucket the
     * read side would not have shown: an unaddressable agent id is refused here rather than producing a key
     * for a memory nobody owns.
     */
    private fun curatedKey(
        tenantId: Long,
        userId: String,
        agentId: String,
    ): String {
        if (!MemoryObjectKeys.isValidAgentId(agentId)) {
            throw BizException("Invalid agent id")
        }
        return MemoryObjectKeys.memoryMdKey(keyPrefix(), tenantId, userId, agentId, tenantScoped)
    }

    /**
     * One day of the owner's long-term ledger, addressed by the candidate's own target path.
     *
     * The single source of that key for the daily writes above, for the same reason [curatedKey] is: a path
     * that resolves to nothing is refused here rather than producing a key for a day no listing can answer.
     * Intake has already run the same resolver over every target it accepted, so reaching this line with an
     * unresolvable path means the row was written around intake.
     */
    private fun dailyKey(
        tenantId: Long,
        userId: String,
        agentId: String,
        path: String,
    ): String = longTermSourceKey(tenantId, userId, agentId, path)
        ?: throw BizException("Invalid memory day path: $path")

    /**
     * One store object, read the way a write needs it read.
     *
     * @param etag the server's answer for this exact body, which is what the replacement is made conditional
     *   on; a null means the write cannot be made conditional at all
     * @param version the store's version of the object — never 0 for an object that exists, because
     *   `MinioBaseStore.effectiveVersion` answers 1 for an envelope that predates the embedded version, and
     *   the two sides must not name one layer by two numbers
     * @param content the file text at `value.content`
     * @param createdAt the timestamp the first write embedded, kept so a replacement does not relabel the
     *   file as brand new
     */
    private data class Wrapper(
        val etag: String?,
        val version: Long,
        val content: String,
        val createdAt: String?,
    )

    /**
     * Reads one object for a write.
     *
     * Null is only ever "the object is not there", and it is the whole precondition for a create. Everything
     * else the store or the body complains about is thrown: [readBody]'s tolerance belongs to the read path,
     * where a neighbour's odd object is not this caller's memory, while here a body this class cannot
     * round-trip is a body it must not overwrite. A store that answers 500 would otherwise be answered with
     * "no memory yet", and the approval would create a second object over the one the owner has.
     */
    private fun loadWrapper(objectKey: String): Wrapper? {
        val (client, bucket) = storage()
        return try {
            client.getObject(
                GetObjectArgs.builder().bucket(bucket).`object`(objectKey).build(),
            ).use { response ->
                val etag = response.headers()["ETag"]
                unwrap(objectKey, etag, response.bufferedReader(StandardCharsets.UTF_8).readText())
            }
        } catch (e: BizException) {
            throw e
        } catch (e: ErrorResponseException) {
            if (isMissingObject(e)) {
                log.debug("[memory] No object at {} yet, which is a create", objectKey)
                null
            } else {
                throw BizException(503, "Memory object $objectKey could not be read", e)
            }
        } catch (e: Exception) {
            throw BizException(503, "Memory object $objectKey could not be read", e)
        }
    }

    /** The envelope of [body] as a write needs it, refused when the object is not an envelope at all. */
    private fun unwrap(
        objectKey: String,
        etag: String?,
        body: String,
    ): Wrapper {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw BizException(503, "Memory object $objectKey is not a store envelope, so it cannot be replaced", e)
        }
        val value = root?.path("value")
        if (value == null || !value.isObject) {
            throw BizException(503, "Memory object $objectKey is not a store envelope, so it cannot be replaced")
        }
        return Wrapper(
            etag = etag,
            version = root.path("version").asLong(0L).coerceAtLeast(1L),
            content = MemoryRecordParser.parse(body, objectMapper).content,
            createdAt = value.path("created_at").takeIf { it.isString || it.isNumber }?.asText(),
        )
    }

    /**
     * Writes one envelope over [objectKey], conditionally when [precondition] says so.
     *
     * The body is the same document `MinioBaseStore.write()` produces — `key`, then `value` with
     * `created_at`, `encoding`, `modified_at` and `content` in that order, then `version` — because the agent
     * runtime reads this object back through that class, and `MemoryOwnerBucketMinioIT` pins the shape on
     * both sides the way every other memory key string is pinned in this repo.
     *
     * [itemKey] is part of that contract rather than decoration: `MinioBaseStore.search()` hands back the
     * envelope's own `key` as the item key of each listed object, so a daily file written with
     * `MemoryObjectKeys.MEMORY_MD_ITEM_KEY` would be reported to the runtime as a second `/MEMORY.md` in the
     * same namespace — invisible on the page, which lists object keys, and wrong on every read side.
     *
     * A 412 is the conditional write having been refused, which is an answer about the bucket and comes back
     * as false; anything else the store complains about is thrown.
     */
    private fun writeEnvelope(
        objectKey: String,
        itemKey: String,
        content: String,
        version: Long,
        createdAt: String?,
        precondition: Map<String, String>,
    ): Boolean {
        val (client, bucket) = storage()
        val now = Instant.now().toString()
        val json = objectMapper.writeValueAsString(
            linkedMapOf(
                "key" to itemKey,
                "value" to linkedMapOf<String, Any>(
                    "created_at" to (createdAt ?: now),
                    "encoding" to "utf-8",
                    "modified_at" to now,
                    "content" to content,
                ),
                "version" to version,
            ),
        )
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val builder = PutObjectArgs.builder()
            .bucket(bucket)
            .`object`(objectKey)
            .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
            .contentType("application/json")
        if (precondition.isNotEmpty()) {
            // MinIO answers an unfulfilled conditional PUT by closing the connection without reading the body,
            // and okhttp hands that dead connection to the next write on this client — which then throws an
            // IOException and loses a write that had every right to land (measured in MemoryApprovalStoreIT).
            // A conditional write is therefore a one-connection event: a refusal costs its own connection
            // rather than the next approval's.
            builder.extraHeaders(precondition + mapOf("Connection" to "close"))
        }
        return try {
            client.putObject(builder.build())
            log.info("[memory] Wrote {} as version {}", objectKey, version)
            true
        } catch (e: ErrorResponseException) {
            if (e.response().code == 412) {
                log.warn("[memory] The store refused the version precondition on {}, so another write got there first", objectKey)
                false
            } else {
                throw BizException(503, "Memory object $objectKey could not be written", e)
            }
        } catch (e: Exception) {
            throw BizException(503, "Memory object $objectKey could not be written", e)
        }
    }

    /**
     * Removes one object and reads it back, throwing while it is still there.
     *
     * The store's own success answer is not the evidence: an approval that counted a file as merged away
     * while the conversation still has it would re-propose the same text on the next pass, forever, and the
     * queue would say the two counts it just printed.
     */
    private fun deleteAndConfirmGone(objectKey: String) {
        val (client, bucket) = storage()
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(objectKey).build())
        } catch (e: Exception) {
            throw BizException(503, "Memory object $objectKey could not be deleted", e)
        }
        if (loadWrapper(objectKey) != null) {
            throw BizException(503, "Memory object $objectKey survived its own deletion, so the conversation layer was not cleared")
        }
    }

    /**
     * Every object key under [prefix], named the way the bucket names it.
     *
     * The two delete sweeps work on these instead of on [list]'s decoded objects, because a key this decoder
     * refuses is a key a sweep may not skip: an agent whose own name contains a slash writes a memory object
     * that no endpoint can name, and it has to leave with its owner. The prefix is the whole scope — built from
     * ids the request cannot influence and always ending in `/`, so nothing that starts with it belongs to
     * anybody else, and the workspace files of the same owner live under a different namespace entirely.
     *
     * An entry whose metadata the store cannot deliver is thrown rather than skipped, unlike the read path:
     * reporting a clean sweep over objects that were never named is exactly what rule 3 forbids.
     */
    private fun keysUnder(prefix: String): List<String> = rawEntries(prefix).mapNotNull { entry ->
        val objectKey = try {
            entry.get().objectName()
        } catch (e: Exception) {
            log.error("[memory] Naming an entry under {} failed", prefix, e)
            throw BizException(503, "Memory store could not be listed", e)
        }
        objectKey.takeIf { it.startsWith(prefix) }
    }

    /** The bucket's answer for [prefix], materialised before anything reads it so a fault is thrown here. */
    private fun rawEntries(prefix: String): List<Result<Item>> {
        val (client, bucket) = storage()
        return try {
            client.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build(),
            ).toList()
        } catch (e: Exception) {
            log.error("[memory] Listing {} failed", prefix, e)
            throw BizException(503, "Memory store could not be listed", e)
        }
    }

    /** The objects under [prefix] that decode to a memory location of this owner. */
    private fun list(prefix: String): List<Stored> {
        val stored = mutableListOf<Stored>()
        for (entry in rawEntries(prefix)) {
            val item = try {
                entry.get()
            } catch (e: Exception) {
                log.warn("[memory] Keeping an entry under {} whose listing metadata failed: {}", prefix, e.message)
                continue
            }
            val objectKey = item.objectName()
            // A prefix listing can in principle answer with a foreign key. Dropping it here is what keeps
            // every read and delete below inside the caller's own namespace.
            val location = MemoryObjectKeys.locationOf(prefix, objectKey) ?: continue
            stored.add(Stored(objectKey, location, item.lastModified()))
        }
        return stored
    }

    /**
     * The caller's objects by agent.
     *
     * An agent id that is not addressable (a name the runtime wrote with a slash in it, say) groups to a
     * key no request could ever ask for, so it is dropped rather than guessed at — the owner's memory is
     * still there, only this page cannot name it. The two sweeps do not consult this map, so an agent the
     * page cannot name still goes with its owner.
     */
    private fun groupByAgent(objects: List<Stored>): Map<String, List<Stored>> = objects
        .groupBy { it.location.agentId }
        .filter { (agentId, _) -> MemoryObjectKeys.isValidAgentId(agentId) }

    /**
     * The file text and embedded timestamp of one object, or null when the object carries no readable text.
     *
     * Two answers are "nothing to show" and they are both about this object: a 404 (removed after the listing
     * handed it out) and a body that is not a store envelope. Everything else is the store refusing to answer,
     * which is thrown — a caller whose memory domain is on a machine that is down would be told they have no
     * memory, and rule 3 exists because that is the one answer an owner cannot check for themselves.
     */
    private fun readBody(stored: Stored): MemoryRecordParser.Record? = try {
        val (client, bucket) = storage()
        client.getObject(
            GetObjectArgs.builder().bucket(bucket).`object`(stored.objectKey).build(),
        ).use { response ->
            MemoryRecordParser.parse(response.bufferedReader(StandardCharsets.UTF_8).readText(), objectMapper)
        }
    } catch (e: ErrorResponseException) {
        if (isMissingObject(e)) {
            log.debug("[memory] Object {} went away after the listing: {}", stored.objectKey, e.errorResponse().code())
            null
        } else {
            throw BizException(503, "Memory object ${stored.objectKey} could not be read", e)
        }
    } catch (e: Exception) {
        log.error("[memory] Reading object {} failed", stored.objectKey, e)
        throw BizException(503, "Memory object ${stored.objectKey} could not be read", e)
    }

    /** What an object store answers for a key that is not there. */
    private fun isMissingObject(e: ErrorResponseException): Boolean = e.response().code == 404 &&
        e.errorResponse().code() == "NoSuchKey"

    /** The object's storage time, falling back to the timestamp the writer embedded in the envelope. */
    private fun storageTime(
        lastModified: ZonedDateTime?,
        embedded: String?,
    ): String? = lastModified?.toInstant()?.toString() ?: embedded

    private fun deleteAll(
        objectKeys: List<String>,
        described: String,
    ): Int {
        if (objectKeys.isEmpty()) {
            return 0
        }
        val (client, bucket) = storage()
        var removed = 0
        var failure: Exception? = null
        for (objectKey in objectKeys) {
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(objectKey).build())
                removed++
            } catch (e: Exception) {
                // Every object still gets a chance, because a retry of a half-finished delete has to be the
                // cheap case; the first failure is what gets reported.
                failure = failure ?: e
                log.warn("[memory] Object {} could not be deleted: {}", objectKey, e.message)
            }
        }
        val error = failure
        if (error != null) {
            // Not swallowed: the caller's transaction rolls back on this and the operator learns the memory
            // is only partly gone. Objects already removed stay removed, and a repeated delete is a no-op.
            log.error("[memory] Deleting {} stopped after {} object(s): {}", described, removed, error.message)
            throw BizException(
                503,
                "Memory could not be fully deleted for $described ($removed of ${objectKeys.size} object(s) removed before failing)",
                error,
            )
        }
        log.info("[memory] Deleted {} memory object(s) of {}", removed, described)
        return removed
    }

    private fun storage(): Pair<MinioClient, String> {
        val client = minioClientProvider.ifAvailable
            ?: throw BizException(503, "Memory store is not configured on this instance (minio.enabled=false)")
        val props = minioPropertiesProvider.ifAvailable
            ?: throw BizException(503, "Memory store is not configured on this instance (minio.enabled=false)")
        if (props.storeBucket.isBlank()) {
            throw BizException(503, "minio.store-bucket is not configured, so memory has no bucket to read")
        }
        return client to props.storeBucket
    }

    private fun keyPrefix(): String = minioPropertiesProvider.ifAvailable?.storePrefix ?: MemoryObjectKeys.DEFAULT_KEY_PREFIX

    companion object {
        /** The version an absent `MEMORY.md` answers with, and the one a first approval is filed against. */
        private const val CREATE_IF_ABSENT = 0L
    }
}
