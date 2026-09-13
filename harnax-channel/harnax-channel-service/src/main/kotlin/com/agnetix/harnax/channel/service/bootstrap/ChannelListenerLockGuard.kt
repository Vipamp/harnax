package com.agnetix.harnax.channel.service.bootstrap

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

/**
 * Makes exactly one channel-service instance the owner of the platform listeners.
 *
 * Two instances listening on the same Feishu/WeChat/WeCom credentials is not a benign
 * misconfiguration: the platform pushes each message to one of the connections — or, for long
 * polling, hands out the same updates to whoever asks — so users see duplicate or missing replies
 * and the conversation history splits across two memories. Nothing else in this service prevents
 * it: the reconcile loop reads a shared DB table, so every replica sees the same start list.
 *
 * MySQL named locks (`GET_LOCK`) are the smallest thing that works here. They live on the
 * connection, so a crashed or SIGKILLed replica is evicted as soon as the server drops that
 * connection — no lease table, no expiry column to refresh, no new schema (flyway is disabled in
 * this service; the admin service owns migrations for this DB).
 *
 * Failure policy, and it is deliberately asymmetric: an owned lock that cannot be re-verified
 * (a DB blip on the connection we created while holding it) keeps this instance serving, because
 * the same blip also makes the reconcile query fail — the loop goes idle on its own, and failing
 * closed would tear down and rebuild every WebSocket in the deployment each time MySQL hiccups.
 * A lock this instance never proved it owns is never assumed: that path returns false, because
 * claiming ownership there is exactly how two replicas end up listening on one set of credentials.
 */
@Component
class ChannelListenerLockGuard(
    private val dataSource: DataSource,
    @Value("\${channel.lock.enabled:true}") val lockEnabled: Boolean,
    @Value("\${channel.lock.name:harnax-channel-listeners}") val lockName: String,
) {

    private val log = LoggerFactory.getLogger(ChannelListenerLockGuard::class.java)

    /** Dedicated connection: MySQL named locks die with it, so it must stay open between polls. */
    private var connection: Connection? = null

    private var held = false

    /**
     * Refreshes the lock and reports whether this instance may run listeners.
     * Called at the head of every reconcile round.
     */
    @Synchronized
    fun hold(): Boolean {
        if (!lockEnabled) return true

        val conn = existingConnection() ?: return acquireOnNewConnection(ownershipProvenBefore = false)
        return try {
            // GET_LOCK is per-session and re-entrant: re-issuing it on the owning connection
            // returns 1, so this doubles as the liveness check for the lock.
            val stillOurs = getLock(conn)
            if (!stillOurs) {
                log.warn("Channel listener lock '{}' was released while we held it; re-establishing", lockName)
                held = false
                closeQuietly()
            }
            stillOurs
        } catch (e: Exception) {
            log.warn("Lost the listener-lock connection, re-acquiring: {}", e.message)
            // `held` records that this instance had proven ownership on the connection that just
            // broke; only that history earns the keep-serving benefit of the doubt below.
            val proven = held
            held = false
            closeQuietly()
            acquireOnNewConnection(ownershipProvenBefore = proven)
        }
    }

    fun isHeld(): Boolean = held

    @Synchronized
    @PreDestroy
    fun release() {
        if (!lockEnabled) return
        try {
            connection?.prepareStatement(RELEASE_SQL)?.use { ps ->
                ps.setString(1, lockName)
                ps.executeQuery().close()
            }
        } catch (e: Exception) {
            log.warn("Failed to release channel listener lock '{}': {}", lockName, e.message)
        } finally {
            held = false
            closeQuietly()
        }
    }

    /**
     * @param ownershipProvenBefore true only when this instance held the lock on the connection
     *   that was just lost. Without that history an unevaluable lock yields false: two replicas
     *   listening on one credential duplicate and drop user messages, which costs more than one
     *   round of idling while MySQL recovers.
     */
    private fun acquireOnNewConnection(ownershipProvenBefore: Boolean): Boolean = try {
        val conn = dataSource.connection
        connection = conn
        val acquired = getLock(conn)
        held = acquired
        if (acquired) {
            log.info("Acquired channel listener lock '{}'; this instance owns the listeners", lockName)
        } else {
            log.info("Channel listener lock '{}' is held by another instance; staying idle", lockName)
            closeQuietly()
        }
        acquired
    } catch (e: Exception) {
        held = false
        closeQuietly()
        if (ownershipProvenBefore) {
            log.error("Unable to re-evaluate channel listener '{}' after a connection loss; continuing as owner: {}", lockName, e.message)
            true
        } else {
            log.error("Unable to evaluate channel listener lock '{}'; not claiming ownership this round: {}", lockName, e.message)
            false
        }
    }

    private fun existingConnection(): Connection? = connection?.takeIf { runCatching { !it.isClosed }.getOrDefault(false) }

    private fun getLock(conn: Connection): Boolean = conn.prepareStatement(GET_LOCK_SQL).use { ps ->
        // Timeout 0: a busy lock means another instance owns it, and waiting would block the
        // scheduler thread that also drives every other reconcile tick.
        ps.setString(1, lockName)
        ps.setInt(2, 0)
        ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) == 1 }
    }

    private fun closeQuietly() {
        val conn = connection ?: return
        connection = null
        runCatching { if (!conn.isClosed) conn.close() }
            .onFailure { log.warn("Failed to close listener lock connection: {}", it.message) }
    }

    private companion object {
        const val GET_LOCK_SQL = "SELECT GET_LOCK(?, ?)"
        const val RELEASE_SQL = "SELECT RELEASE_LOCK(?)"
    }
}
