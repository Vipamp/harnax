package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.config.AdminMinioProperties
import com.agnetix.harnax.admin.dto.MemoryAgentResponse
import com.agnetix.harnax.admin.dto.MemoryDailyEntryResponse
import com.agnetix.harnax.admin.dto.MemoryDetailResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.MemoryObjectKeys
import com.agnetix.harnax.admin.util.MemoryRecordParser
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs
import io.minio.errors.ErrorResponseException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

/**
 * Reads and removes one owner's long-term agent memory out of the shared store bucket.
 *
 * The agent runtime owns every byte here: it writes the curated `MEMORY.md` and the daily ledger through
 * `MinioBaseStore`, which wraps each file in a JSON envelope. This class is the mirror of that write for
 * the two things admin has to answer — what one owner has, and how to take it back — and it never builds a
 * key from anything but a tenant id and a user id the request cannot influence, plus an agent id that
 * passed [MemoryObjectKeys.isValidAgentId].
 *
 * Three rules hold everywhere below:
 * 1. Every listing starts at the caller's own owner prefix — `store/tenants/<tenantId>/users/<userId>/`, or
 *    `store/users/<userId>/` when the writer's tenant-scoped switch is off — and a key that does not decode
 *    under that prefix is dropped before it can be read or deleted.
 * 2. The bytes only ever come from keys the object store itself returned, never from a rebuilt path.
 * 3. A storage failure is thrown, never logged and answered as "no memory", on the listing and on the read
 *    alike: an owner told they have nothing because the server was unreachable would stop looking, and an
 *    admin whose user delete left memory behind would believe the account was cleaned.
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
        return groupByAgent(longTermOnly(list(ownerPrefix))).map { (agentId, objects) ->
            val curated = recordOf(objects, MemoryObjectKeys.ROOT_SEGMENT)
            MemoryAgentResponse(
                agentId = agentId,
                content = curated?.record?.content ?: "",
                lastModified = curated?.let { storageTime(it.stored.lastModified, it.record.modifiedAt) },
                // Dates only here: a list that read every ledger of every agent would fetch the owner's
                // whole memory history to show one line per agent.
                dates = objects
                    .filter { it.location.segment == MemoryObjectKeys.MEMORY_SEGMENT }
                    .mapNotNull { MemoryObjectKeys.dateOf(it.location.itemKey) }
                    .distinct()
                    .sorted(),
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
     * The agent id is validated before anything is addressed, and the deleted keys are the ones the listing
     * under the caller's own prefix named — the request never contributes a path segment of its own.
     */
    fun deleteAgent(
        tenantId: Long,
        userId: String,
        agentId: String,
    ): Int {
        if (!MemoryObjectKeys.isValidAgentId(agentId)) {
            throw BizException("Invalid agent id")
        }
        val objects = groupByAgent(list(ownerPrefix(tenantId, userId)))[agentId].orEmpty()
        return deleteAll(objects, "agent '$agentId' of user $userId in tenant $tenantId")
    }

    /**
     * Deletes everything one user ever wrote, across every agent: the sweep an admin's user deletion runs.
     *
     * The runtime keys the bucket on the tenant of the agent that was talked to, so an owner who is a member
     * of several tenants has memory spread over one prefix per tenant, and a sweep of only the row's home
     * tenant leaves the rest of a "deleted" account's memory behind. Tenant and user both come from rows,
     * never from a path variable.
     */
    fun deleteUser(
        tenantIds: Collection<Long>,
        userId: String,
    ): Int {
        // With the switch off every tenant names the same area, and one sweep of that owner is the truth.
        val prefixes = tenantIds.map { ownerPrefix(it, userId) }.distinct()
        val targets = prefixes.flatMap { list(it) }
        return deleteAll(targets, "user $userId in ${prefixes.size} memory prefix(es)")
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
     * [deleteAgent] and [deleteUser] run on the unfiltered listing.
     */
    private fun longTermOnly(objects: List<Stored>): List<Stored> = objects.filter { it.location.sessionId == null }

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

    /** The objects under [prefix] that decode to a memory location of this owner. */
    private fun list(prefix: String): List<Stored> {
        val (client, bucket) = storage()
        val entries = try {
            client.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build(),
            ).toList()
        } catch (e: Exception) {
            log.error("[memory] Listing {} failed", prefix, e)
            throw BizException(503, "Memory store could not be listed", e)
        }
        val stored = mutableListOf<Stored>()
        for (entry in entries) {
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
     * still there, only this page cannot name it.
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
        targets: List<Stored>,
        described: String,
    ): Int {
        if (targets.isEmpty()) {
            return 0
        }
        val (client, bucket) = storage()
        var removed = 0
        var failure: Exception? = null
        for (target in targets) {
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(target.objectKey).build())
                removed++
            } catch (e: Exception) {
                // Every object still gets a chance, because a retry of a half-finished delete has to be the
                // cheap case; the first failure is what gets reported.
                failure = failure ?: e
                log.warn("[memory] Object {} could not be deleted: {}", target.objectKey, e.message)
            }
        }
        val error = failure
        if (error != null) {
            // Not swallowed: the caller's transaction rolls back on this and the operator learns the memory
            // is only partly gone. Objects already removed stay removed, and a repeated delete is a no-op.
            log.error("[memory] Deleting {} stopped after {} object(s): {}", described, removed, error.message)
            throw BizException(
                503,
                "Memory could not be fully deleted for $described ($removed of ${targets.size} object(s) removed before failing)",
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
}
