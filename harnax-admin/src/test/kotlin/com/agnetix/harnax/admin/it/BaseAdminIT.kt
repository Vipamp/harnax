package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.HarnaxAdminApplication
import com.agnetix.harnax.admin.util.JwtUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path

/**
 * Base class for harnax-admin integration tests.
 *
 * Boots the full HarnaxAdminApplication on a random port against a shared
 * Testcontainers MySQL 8 instance (Flyway performs the schema migration).
 *
 * Authentication: tokens are signed directly with the application's JwtUtil
 * (admin userId=1 / username=admin / tenantId=1 / isAdmin=1) because the login
 * endpoint requires a captcha which cannot be bypassed.
 */
@SpringBootTest(
    classes = [HarnaxAdminApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("it")
abstract class BaseAdminIT {

    companion object {
        // Single shared container for all IT classes (started once, never stopped manually;
        // Ryuk reaps it after the JVM exits).
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_admin")
            .withUsername("root")
            .withPassword("it_test")

        init {
            mysql.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var jwtUtil: JwtUtil

    protected val json: ObjectMapper = jacksonObjectMapper()

    private val rest: RestTemplate by lazy {
        RestTemplate().apply {
            // Never throw on 4xx/5xx: tests assert on the ResultVo body instead
            errorHandler = object : ResponseErrorHandler {
                override fun hasError(response: ClientHttpResponse): Boolean = false
            }
        }
    }

    protected fun url(path: String): String = "http://localhost:$port$path"

    protected fun adminToken(): String = jwtUtil.generateToken(1L, "admin", 1L, 1)

    protected fun authHeaders(token: String = adminToken()): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        setBearerAuth(token)
    }

    protected fun exchange(method: HttpMethod, path: String, body: Any? = null, token: String? = adminToken()): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (token != null) setBearerAuth(token)
        }
        val payload: String? = when (body) {
            null -> null
            is String -> body
            else -> json.writeValueAsString(body)
        }
        return rest.exchange(url(path), method, HttpEntity(payload, headers), String::class.java)
    }

    protected fun getJson(path: String): JsonNode = parseBody(exchange(HttpMethod.GET, path))

    protected fun postJson(path: String, body: Any? = null): JsonNode = parseBody(exchange(HttpMethod.POST, path, body))

    protected fun putJson(path: String, body: Any? = null): JsonNode = parseBody(exchange(HttpMethod.PUT, path, body))

    protected fun deleteJson(path: String): JsonNode = parseBody(exchange(HttpMethod.DELETE, path))

    protected fun parseBody(response: ResponseEntity<String>): JsonNode {
        val body = response.body ?: error("Empty response body, status=${response.statusCode}")
        return json.readTree(body)
    }

    /**
     * Uploads a ZIP archive to /api/admin/skill-sources/upload.
     *
     * A ZIP source *is* the archive, so this is the only way to create one; [exchange] cannot be
     * used because it always serialises the body as JSON. The form converter adds the multipart
     * boundary itself, and the shared [rest] instance is used so a rejected upload surfaces as a
     * ResultVo body rather than an exception.
     *
     * @return the ResultVo node: `code`, plus `data.source` and `data.install` on success
     */
    protected fun uploadSkillZip(zip: Path, name: String): JsonNode {
        val form = LinkedMultiValueMap<String, Any>()
        form.add("file", FileSystemResource(zip))
        form.add("name", name)
        val headers = authHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        val response = rest.postForEntity(
            url("/api/admin/skill-sources/upload"),
            HttpEntity(form, headers),
            String::class.java,
        )
        return parseBody(response)
    }

    /** Assert ResultVo code == 200 and return the data node. */
    protected fun assertOk(node: JsonNode): JsonNode {
        check(node["code"].asInt() == 200) { "Expected code=200 but got: $node" }
        return node["data"] ?: json.nullNode()
    }

    /** Assert ResultVo code != 200. */
    protected fun assertErr(node: JsonNode): JsonNode {
        check(node["code"].asInt() != 200) { "Expected error code but got: $node" }
        return node
    }

    /** Find the first record in a Page response matching the predicate, paging through all pages. */
    protected fun findInPage(basePath: String, extraQuery: String = "", predicate: (JsonNode) -> Boolean): JsonNode? {
        var pageNum = 1
        while (true) {
            val sep = if (extraQuery.isEmpty()) "" else "&$extraQuery"
            val data = assertOk(getJson("$basePath?pageNum=$pageNum&pageSize=50$sep"))
            val records = data["records"]
            if (records == null || !records.isArray || records.isEmpty) return null
            records.forEach { if (predicate(it)) return it }
            val pages = data["pages"]?.asLong() ?: 1
            if (pageNum >= pages) return null
            pageNum++
        }
    }
}
