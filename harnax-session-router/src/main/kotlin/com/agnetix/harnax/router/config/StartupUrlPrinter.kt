package com.agnetix.harnax.router.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Prints the URLs the operator cares about (monitor UI + monitor API) once the
 * application is fully started and the web server has bound to its port.
 *
 * Uses [ApplicationReadyEvent] (not `ContextRefreshedEvent`) so the port is
 * guaranteed to be live and any random-port binding has already resolved.
 *
 * The output is intentionally framed with banner separators so it stands out
 * in a long Spring Boot startup log even when console color is disabled.
 */
@Component
class StartupUrlPrinter(
    private val environment: Environment,
) {

    private val log = LoggerFactory.getLogger(StartupUrlPrinter::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady(event: ApplicationReadyEvent) {
        val port = resolveActualPort(event)
        val host = pickLocalHost()
        val baseUrl = "http://$host:$port"

        // Banner separator — uses simple ASCII so it survives any log encoder.
        val sep = "=".repeat(78)

        log.info("")
        log.info(sep)
        log.info("HARNAX // ROUTER is ready")
        log.info("  Monitor UI  : {}/ui", baseUrl)
        log.info("  Instances   : {}/api/router/monitor/instances", baseUrl)
        log.info("  Call logs   : {}/api/router/monitor/call-logs", baseUrl)
        log.info("  Health      : {}/api/router/health", baseUrl)
        log.info("  Swagger UI  : {}/swagger-ui.html", baseUrl)
        log.info(sep)
    }

    /**
     * Resolve the actual bound port, preferring the live WebServer (handles
     * random `server.port=0` or `PORT` env var overrides) and falling back
     * to the `server.port` property when no WebServer context is available
     * (e.g. in test slices).
     */
    private fun resolveActualPort(event: ApplicationReadyEvent): Int {
        val ctx = event.applicationContext
        if (ctx is WebServerApplicationContext) {
            runCatching { ctx.webServer?.port }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return environment.getProperty("server.port", Int::class.java)
            ?: environment.getProperty("local.server.port", Int::class.java)
            ?: 8081
    }

    /**
     * Best-effort localhost detection. We don't try to enumerate network
     * interfaces here — the operator usually just wants `localhost`.
     * If the deployment binds to a specific interface, the operator can
     * read the actual interface from the access log instead.
     */
    private fun pickLocalHost(): String = environment.getProperty("harnax.router.bind-host", String::class.java) ?: "localhost"
}
