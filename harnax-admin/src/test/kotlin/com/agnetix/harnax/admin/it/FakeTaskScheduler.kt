package com.agnetix.harnax.admin.it

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A stand-in for `harnax-scheduler`'s task surface, for the admin integration tests.
 *
 * Release 2 left [com.agnetix.harnax.admin.controller.AgentTaskController] with nothing but a forward, so an IT
 * over these endpoints has two things left to prove that a unit test cannot: that the call reaches the other
 * service on the path, method and query the browser-facing contract names — with the contract-C4 identity
 * headers, through this application's real security and tenant filters and its real `SchedulerClient` bean —
 * and that whatever the other service answered is what the caller gets back.
 *
 * It therefore keeps a task table in memory and answers the way the scheduler does, including the refusal
 * sentences, because those are what the clients parse:
 *
 *  - a create that duplicates a name, fails the cron field count, or names an agent nobody resolved gets the
 *    500 sentence the real endpoint builds around its own business exception;
 *  - a write against an id that does not exist gets `400 / Agent task not found` — the answer that used to come
 *    out of admin's own database read and now comes from here, which is why the toggle case had to change
 *    direction (the gate moved with the data);
 *  - a request that arrives on any path outside the task surface is recorded in [otherPaths] rather than
 *    served, which is how the update and delete cases prove admin stopped notifying a reload.
 *
 * What it does not do is schedule anything: no Quartz and no reconcile, so it cannot invent 40901/40902/40903
 * of its own. Those codes are the scheduler's, and the relay of a body carrying one is pinned against a
 * scripted answer in `AgentTaskForwardingContractTest` rather than simulated here.
 */
class FakeTaskScheduler : Dispatcher() {

    private val json: ObjectMapper = jacksonObjectMapper()

    /** id -> the record, in the eighteen-key shape the scheduler answers a task in. */
    private val tasks = LinkedHashMap<Long, MutableMap<String, Any?>>()

    /** Log ids the caller owns, i.e. what the scheduler's `requireOwnedLog` would let through. */
    private val ownedLogs = mutableSetOf<Long>()

    /** Paths outside the task surface a call arrived on. Admin should no longer notify a reload at all. */
    val otherPaths = mutableListOf<String>()

    /** When set, every call is answered with one business failure, to check that a relay is a relay. */
    @Volatile
    var failNext: Boolean = false

    private var nextId = 1000L

    fun seedOwnedLog(logId: Long) {
        ownedLogs += logId
    }

    fun hasTask(id: Long): Boolean = tasks.containsKey(id)

    fun taskStatusOf(id: Long): Int? = tasks[id]?.get("taskStatus") as? Int

    fun agentNameOf(id: Long): String? = tasks[id]?.get("agentName") as? String?

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val route = path.substringBefore('?')
        if (failNext) {
            return business(500, "scheduler boom")
        }
        if (!route.startsWith(TASK_API)) {
            otherPaths += path
            return business(500, "Not served by the task surface: $path")
        }
        val rest = route.removePrefix(TASK_API)
        val method = request.method
        val body = request.body.readUtf8()
        return when {
            method == "GET" && rest == "/page" -> page(request)
            method == "GET" && rest.matches(ID_PATH) -> detail(id(rest))
            method == "POST" && rest.isEmpty() -> create(body)
            method == "PUT" && rest.matches(ID_PATH) -> update(id(rest), body)
            method == "DELETE" && rest.matches(ID_PATH) -> delete(id(rest))
            method == "POST" && rest.matches(TOGGLE_PATH) -> toggle(request, id(rest))
            method == "POST" && rest.matches(VERB_PATH) -> verb(rest)
            method == "POST" && rest.matches(STOP_PATH) -> stop(rest)
            method == "GET" && rest.matches(LOGS_PATH) -> success(pageEnvelope(emptyList(), 1L, 10L))
            else -> business(500, "Not served by the task surface: $path")
        }
    }

    // ==================== the eleven forwarded endpoints ====================

    private fun page(request: RecordedRequest): MockResponse {
        // A forward that named nobody is what C4 refuses, and this is the sentence the moved read uses.
        val caller = request.getHeader("X-Forwarded-User")
            ?: return business(500, "Failed to query agent task list: Not logged in")
        val query = request.path.orEmpty().substringAfter('?', "")
        val name = queryParam(query, "name")
        val agentId = queryParam(query, "agentId")?.toLongOrNull()
        val taskStatus = queryParam(query, "taskStatus")?.toIntOrNull()
        val pageNum = queryParam(query, "pageNum")?.toLongOrNull() ?: 1L
        val pageSize = queryParam(query, "pageSize")?.toLongOrNull() ?: 10L
        val visible = tasks.values.filter { task ->
            (name == null || task["name"].toString().contains(name)) &&
                (agentId == null || task["agentId"] == agentId) &&
                (taskStatus == null || task["taskStatus"] == taskStatus) &&
                // The owner rule lives here now: `is_public = 1 OR creator = ?`.
                (task["isPublic"] == 1 || task["creator"] == caller)
        }
        return success(pageEnvelope(visible, pageNum, pageSize))
    }

    private fun detail(id: Long): MockResponse {
        // "Agent task not found" is this service's answer now, in the 500 shell the moved endpoint kept.
        val task = tasks[id] ?: return business(500, "Agent task not found")
        return success(task)
    }

    private fun create(body: String): MockResponse {
        val sent = json.readTree(body)
        val missing = REQUIRED.firstOrNull { (field, _) -> sent.path(field).asText("").isEmpty() }
        if (missing != null) {
            // Bean validation answers this one with a 400 status as well as a 400 code.
            return MockResponse().status(400).vo(400, "${missing.first}: ${missing.second}")
        }
        val agentId = sent.path("agentId").asLong(0L)
        if (agentId == 0L) {
            return MockResponse().status(400).vo(400, "agentId: Agent ID is required")
        }
        val name = sent.path("name").asText()
        if (tasks.values.any { it["name"] == name }) {
            return business(500, "Failed to create agent task: Task name already exists")
        }
        if (sent.path("cronExpression").asText("").trim().split(WS).size !in 6..7) {
            return business(500, "Failed to create agent task: Invalid cron expression")
        }
        // The agent-name snapshot is admin's data to resolve; an absent one is the "Agent not found" this
        // service used to answer after its own agent lookup came back empty.
        val agentName = sent.path("agentName").asText("")
        if (agentName.isBlank()) {
            return business(500, "Failed to create agent task: Agent not found")
        }
        val id = nextId++
        tasks[id] = recordOf(
            id = id,
            name = name,
            agentId = agentId,
            agentName = agentName,
            prompt = sent.path("prompt").asText(),
            cronExpression = sent.path("cronExpression").asText(),
            timeoutSeconds = sent.path("timeoutSeconds").asInt(300),
            description = sent.path("description").asText(""),
            isPublic = sent.path("isPublic").asInt(0),
        )
        return success(null)
    }

    private fun update(id: Long, body: String): MockResponse {
        val task = tasks[id] ?: return business(400, "Agent task not found")
        val sent = json.readTree(body)
        sent.path("name").asText("").takeIf { it.isNotEmpty() }?.let { name ->
            if (name != task["name"] && tasks.values.any { it["name"] == name }) {
                return business(500, "Failed to update agent task: Task name already exists")
            }
            task["name"] = name
        }
        sent.path("cronExpression").asText("").takeIf { it.isNotEmpty() }?.let { cron ->
            if (cron.trim().split(WS).size !in 6..7) {
                return business(500, "Failed to update agent task: Invalid cron expression")
            }
            task["cronExpression"] = cron
        }
        if (sent.path("agentId").isNumber && sent.path("agentId").asLong() != task["agentId"]) {
            val agentName = sent.path("agentName").asText("")
            if (agentName.isBlank()) {
                return business(400, "Agent not found")
            }
            task["agentId"] = sent.path("agentId").asLong()
            task["agentName"] = agentName
        }
        patch(task, sent, "prompt")
        patch(task, sent, "description")
        if (sent.path("timeoutSeconds").isNumber) task["timeoutSeconds"] = sent.path("timeoutSeconds").asInt()
        if (sent.path("concurrent").isNumber) task["concurrent"] = sent.path("concurrent").asInt()
        if (sent.path("isPublic").isNumber) task["isPublic"] = sent.path("isPublic").asInt()
        // An edit always takes the task out of the running state, and the fake keeps that column honest.
        task["taskStatus"] = 0
        return success(null)
    }

    private fun delete(id: Long): MockResponse {
        if (tasks.remove(id) == null) {
            return business(400, "Agent task not found")
        }
        return success(null)
    }

    /**
     * `toggle` is where the visibility gate moved to: an id nothing the caller may see is refused *here*, after
     * the forward.
     */
    private fun toggle(request: RecordedRequest, id: Long): MockResponse {
        if (id !in tasks) {
            return business(400, "Agent task not found")
        }
        val status = queryParam(request.path.orEmpty().substringAfter('?', ""), "status")?.toIntOrNull()
            ?: return MockResponse().status(400).vo(400, "status: Required parameter status is missing")
        tasks[id]!!["taskStatus"] = status
        return success(null)
    }

    private fun verb(rest: String): MockResponse {
        val id = rest.substringBeforeLast('/').trimStart('/').toLongOrNull()
        if (id == null || id !in tasks) {
            return business(400, "Agent task not found")
        }
        tasks[id]!!["taskStatus"] = if (rest.endsWith("/pause")) 0 else 1
        return success(null)
    }

    private fun stop(rest: String): MockResponse {
        val logId = rest.trim('/').split('/')[1].toLongOrNull()
        if (logId == null || logId !in ownedLogs) {
            // The same answer the creator-only gate gives for "exists but is not yours".
            return business(400, "Agent task log not found")
        }
        return success(null)
    }

    // ==================== shape helpers ====================

    private fun patch(task: MutableMap<String, Any?>, sent: JsonNode, field: String) {
        // A field the request names is a field the row changes; anything else stays as stored.
        sent.path(field).takeIf { it.isString }?.let { task[field] = it.asText() }
    }

    private fun recordOf(
        id: Long,
        name: String,
        agentId: Long,
        agentName: String,
        prompt: String,
        cronExpression: String,
        timeoutSeconds: Int,
        description: String,
        isPublic: Int,
    ): MutableMap<String, Any?> = linkedMapOf(
        "id" to id,
        "tenantId" to 1L,
        "name" to name,
        "agentId" to agentId,
        "agentName" to agentName,
        "prompt" to prompt,
        "cronExpression" to cronExpression,
        "taskStatus" to 0,
        "concurrent" to 0,
        "timeoutSeconds" to timeoutSeconds,
        "description" to description,
        "isPublic" to isPublic,
        "creator" to CREATOR,
        "active" to 1,
        "createTime" to NOW,
        "updateTime" to NOW,
        // Present and null: a task that has never run, in the two keys the list joins in.
        "lastRunStatus" to null,
        "lastRunTime" to null,
    )

    /** The seven-key envelope three clients read, computed the way `Page` computes it. */
    private fun pageEnvelope(records: List<Map<String, Any?>?>, pageNum: Long, pageSize: Long): Map<String, Any?> {
        val pages = if (pageSize > 0) (records.size + pageSize - 1) / pageSize else 0L
        return linkedMapOf(
            "pageNum" to pageNum,
            "pageSize" to pageSize,
            "total" to records.size.toLong(),
            "records" to records,
            "pages" to pages,
            "hasPrevious" to (pageNum > 1),
            "hasNext" to (pageNum < pages),
        )
    }

    private fun queryParam(query: String, name: String): String? = query
        .split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=')
        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

    private fun id(rest: String): Long = rest.trimStart('/').substringBefore('/').toLong()

    /** An OK response carrying a business code: how every one of these endpoints answers a refusal. */
    private fun business(code: Int, message: String): MockResponse = MockResponse().vo(code, message)

    private fun success(data: Any?): MockResponse = MockResponse().vo(200, "success", data)

    private fun MockResponse.vo(code: Int, message: String, data: Any? = null): MockResponse = setHeader("Content-Type", "application/json")
        .setBody(json.writeValueAsString(linkedMapOf<String, Any?>("code" to code, "message" to message, "data" to data, "timestamp" to TIMESTAMP)))

    private fun MockResponse.status(httpStatus: Int): MockResponse = setResponseCode(httpStatus)

    companion object {
        private const val TASK_API = "/api/scheduler/agent-tasks"
        private const val NOW = "2026-07-02T09:00:00"
        private const val CREATOR = "admin"
        private const val TIMESTAMP = 1704067200000L
        private val WS = Regex("\\s+")
        private val ID_PATH = Regex("^/\\d+$")
        private val TOGGLE_PATH = Regex("^/toggle/\\d+$")
        private val VERB_PATH = Regex("^/\\d+/(start|pause|trigger)$")
        private val STOP_PATH = Regex("^/logs/\\d+/stop$")
        private val LOGS_PATH = Regex("^/\\d+/logs$")

        /** The create body's three required fields, with the validation texts the real DTO carries. */
        private val REQUIRED = listOf(
            "name" to "Task name is required",
            "prompt" to "Prompt is required",
            "cronExpression" to "Cron expression is required",
        )
    }
}
