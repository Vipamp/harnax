package com.agnetix.harnax.router.controller

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Serves the bundled monitor UI at `/ui`.
 *
 * We don't use `/` because Spring Boot 4.x's `WelcomePageHandlerMapping`
 * always wins over `RequestMappingHandlerMapping` for the root path and
 * returns `Content-Length: 0` — a known upstream bug we can't reliably
 * override. Using `/ui` gives us a dedicated path that no other handler
 * competes for.
 *
 * The file is read directly from `classpath:/static/index.html` and returned
 * as the response body, avoiding any forward/redirect gymnastics.
 *
 * This endpoint is intentionally NOT annotated with `@InternalOnly`; access
 * control is expected to be enforced at the network boundary (nginx / VPC).
 */
@Controller
class RootController {

    @GetMapping("/ui")
    fun monitorUi(): ResponseEntity<String> {
        val html = this::class.java
            .getResourceAsStream("/static/index.html")
            ?.use { it.readAllBytes() }
            ?.toString(Charsets.UTF_8)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .body(html)
    }
}
