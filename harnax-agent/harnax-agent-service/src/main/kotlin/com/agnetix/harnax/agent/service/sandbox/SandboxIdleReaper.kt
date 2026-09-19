package com.agnetix.harnax.agent.service.sandbox

import com.agnetix.harnax.harness.HarnessAgentLauncher
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodically reaps keep-alive sandboxes that nobody has used lately.
 *
 * [com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager.cleanupIdle] was reachable only from
 * `getOrCreate`, so releasing a container required another session to start: a service that went quiet
 * kept every container it had ever attached to, including the ones startup attaches to from Docker when
 * it restores them. This is the caller that makes the idle budget mean something.
 */
@Component
@ConditionalOnProperty(prefix = "harness.sandbox", name = ["enabled"], havingValue = "true")
class SandboxIdleReaper(
    private val launcher: HarnessAgentLauncher,
) {

    @Scheduled(
        fixedDelayString = "\${harness.sandbox.keep-alive-sweep-interval-ms:300000}",
        initialDelayString = "\${harness.sandbox.keep-alive-sweep-interval-ms:300000}",
    )
    fun reap() {
        launcher.keepAliveSandboxManager?.cleanupIdle()
    }
}
