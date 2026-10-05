package com.agnetix.harnax.harness.minio

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Whether a [BaseStore] can really compare versions before writing, which is what decides if this
 * deployment's memory hooks ever run.
 *
 * Upstream picks the consolidation gate from the distributed store alone
 * (`HarnessAgent.java:2409-2412` builds `StoreBackedPeriodicGate(distributedStore.baseStore())`) and
 * gives the assembly no way to choose the gate itself, so the store's `putIfVersion` is the only thing
 * standing between a claim and every replica doing the same background write. Two defects look
 * identical from the outside — a consolidation that never fires:
 *
 * - a backend that never overrode `putIfVersion`, whose interface default answers `false` to every
 *   claim, so the gate refuses everything in silence;
 * - a gateway that takes the precondition header and ignores it, so the gate grants everything and
 *   the deduplication it exists for does not happen.
 *
 * Both are worth knowing about before an operator waits a day for a `MEMORY.md` that will not arrive.
 */
object StoreCasProbe {

    /** The namespace [StoreBackedPeriodicGate] keeps its slots in (`StoreBackedPeriodicGate.java:37`). */
    val COORDINATION: List<String> = listOf("coordination", "periodic")

    private val log = LoggerFactory.getLogger(StoreCasProbe::class.java)

    /** The slot name prefix. Each probe uses its own so two replicas probing at once cannot step on it. */
    private const val SLOT_PREFIX = "harnax-cas-probe"

    /**
     * Claims, writes and reads one throwaway slot three ways, then deletes it.
     *
     * [detail] is written for whoever ends up reading a WARN in the log, so it names the arm that failed
     * rather than just saying the store is unhealthy.
     */
    fun probe(store: BaseStore): StoreCasSupport {
        val slot = "$SLOT_PREFIX-${UUID.randomUUID()}"
        val seed = mapOf("lastClaimAt" to 0L)
        return try {
            store.put(COORDINATION, slot, seed)
            val observed = store.get(COORDINATION, slot)?.version ?: 0L
            if (observed <= 0L) {
                return StoreCasSupport(
                    false,
                    "the store answered version $observed for an object it had just written, so nothing " +
                        "can be compared against",
                )
            }
            if (store.putIfVersion(COORDINATION, slot, seed, observed + 5)) {
                return StoreCasSupport(
                    false,
                    "the version precondition is ignored: a claim naming a version nobody observed was " +
                        "accepted, so every replica would claim the same slot",
                )
            }
            if (!store.putIfVersion(COORDINATION, slot, seed, observed)) {
                return StoreCasSupport(
                    false,
                    "the claim never went through: putIfVersion refused the version this read had just " +
                        "observed, which is what the interface default does",
                )
            }
            if (store.putIfVersion(COORDINATION, slot, seed, 0L)) {
                return StoreCasSupport(
                    false,
                    "the create-if-absent precondition is ignored: a claim against a slot that already " +
                        "exists was accepted",
                )
            }
            StoreCasSupport(
                true,
                "all three version arms behaved: mismatch refused, match granted, " +
                    "create-if-absent refused",
            )
        } catch (e: RuntimeException) {
            StoreCasSupport(false, "the store threw while comparing versions: ${e.message}", definitive = false)
        } finally {
            try {
                store.delete(COORDINATION, slot)
            } catch (e: RuntimeException) {
                log.warn("[memory] CAS probe slot {} was left behind: {}", slot, e.message)
            }
        }
    }

    /**
     * [supported] false means the gate has to be served in this process instead; [detail] says why.
     *
     * [definitive] is false only when the probe never got an answer, so the verdict describes a connection
     * rather than a store: a caller that remembered that would pin a deployment to per-replica coordination
     * because MinIO was restarting while the first session of the process came in.
     */
    data class StoreCasSupport(
        val supported: Boolean,
        val detail: String,
        val definitive: Boolean = true,
    )
}
