package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.adaptor.SkillUsageAdaptor
import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns "these skills were just read out of the delivered set" into `VIEW` events for Admin.
 *
 * One instance belongs to one agent build, so its state is one session's view of which skills are in play.
 * The delivered [AgentSkill] carries no database id — Admin's `skill.id` lives in the spec that produced it
 * — so the launcher attributes each name to its id as it adds the skill, and a name nobody attributed is
 * reported not at all rather than guessed at. The end user is fixed the same way, once per build: one build
 * is one run and one run has one identity, so no report of it can disagree with any other.
 *
 * Throttled by design. The harness re-reads its repositories every time it composes a system prompt, which
 * is once per model call inside a single answer, so an unthrottled recorder would write one event per skill
 * per iteration and turn a count of loads into a count of prompt renders. One event per skill per
 * [cooldownMillis] keeps the number answering the question the usage page actually asks — was this skill
 * loaded into a context during this window — and bounds how often a running session calls Admin at all.
 *
 * Never throws and never blocks on the network: [SkillUsageAdaptor] carries that contract, and this adds
 * the guard for an adaptor that breaks it.
 */
class SkillViewRecorder(
    private val sessionId: String,
    private val userId: Long?,
    private val adaptor: SkillUsageAdaptor,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val log = LoggerFactory.getLogger(SkillViewRecorder::class.java)
    private val idsByName = ConcurrentHashMap<String, Long>()
    private val lastReportedAt = ConcurrentHashMap<Long, Long>()

    /**
     * Records which Admin skill id stands behind one delivered skill name.
     */
    fun attribute(skillName: String, skillId: Long) {
        idsByName[skillName] = skillId
    }

    /**
     * Report the skills just read, skipping any already reported inside the current window.
     */
    fun onRead(skills: Collection<AgentSkill>) {
        if (skills.isEmpty()) return
        val now = clock()
        val due = skills.mapNotNull { idsByName[it.name] }.distinct().filter { id ->
            val previous = lastReportedAt[id]
            previous == null || now - previous >= cooldownMillis
        }
        if (due.isEmpty()) return
        due.forEach { lastReportedAt[it] = now }
        try {
            adaptor.reportViews(sessionId, due, userId)
        } catch (e: Exception) {
            // A counter is not worth an error banner in front of the user, and the events this batch
            // covered will be picked up by the next read of the same skill
            log.warn("Skill usage reporting failed for session {} ({} skill(s) dropped): {}", sessionId, due.size, e.message)
        }
    }

    companion object {
        /** Long enough that one answer's ReAct iterations count once, short enough to stay near-real-time. */
        const val DEFAULT_COOLDOWN_MILLIS = 60_000L
    }
}
