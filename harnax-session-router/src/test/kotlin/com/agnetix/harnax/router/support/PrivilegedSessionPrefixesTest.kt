package com.agnetix.harnax.router.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier

/**
 * `PrivilegedSessionPrefixes` and admin's prefix `when` are two copies of the same fact, spelled twice
 * in two modules. That split was accepted deliberately — the router must not depend on `harnax-admin`,
 * and hoisting the literals into `harnax-common` would put an admin routing table in front of every
 * module that reads it while still leaving admin's own `when` free to drift from it — but an accepted
 * copy is not a checked copy. Until now their agreement rested on nobody editing one side.
 *
 * [ADMIN_ROUTES] is the second copy: a mirror of `InternalApiController.getAgentSpec`, whose `when`
 * answers `Unknown sessionId prefix` for anything outside these four prefixes, transcribed into this
 * test and spelled independently of the router's constants on purpose. Transcribed, so it cannot on its
 * own notice admin's real code changing — what it does catch is the two spellings drifting apart in the
 * ways that happen by hand: casing, the separator character, leading whitespace, an anchored match
 * turning into a `contains`, and a prefix appearing on one side only. Admin's dispatch is pinned where
 * it lives, by `InternalApiControllerTest`; these cases pin the seam between the two.
 *
 * The risk is one-directional, so it is asserted in both:
 * - A prefix the router refuses that admin would not have resolved anyway is a dead rule. It buys no
 *   authorisation and can only cost real traffic — which is exactly how `chn-` came to be switched off
 *   by mistake.
 * - A prefix admin resolves from a table the guard's lookup cannot see, which the router does *not*
 *   refuse, is the original asymmetry: the hole `task-` was closed for. `chn-` used to sit there by
 *   choice, because admin's ownership endpoint read only the `session` table and so answered "unknown"
 *   for every channel session — and "unknown" is a pass. F3-A moved it out: admin now answers a `chn-`
 *   id's tenant from `channel`, so that prefix settles by the tenant comparison and needs no rule.
 *   [ADMIN_RESOLVED_BUT_NOT_REFUSED] is empty for exactly that reason, and stays the place any future
 *   server-decided prefix has to be judged before it lands in the gap unnoticed.
 */
class PrivilegedSessionPrefixesTest {

    /**
     * Where admin sends a session id in `getAgentSpec`, and — the half that decides whether the router
     * needs a rule for it — whether [com.agnetix.harnax.router.service.SessionAccessGuard]'s ownership
     * lookup can be told who owns the result.
     */
    private enum class AdminRoute {
        /** From the `session` table: a conversation the caller owns. */
        CALLER_OWNED,

        /**
         * From a table the ownership lookup never consults: a conversation the server decided, and one
         * no query can therefore attribute. Only a prefix rule can guard these.
         */
        SERVER_DECIDED,

        /**
         * Server-decided in shape — the caller did not pick this id — but admin's ownership endpoint
         * answers for it, so the tenant comparison in
         * [com.agnetix.harnax.router.service.SessionAccessGuard] settles it and a prefix rule would only
         * remove access. `chn-` moved here when admin started reading the `channel` table for it.
         *
         * Putting a prefix in this category *is* the judgement that the comparison is sufficient for it.
         * That is not true of every answerable id: a prefix whose ids are enumerable, and where being in
         * the same tenant does not entitle a caller to the row (a task of someone else's, say), still
         * needs the rule — such a prefix belongs in its own category with the reason spelled out, not
         * here.
         *
         * The entry fee is [ATTRIBUTABLE_OWNER_SOURCES]: a prefix lands here only with the `table.column`
         * admin reads its owner from attached to it. This is the category the residue check does not look
         * at, so an unpriced entry here is a decision nobody has to defend.
         */
        SERVER_DECIDED_BUT_ATTRIBUTABLE,

        /** Answered `Unknown sessionId prefix`. */
        UNRESOLVED,
    }

    private data class AdminPrefix(
        val prefix: String,
        val route: AdminRoute,
    )

    private fun adminRouteOf(sessionId: String): AdminRoute = ADMIN_ROUTES
        .firstOrNull { sessionId.startsWith(it.prefix) }
        ?.route
        ?: AdminRoute.UNRESOLVED

    /**
     * The router's own prefixes, read off the object it declares them on rather than restated here, so
     * the comparison below is between two independent declarations and not between two copies of one.
     */
    private fun routerDeclaredPrefixes(): List<String> = PrivilegedSessionPrefixes::class.java.fields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = [
            // The real shape since contract C1: task id, agent id, then a random tail with no dashes in it.
            "task-42-100-6f0b1a2c3d4e5f60718293a4b5c6d7e8",
            // The shapes the producer wrote before C1. A prefix rule has to be blind to the segment count,
            // so both spellings still have to land on the same side of the line.
            "task-7-6f1d0a2e",
            "task-42-1f0e2d3c-4b5a-6978-8a9b0c1d2e3f",
            "task-",
            // Case variants: neither side folds case, so both must treat these as not-a-task.
            "TASK-7-6f1d0a2e",
            "Task-7-6f1d0a2e",
            // A leading space: no longer a prefix, and admin resolves nothing of that shape either.
            " task-7-6f1d0a2e",
            // Separator variants: the grammar is `task-`. A copy that drifted to another separator
            // would refuse what admin cannot resolve, or pass what it can.
            "task_7-6f1d0a2e",
            "task.7-6f1d0a2e",
            "task:7-6f1d0a2e",
            "task7-6f1d0a2e",
            // Mid-string: pins both sides as anchored. A `contains` on either would diverge here.
            "web-task-7",
            "mytask-7",
            // The server-decided prefix admin can now attribute, and the caller's own sessions.
            "chn-da0b56ff-c712-4bb6-8536-3b3e88b1818b",
            "chn-xyz",
            "web-1f0e2d3c",
            "mp-1f0e2d3c",
        ],
    )
    fun `the router's refusal and admin's resolution agree on every shape of session id`(sessionId: String) {
        val refused = PrivilegedSessionPrefixes.matches(sessionId)
        val route = adminRouteOf(sessionId)

        if (refused) {
            assertEquals(
                AdminRoute.SERVER_DECIDED,
                route,
                "$sessionId is refused to every end-user caller by the router, but admin would not have " +
                    "resolved it from a server-decided table — it answers $route. A prefix rule that " +
                    "refuses what admin cannot resolve buys nothing and can only take traffic offline.",
            )
        }

        if (route == AdminRoute.SERVER_DECIDED && ADMIN_RESOLVED_BUT_NOT_REFUSED.none { sessionId.startsWith(it) }) {
            assertTrue(
                refused,
                "$sessionId: admin resolves it from a table the guard's ownership lookup never sees, yet the " +
                    "router lets an end-user caller name it. That asymmetry is the hole this rule exists to " +
                    "close. If it is meant to stay open, record the prefix in ADMIN_RESOLVED_BUT_NOT_REFUSED " +
                    "and say why.",
            )
        }

        if (route == AdminRoute.SERVER_DECIDED_BUT_ATTRIBUTABLE) {
            assertFalse(
                refused,
                "$sessionId: admin answers who owns this id, so the guard's tenant comparison can already " +
                    "decide it and a prefix rule adds no decision — it only denies the legitimate " +
                    "same-tenant reads, which is how `chn-` was wrongly switched off once.",
            )
        }
    }

    /**
     * The same two directions at the level of whole prefix sets, so the drift a shape table cannot see
     * — a prefix added to one side only — is what fails.
     */
    @Test
    fun `the two copies name the same server-decided prefixes`() {
        val routerRefused = routerDeclaredPrefixes()
        val adminResolves = ADMIN_ROUTES.filter { it.route == AdminRoute.SERVER_DECIDED }.map { it.prefix }

        assertEquals(
            setOf(PrivilegedSessionPrefixes.TASK),
            routerRefused.toSet(),
            "the router's privileged prefixes changed; re-read which of them carry an enumerable id",
        )
        assertTrue(
            adminResolves.containsAll(routerRefused),
            "the router refuses prefixes admin does not resolve from a server-decided table: " +
                (routerRefused - adminResolves.toSet()),
        )

        val unjudged = adminResolves
            .filter { prefix -> routerRefused.none { it == prefix } }
            .filter { prefix -> ADMIN_RESOLVED_BUT_NOT_REFUSED.none { prefix.startsWith(it) } }
        assertTrue(
            unjudged.isEmpty(),
            "admin resolves these from a table the router cannot query, and neither a refusal nor a " +
                "recorded residual covers them: $unjudged",
        )

        routerRefused.forEach { prefix ->
            assertTrue(
                PrivilegedSessionPrefixes.matches(prefix + "1"),
                "$prefix is declared as privileged but the matcher does not act on it",
            )
        }
    }

    /**
     * The attributable tier has to cost something to enter, or it is a door out of the hole list rather
     * than a judgement about it.
     *
     * The set-level check above only ever reads the `SERVER_DECIDED` side of [ADMIN_ROUTES] — both
     * `adminResolves` and the `unjudged` filter start from that one route — so a prefix classified as
     * [AdminRoute.SERVER_DECIDED_BUT_ATTRIBUTABLE] is filtered out before the residue assertion runs.
     * Without this pin, adding such a prefix would fail nothing: [ADMIN_RESOLVED_BUT_NOT_REFUSED] keeps
     * reading `emptyList()`, and the hole sits one category over where no assertion looks. Naming the
     * table each attributable prefix is attributed to, and requiring that name to be a `table.column`,
     * is what makes the claim "admin can answer who owns this" something you have to write down rather
     * than just assert.
     */
    @Test
    fun `every attributable prefix names the admin table that answers its owner`() {
        val attributable = ADMIN_ROUTES
            .filter { it.route == AdminRoute.SERVER_DECIDED_BUT_ATTRIBUTABLE }
            .map { it.prefix }

        assertEquals(
            attributable.toSet(),
            ATTRIBUTABLE_OWNER_SOURCES.keys,
            "attributable prefixes and their owner-query pairing have drifted apart. A prefix enters " +
                "SERVER_DECIDED_BUT_ATTRIBUTABLE only by saying which admin read answers its " +
                "owner, and the pairing names no prefix that is not classified that way.",
        )

        val adminResolves = ADMIN_ROUTES.filter { it.route != AdminRoute.UNRESOLVED }.map { it.prefix }.toSet()
        assertTrue(
            adminResolves.containsAll(ATTRIBUTABLE_OWNER_SOURCES.keys),
            "an owner query is recorded for a prefix admin does not resolve at all: " +
                (ATTRIBUTABLE_OWNER_SOURCES.keys - adminResolves),
        )

        val routerRefused = routerDeclaredPrefixes().toSet()
        assertTrue(
            ATTRIBUTABLE_OWNER_SOURCES.keys.none { it in routerRefused },
            "a prefix the router refuses cannot also be recorded as settled by the ownership lookup: the " +
                "rule runs before the lookup, so one of the two claims is wrong: " +
                (ATTRIBUTABLE_OWNER_SOURCES.keys.filter { it in routerRefused }),
        )
        assertTrue(
            ATTRIBUTABLE_OWNER_SOURCES.keys.none { it in ADMIN_RESOLVED_BUT_NOT_REFUSED },
            "a prefix cannot be both an open hole and attributable to a table that answers it: " +
                (ATTRIBUTABLE_OWNER_SOURCES.keys.filter { it in ADMIN_RESOLVED_BUT_NOT_REFUSED }),
        )

        ATTRIBUTABLE_OWNER_SOURCES.forEach { (prefix, source) ->
            assertTrue(
                OWNER_SOURCE_PATTERN.matches(source),
                "$prefix claims its owner comes from `$source`, which is not a `table.column` — an " +
                    "attributable prefix has to name the column the ownership read matches on.",
            )
        }
    }

    private companion object {
        /**
         * Spelled independently of [PrivilegedSessionPrefixes] — see the class comment.
         *
         * The attributability half of each entry is a claim about admin's `/sessions/{id}/info`. It is
         * pinned where the claim lives — `InternalApiControllerTest`'s session-info cases prove a `chn-`
         * id answers with the `channel` row's tenant, soft-deleted or not, and an id with no row still
         * answers "no such session" — and pinned mechanically here by [ATTRIBUTABLE_OWNER_SOURCES],
         * because the classification alone is what the residue check walks past.
         */
        val ADMIN_ROUTES = listOf(
            AdminPrefix("web-", AdminRoute.CALLER_OWNED),
            AdminPrefix("mp-", AdminRoute.CALLER_OWNED),
            AdminPrefix("chn-", AdminRoute.SERVER_DECIDED_BUT_ATTRIBUTABLE),
            AdminPrefix("task-", AdminRoute.SERVER_DECIDED),
        )

        /**
         * What it costs to call a prefix attributable: the `table.column` admin's `/sessions/{id}/info`
         * matches on to name the owner of one of its ids. The keys are asserted to be exactly the
         * [AdminRoute.SERVER_DECIDED_BUT_ATTRIBUTABLE] prefixes of [ADMIN_ROUTES], so this entry cannot
         * be dropped behind the classification nor added to it without saying where the answer comes from
         * — the test above that pins this map is what enforces it, and says what hole it closes.
         *
         * `channel.session_id` is the ownership read, and it reads the row whatever its `active` flag
         * says: a soft-deleted channel still has an owner, and "no owner" is the answer the router passes.
         */
        val ATTRIBUTABLE_OWNER_SOURCES = mapOf("chn-" to "channel.session_id")

        /** `table.column`, lower-case — the shape of [ATTRIBUTABLE_OWNER_SOURCES]'s values. */
        val OWNER_SOURCE_PATTERN = Regex("[a-z_]+\\.[a-z_]+")

        /**
         * The prefixes admin resolves from a table the guard's ownership lookup cannot see, and which the
         * router therefore does not refuse. Empty since F3-A taught that lookup the `channel` table:
         * `chn-` was the only entry, and it was open because there was no answer to check, not because
         * the traffic was trusted.
         *
         * Keep it empty. A prefix that belongs here and is not refused is the asymmetry `task-` was
         * closed for, and the list is what makes the next server-decided prefix get judged before it
         * lands in that gap.
         */
        val ADMIN_RESOLVED_BUT_NOT_REFUSED = emptyList<String>()
    }
}
