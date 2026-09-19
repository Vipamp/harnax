package com.agnetix.harnax.harness.team

/**
 * Which side of a team one agent instance sits on.
 *
 * The role is what makes the two assemblies differ, and it is passed in by the runtime rather than
 * inferred from configuration, so no team member can talk its way into a lead's authority or the other
 * way round (design section 5).
 */
sealed class TeamRole {

    /** The runtime that owns the members' runs and the artifact scope. */
    abstract val orchestrator: TeamOrchestrator

    /** The session's own agent: it delegates and summarizes, and is assembled with nothing to execute. */
    class Lead(
        override val orchestrator: TeamOrchestrator,
    ) : TeamRole()

    /**
     * A delegated member: its own model, tools, MCP, skills, CLI and sandbox, plus the artifact tools
     * bound to [member] so it can hand work back.
     */
    class Member(
        override val orchestrator: TeamOrchestrator,
        val member: TeamMemberSpec,
    ) : TeamRole()
}
