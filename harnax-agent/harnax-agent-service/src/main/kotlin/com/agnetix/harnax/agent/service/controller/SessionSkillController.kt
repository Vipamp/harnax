package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.auth.InternalOnly
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.skill.EnableOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A session's own skills: what has been drafted and what the operator has let this session use.
 *
 * Internal-only, like the workspace browsing next to it — nothing here is reachable from a browser or the
 * app directly. Both go through `harnax-session-router`, which is where the session's owner is checked
 * (`SessionRouterService.boundInstance` runs `sessionAccessGuard.requireAccessible` on its first line), and
 * nginx has no `/api/agent/` location to add a route to.
 *
 * The state lives in one container, so no session can read or enable another's skills even if a caller named
 * a different id: the handle is resolved per session id and nothing is shared.
 */
@RestController
@RequestMapping("/api/agent/session-skills")
@InternalOnly
@Tag(name = "Session Skills", description = "Per-session skill drafting and enabling APIs")
class SessionSkillController(
    private val launcher: HarnessAgentLauncher,
) {

    private val log = LoggerFactory.getLogger(SessionSkillController::class.java)

    @GetMapping("/{sessionId}")
    @Operation(summary = "List enabled skills", description = "List the skills this session may use")
    fun list(@PathVariable sessionId: String): ResultVo<List<SessionSkillView>> = ResultVo.success(
        launcher.sessionSkillStore.listEnabled(sessionId).map {
            SessionSkillView(it.name, it.description, it.enabledAt)
        },
    )

    @PostMapping("/{sessionId}/{name}/enable")
    @Operation(summary = "Enable one draft in this session", description = "Copy one draft into this session's enabled skills")
    fun enable(
        @PathVariable sessionId: String,
        @PathVariable name: String,
        @RequestBody(required = false) actor: EnableActorRequest? = null,
    ): ResultVo<EnableResultView> = when (val outcome = launcher.sessionSkillStore.enable(sessionId, name)) {
        is EnableOutcome.Enabled -> {
            log.info(
                "Skill {} enabled for session {} by user {} (verdict {})",
                outcome.name,
                sessionId,
                actor?.userId ?: "unknown",
                outcome.verdict,
            )
            ResultVo.success(
                EnableResultView(
                    ok = true,
                    name = outcome.name,
                    verdict = outcome.verdict,
                    findings = outcome.findings,
                    count = launcher.sessionSkillStore.listEnabledNames(sessionId).size,
                ),
            )
        }

        is EnableOutcome.Blocked -> ResultVo.error(
            403,
            "the security scan says ${outcome.verdict}: " + outcome.findings.joinToString("; "),
        )

        is EnableOutcome.Full -> ResultVo.error(
            409,
            "this session already has ${outcome.count} skills enabled; enable replaces one of them, it does not add a further one",
        )

        EnableOutcome.SourceMissing -> ResultVo.error(404, "no draft named '$name' to enable")

        is EnableOutcome.Failed -> ResultVo.error(500, "the copy into this session did not complete: ${outcome.reason}")

        EnableOutcome.NoSandbox -> ResultVo.error(
            410,
            "this session has no running sandbox, so there is nowhere to enable a skill into",
        )
    }
}

data class SessionSkillView(val name: String, val description: String?, val enabledAt: String?)

/** Who pressed the button, as the router resolved it (D11: this log line is the whole audit trail). */
data class EnableActorRequest(val userId: Long? = null)

data class EnableResultView(
    val ok: Boolean,
    val name: String? = null,
    val verdict: String? = null,
    val findings: Int = 0,
    val count: Int = 0,
)
