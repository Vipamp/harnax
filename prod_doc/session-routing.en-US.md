# Harnax Session Router Design (English)

This document covers the current complete implementation of `harnax-session-router` (Router below): capability boundary, authentication layering, endpoint contracts, session binding and instance lifecycle, the SSE streaming chain, tenant isolation, call logging, deployment shape and observability definitions, plus the list of current limits. Every statement is taken from the code on the `kotlin-dev` branch.

## 1. Capability and boundary

### 1.1 Where session stickiness comes from

A conversation (`sessionId`) executes on the same agent-service instance for its whole lifetime. The reason is that the Agent's mutable state does not live in a shared database, it lives in that process's memory and on its local disk:

- the Agent instance object with its ReAct loop context and the cached agents (`agentCache`);
- the workspace sandbox container and the plan in progress inside it;
- the suspended state of a HITL tool confirmation (it only resumes when `/confirm` reaches the same instance).

Router supplies the placement decision and the request forwarding for this "session → instance" constraint. It listens on **8081**, runs Spring MVC with Kotlin coroutines, keeps no state of its own in the process, and scales out horizontally (replicas share one copy of the routing state in Redis).

### 1.2 What Router does and does not own

| Concern | Owner |
|------|------|
| Session-to-instance binding, placement, rerouting, circuit breaking | Router |
| Request forwarding and streaming responses back | Router |
| Running the Agent, calling models | `harnax-agent-service` (:8082) |
| Storing conversation history, plans, workspace files | agent-service (state store + snapshot) |
| Whether an API Key is valid and which tenant it belongs to | `harnax-admin` (Router queries it remotely and caches locally) |
| Which tenant owns a session | admin internal endpoint `/api/admin/internal/sessions/{id}/info`; Router only queries, it never adjudicates |
| Channel message parsing and delivery back | `harnax-channel` |
| Scheduled task dispatch | `harnax-scheduler` (:8084) |

The complete set of state Router holds is: the instance registry, session bindings, the reverse index, circuit-breaker state, idempotency leases and call logs. It holds no business data at all.

## 2. Topology and callers

### 2.1 Topology

```
                       +- channel-service  (platform user messages, internal JWT)
                       +- webui            (admin console, login JWT or API Key)
 external callers -- nginx --+-- harnax-app      (H5 / mini program, API Key)
    80 -> 443                +- harnax-client SDK (system integration, API Key)
                             +- harnax-admin     (session and workspace calls, API Key)
                                |
                                v  X-Api-Key or Bearer JWT
                       +--------------------+
        +--------------|   Router :8081     |-----------+
        |              +--------------------+           |
        |                        |                       v  internal JWT
   admin :8080                Redis :6379        agent-service :8082 (xN)
 (key validation, session    (instance registry,  |
   ownership, raw secret      session bindings,   | register / heartbeat
   string comparison)         breaker, leases)    v
                             MySQL :3306   +-- POST /api/router/instance/*
                           (call log db)   |  agent-service registers itself
                                           +-- call log persisted (api_call_log)
```

### 2.2 Caller matrix

| Caller | Endpoints used | Credential | Notes |
|--------|-----------|------|------|
| channel-service | `/agent/chat/stream`, `/agent/command`, `/agent/session/{id}` DELETE | internal JWT (`typ=internal`) | Forwards on behalf of an already authenticated user; carries no tenant, so no tenant comparison happens |
| webui | all of `/agent/**` (workspace included), `/monitor/**` | Bearer JWT or `X-Api-Key` | reaches Router through the nginx `/api/router/` location |
| harnax-app (H5 / mini program) | the chat + workspace subset | `X-Api-Key` | may hit 28081 directly or go through nginx |
| harnax-client SDK | the matching `/agent/**` methods | `X-Api-Key` | — |
| agent-service | `/instance/register`, `/instance/heartbeat`, `/instance/unregister` | internal JWT | `@InternalOnly`; any external credential gets 403 |
| admin / operators | `/instance/drain`, `/instance/list`, `/health`, `/metrics/cache`, `/monitor/**` | internal JWT (`/monitor/**` also accepts an admin Key) | — |
| scheduler | `/agent/chat`, `/agent/command`, `/agent/session/{id}` DELETE | whatever its own configuration says | goes through the `task-` prefix rule, see the session id prefix rules |

## 3. Authentication and authorization layers

### 3.1 Inbound: API Key and internal JWT

`UnifiedAuthFilter` (`harnax-auth`, `@Order(HIGHEST_PRECEDENCE + 10)`) establishes an `AuthContext` before `/api/router/**`:

| Step | Behaviour |
|------|------|
| Auth-exempt prefixes | `/health`, `/actuator` plus `harnax.auth.skip-paths` (static resources only) |
| `Authorization: Bearer …` | `InternalTokenProvider.verifyToken` validates the signature and classifies the token; failure is 401 |
| `X-Api-Key: …` | `ExternalApiKeyValidator` hashes with SHA-256 and looks the digest up in `ApiKeyStore`; failure is 401 |
| Neither present | 401 with a `ResultVo` body |

The classification rules inside `verifyToken` are what separate internal from external:

- a `typ=internal` claim → `CallerType.INTERNAL_SERVICE`, optionally with `userId` / `tenantId`;
- no `typ` but a `userId` → handled as `EXTERNAL_API` (a JWT signed at login never obtains an internal identity, even if an operator points `jwt.secret` and `harnax.auth.internal.shared-secret` at the same value);
- neither `typ` nor `userId` → throws `SecurityException`; that bearer is not accepted.

On the API Key side, `RemoteApiKeyStore` (`@Primary`) calls admin's `/api/admin/internal/api-keys/validate` and caches 1000 entries for 5 minutes after write with Caffeine, caching both hits and misses; `enabled=false` or a passed `expiresAt` → 401. The Key's tenant and owner identity come entirely from admin's answer; Router never interprets the Key itself.

### 3.2 API Key scope: chat / manager

`scopes` is a comma-separated string. admin writes `chat` when a Key is created without an explicit value, and the management console offers `chat` (conversation) and `manager` (administration). Router carries scopes into the `AuthContext` unchanged, but **none of its admission decisions read scopes**:

- internal-only endpoints are decided by `callerType` (`InternalAuthorizationInterceptor` accepts `INTERNAL_SERVICE` only);
- session-scoped endpoints are decided by tenant ownership (`SessionAccessGuard`);
- rate is decided by the Key's `rateLimit`.

So "what a given Key may do" on the Router side is equivalent to: whether it is an internal or an external identity, which tenant it belongs to, and how many requests per minute it may send. The `chat` versus `manager` distinction is applied by admin's management endpoints.

### 3.3 Outbound: Router to agent-service and to admin

| Target | Authentication | Implementation |
|------|----------|------|
| agent-service | `Authorization: Bearer <internal JWT>` + `X-Caller-Id` | `RouterConfig.authFilter()` asks `InternalTokenProvider.authHeaders()` for headers on every request; token TTL 300s, re-signed when fewer than 60s remain |
| admin internal API | `Authorization: Bearer <raw secret string>` | `AdminClientService` attaches a `defaultHeader` when building its WebClient, value from `admin.internal-api.secret` |

admin's `InternalApiAuthFilter` guards `/api/admin/internal/**` with a **raw string equality comparison** (`token == secret`); it does not parse a JWT. If a caller sends a JWT by mistake, the 401 response text says exactly that. The two outbound chains use different credential systems, so `HARNAX_AUTH_SECRET` and `ADMIN_INTERNAL_API_SECRET` have to be aligned separately in configuration.

Router calls admin for two things: Key validation (`validateApiKey`) and session-ownership lookup (`lookupSession`). The session id is assembled with `pathSegment()` rather than string interpolation, so that an id containing `../` cannot point the request at a different internal endpoint and carry this secret with it.

### 3.4 Rate limiting and async dispatch

- `RateLimitInterceptor` applies to `EXTERNAL_API` only, counting per `callerId` in a 60-second sliding window; exceeding the limit is **HTTP 429** + `Retry-After: 60` with a `ResultVo(429, …)` body. A Key with an empty `rateLimit` is not limited.
- Router's counter is in-process (`RateLimiter`); with several replicas each one counts on its own.
- `InternalAuthorizationInterceptor` decides on `DispatcherType.REQUEST` only. The asynchronous dispatch (SSE) is not re-checked, because `AuthContextHolder` is thread-local.

## 4. Session binding and instance lifecycle

### 4.1 Redis key layout

In `redis` mode all shared state lives in Redis, values serialized as JSON (strings carry quotes), key names serialized as plain text:

| Key | Type | Content | Lifetime |
|----|------|------|------|
| `router:session:<sessionId>` | String | the bound `instanceId` | 24h, extended by every request |
| `router:instance_sessions:<instanceId>` | Set | the sessionId set on that instance (reverse index; the basis for load statistics and for moving sessions) | 24h, extended together with the bindings inside it |
| `router:lock:session:<sessionId>` | String | placement mutex token | 10s |
| `router:instance:<instanceId>` | Hash | `host` / `port` / `status` / `lastHeartbeat` / `active` | 24h, extended by heartbeat |
| `router:instances:healthy` | Set | instances that accept new sessions | — |
| `router:instances:all` | Set | every registered instance | — |
| `router:circuit:<instanceId>` | Hash | breaker state and consecutive failure count (0 closed / 1 open / 2 half-open) | open duration 30s |
| `router:idempotency:<requestId>` | String | lease for a request in flight | 60s, deleted when the request ends |
| `router:lock:index_reconcile` | String | global lock for reverse-index reconciliation | 4min |

One state change touches at least two keys (binding + reverse index, or instance status + the healthy set), so every change is **one multi-key Lua script**: `BIND` / `CLAIM` (established with NX) / `MOVE_IF_FROM` (compare-and-swap relocation) / `UNBIND_SESSION` / `REFRESH_TTL` / `REBIND_BATCH` / `UNBIND_BATCH` / `HEARTBEAT` / `MARK_DOWN` / `MARK_DRAINING` / lock release. Doing read-modify-write in Kotlin lets a second replica overwrite the first replica's binding and leaves index entries pointing at an instance that does not serve the session.

### 4.2 Binding lifecycle

| Event | Behaviour |
|------|------|
| First request for an unbound session | placement decision + atomic `CLAIM` write |
| A successful request through the session | `refreshActiveTime` extends the binding and the reverse index together |
| No request for 24h | binding and index expire together; the next request is placed afresh |
| The bound instance goes DOWN | relocated to other instances in batches by the health checker |
| Instance `unregister` | `unbindInstanceSessions` deletes every binding on that instance |
| Clearing a session (`DELETE /agent/session/{id}`) | forwarded to the instance only; the binding stays |

The TTL is one constant in both cache modes: `LocalSessionMappingService`'s `bindingTtl` defaults to `RedisSessionMappingService.SESSION_TTL` (24h). The 24h says "once a session has been out of Router's sight for a day, it may be given a different instance"; it has nothing to do with the session's business validity, whose lifecycle lives in admin's session table.

### 4.3 Placement decision

`selectLeastLoadedInstance` runs a weighted random draw over the candidates with weight `1 / (current session count + 1)`, and returns immediately when there is a single candidate. Candidates come from `instanceRegistry.getHealthyInstances()`, minus:

- instances the caller already saw fail (the `excluded` set);
- instances with the breaker open (`placementExclusions` → `circuitBreaker.trippedInstances`);
- DRAINING instances (not in the healthy set; `MARK_DRAINING_SCRIPT` removes them from it).

**Placement exclusions affect new sessions only.** A session already bound to an instance is not relocated because that instance's breaker tripped — relocating on a breaker reading would move every session on that instance at once, turning one slow instance into a cluster-wide rebinding storm, and the next request's binding would just come back. The only trigger for moving a session is the instance going DOWN.

`rerouteSession` handles concurrency: it first tries to take the 10-second session lock (failing to take it is not a failure; the lock only makes placements tidier), then up to 3 rounds of read-select-compare-and-swap. If another replica already put the session onto an acceptable instance, that placement is adopted and extended. When Redis refuses the write, `placeUnpersisted` takes over (see the behaviour when Redis is unreachable). With no healthy candidate at all, the existing binding is kept when its instance still accepts new sessions; otherwise `IllegalStateException("No healthy agent-service instances available for session …")` is thrown.

### 4.4 Instance state machine

```
(new / re-register)  --register----------> UP        writes status=UP and adds the id to the healthy and all sets
UP                   --heartbeat---------> UP        refreshes lastHeartbeat and the TTL
DOWN                 --heartbeat---------> UP        DOWN is reached only through heartbeat staleness, so one successful heartbeat reclaims it
UP                   --/instance/drain---> DRAINING  removed from the healthy set
DRAINING             --heartbeat---------> DRAINING  a heartbeat keeps DRAINING instead of resetting it to UP
UP / DRAINING        --heartbeat silent 30s --> DOWN CAS decision, one replica wins; the health check scans every 5s
any state            --unregister--------> (the instance key is deleted and the id leaves both sets)
```

| State | Accepts new sessions | Existing bindings keep serving | Counted as a healthy instance |
|------|-----------|------------------|----------------|
| `UP` | yes | yes | yes |
| `DRAINING` | no | yes | no |
| `DOWN` | no | triggers a batch relocation | no |

**A heartbeat can reclaim DOWN**: unless the state is DRAINING, `HEARTBEAT_SCRIPT` writes the state back to UP and re-adds the instance to the healthy set, so a DOWN caused by one network blip rejoins the fleet as soon as that instance's heartbeat returns, with no re-registration needed — but sessions already moved away do not move back. `MARK_DOWN_SCRIPT` returns 0 for an instance that is already DOWN, so one instance is never relocated twice.

`AgentInstance.isHealthy` counts DRAINING as alive — the state means "take no new sessions", not "dead". Treating it as unhealthy would let the health checker mark it DOWN within one scan cycle, forcibly move every session away, and make graceful draining pointless.

**drain is a one-way door**: there is no `undrain` endpoint, and the heartbeat script explicitly preserves DRAINING instead of resetting it to UP. The only way back to accepting new sessions is for that instance to `register` again. drain therefore serves "about to go away", not "pull myself out of rotation for a moment to read the logs".

### 4.5 Failover and relocation

`HeartbeatHealthChecker.checkInstanceHealth` (`@Scheduled`, fixed delay 5s):

1. handles every instance where `!isHealthy(30s)` one at a time, re-reading its health snapshot each time, so sessions are not moved from one just-dead instance to another instance dying in the same batch;
2. applies a 10-second cooldown per instance for relocation (`failoverCooldownMs`, hardcoded); the cooldown record is swept every 60 seconds and entries are treated as expired after 5 minutes;
3. `markInstanceDown` is CAS: a return of 0 rows means another replica already decided, so this one skips;
4. targets exclude breaker-tripped instances (when everything is tripped, nothing is excluded — a session stuck in place is worse than one move), choosing the least loaded;
5. overload avoidance: when the target's session count exceeds cluster mean × 2, the least loaded instance at or below mean × 1.5 is chosen instead;
6. `rebindAllSessions` moves sessions with `REBIND_BATCH_SCRIPT`, 500 per batch, at most 200 batches; whatever does not fit is left to the reconciler;
7. the reverse index is backed up by `SessionIndexReconciler`: one pass every 5 minutes, a global lock keeping it single-node, two sweeps (drop index entries whose binding is gone, re-add index entries that were lost), at most 50000 keys per sweep, the remainder left for the next round.

### 4.6 Eviction semantics: STOP_SANDBOX, not CLEAR

After a session moves, `SessionEvictor` tells the instance it left to stop that session's sandbox:

- the command sent is `CommandType.STOP_SANDBOX`. `CLEAR` would delete the conversation history and the plans along with it, and those live in a state store shared by every instance — one instance losing a session must not delete what the next instance still needs;
- `STOP_SANDBOX` persists a workspace snapshot before destroying the container, so a session that returns to this instance still finds its files;
- before sending, the binding store is asked to confirm "this session really left". During a Redis outage placements are recorded on this node only, and evicting on that basis alone would stop the sandbox of a session that never moved;
- it runs on a dedicated pool (1 to 2 threads, queue `router.migration.max-pending=200`); overflow is dropped and counted in a warning, request threads are never occupied, and failures are not propagated to the caller;
- controlled by `router.migration.evict-old-instance` (default `true`).

### 4.7 Clearing a session and release failures

Behaviour of `DELETE /api/router/agent/session/{sessionId}`:

| Case | Result |
|------|------|
| This Router never placed the session | returns `code=200` directly, the message states it was unbound; no instance is contacted |
| Bound | forwards `DELETE /api/agent/session/{id}` to that instance exactly once, never retried against another instance |

The agent-side chain is: `DefaultAgentRunner.clearSession` → first `interrupt`, clear `agentCache`, clear the state store and the plan notes, and clean up team sub-sessions one by one; then `KeepAliveSandboxManager.destroy` persists the workspace snapshot before `docker rm -f`. **The snapshot is kept**; the history and the plans are removed.

What Router does not do: it does not clear the binding. After the records are cleared the session still points at the same instance.

Release failure is handled on the admin side: `SessionRuntimeReleaser.release` reads `ResultVo.isSuccess()` from Router's reply and throws a `BizException` carrying the runtime's own message when it is not a success. Deleting a session (management console and mobile) and deleting a channel (whose `chn-{uuid}` session has no separate session row) are therefore both built on "if the runtime says no, this delete is not written". Unbound is treated as success by the Runtime side, so a session that never started can still be deleted.

The difference between `callBound` and `executeWithRetry` deserves an explicit statement: write endpoints (chat / command) switch instance and retry after a failure; read and clear endpoints **hit the bound instance exactly once** — a second machine does not have what the first was asked to provide, and reporting the failure to the caller is more useful than inventing an empty result. Only "this instance does not answer" style failures count towards the breaker.

## 5. Endpoint contracts

### 5.1 Conversation proxy (14 endpoints, prefix `/api/router/agent`)

All are routed by `sessionId` and all pass through `SessionAccessGuard.requireAccessible`.

| Method and path | Request body | Returns | Purpose |
|------------|--------|------|------|
| `POST /chat` | `ChatAgentRequest` | `ResultVo<ChatResponse>` | non-streaming chat |
| `POST /chat/stream` | `ChatAgentRequest` | `Flux<ChatEvent>` (SSE) | streaming chat (the main path) |
| `POST /command` | `CommandAgentRequest` | `ResultVo<CommandResponse>` | imperative control (clear / stop / compact / …) |
| `POST /confirm` | `ConfirmAgentRequest` | `Flux<ChatEvent>` (SSE) | HITL tool confirmation, resumes the suspended run |
| `DELETE /session/{sessionId}` | — | `ResultVo<String>` | clear the session records |
| `GET /chat/history/{sessionId}` | — | pass-through | conversation history |
| `GET /session/{sessionId}/plans` | — | pass-through | plan list |
| `GET /session/{sessionId}/current-plan` | — | pass-through | current plan |
| `GET /workspace/{sessionId}/files` | `path` (default `/workspace`) | pass-through | list a directory |
| `GET /workspace/{sessionId}/read` | `path` | pass-through | read a file as text |
| `GET /workspace/status` | `sessionIds` (comma-separated, required) | pass-through | workspace status for several sessions |
| `GET /workspace/{sessionId}/status` | — | single-session status | `{"active": false}` when there is no data |
| `POST /workspace/{sessionId}/upload` | multipart `file` + `path` | pass-through | upload into the workspace |
| `GET /workspace/{sessionId}/download` | `path` | file stream | download a produced file |

Body fields and identity:

```kotlin
ChatAgentRequest(sessionId, message, imageUrls = [], requestId = "", userId = null)
CommandAgentRequest(sessionId, command: CommandType, args = "", userId = null)
ConfirmAgentRequest(sessionId, isConfirmed, toolInfoList = [], toolResults = [], userId = null)
```

`resolveUserId` precedence: the end-user identity in the auth context outranks the body's `userId`. When a trusted identity exists but the body reports a different value, the trusted identity wins and a warning is logged. Only when no identity is available at all (an internal token without `userId`) is the body value adopted, logged at info — that is the single case where body identity is trusted.

`/workspace/status` sends the whole id list to one instance for it to answer, so every id that `IdFormat.parseSessionIds` extracts is put through the guard separately. Checking only the first would turn the remaining ids into an entry point for reading another tenant's session content.

Download and upload boundaries:

- the file name comes from the client, and Router writes it into logs, forwards it into agent's multipart, and puts it in the download's `Content-Disposition`. `safeFileName` strips the directory part, removes quotes and control characters, truncates to 128 characters, and substitutes `unnamed` when nothing is left;
- a download above `MAX_DOWNLOAD_SIZE = 50 MB` makes Router return **HTTP 413** directly; an empty body from agent yields **HTTP 404** (these two use real status codes, not `ResultVo`).

### 5.2 Unbound behaviour of read-only endpoints

When a session is unbound (never chatted, binding expired, or its instance unregistered) Router contacts no instance, and the endpoints answer differently:

| Endpoint | When unbound |
|------|----------|
| `DELETE /session/{id}` | `code=200`, `data` is an explanatory string |
| `GET /chat/history/{id}`, `/plans`, `/current-plan` | `code=200`, `data` is an empty list / null |
| `GET /workspace/{id}/files`, `/workspace/status` | `code=200`, empty list / empty map |
| `GET /workspace/{id}/read` | `code=404`, stating there is no workspace because the session is unbound |
| `POST /workspace/{id}/upload` | `code=409`, telling the caller to send a message first |
| `GET /workspace/{id}/download` | HTTP 404 |
| `POST /chat`, `/command`, `/chat/stream`, `/confirm` | placed onto a new instance immediately |

Semantically, unbound means "this session has not started yet". These answers deliberately do not pretend that "the content really is empty", and do not manufacture an empty directory by placing the session elsewhere.

### 5.3 Instance management (prefix `/api/router`, the whole controller is `@InternalOnly`)

| Method and path | Caller | Semantics |
|------------|--------|------|
| `POST /instance/register` | agent-service | parameters `instanceId` / `host` / `port`; host must be an IPv4 literal, not blocked, and the port within 8000–9999 |
| `POST /instance/heartbeat` | agent-service | refreshes the heartbeat; for an unknown instance the body says `code=410` (HTTP stays 200) and agent re-registers on that |
| `POST /instance/unregister` | agent-service | takes the instance away and unbinds all its sessions |
| `POST /instance/drain` | operators / admin | graceful take-out; body `code=404` when the instance is unknown |
| `GET /instance/list` | operators | instances and their states |
| `GET /health` | operators | capacity report: `healthyInstances > 0` yields `UP`. This is not this node's health; containers and load balancers must use `/actuator/health/*` |
| `GET /metrics/cache` | operators | class names of the registry / binding / idempotency implementations currently in effect |

Because the class is annotated `@InternalOnly`, an external API Key calling any of these endpoints (including `GET /health` and `/metrics/cache`) gets 403.

Registration is validated twice: `InstanceRegistrationValidationFilter` (`@Order(HIGHEST_PRECEDENCE + 20)`, ahead of authorization, reading URL parameters rather than the body, and truncating matrix parameters at `;` before matching the path) rejects dirty parameters with real HTTP status codes — invalid `instanceId`/`host` format 400, blocked address 400, out-of-range port 400, privileged port 403. The controller then re-checks IPv4 and the port range independently, answering inside a `ResultVo`. The first check sits at the registration door so that a dirty address never enters the registry — registration is the main SSRF surface.

### 5.4 Monitoring and static resources

| Path | Auth | Content |
|------|------|------|
| `GET /api/router/monitor/instances` | credential required | instance address, port, state, heartbeat age, session count. This is cluster topology, owned by no tenant, which is why it does not descend into session content |
| `GET /api/router/monitor/call-logs` | credential required | paged call-log query, narrowed to the caller's tenant (see the call log section) |
| `/ui`, `/index.html`, `/static/`, `/style.css`, `/app.js`, `/favicon.ico` | exempt | static resources for rendering the monitor page only; the page's data requests require a credential |

The monitoring endpoints are deliberately not `@InternalOnly`: browsers hold no service-to-service credential, and operators use their own JWT or an admin Key. The exempt paths are static resources only — putting `/api/router/` into `harnax.auth.skip-paths` would leave `heartbeat` / `register` without an `AuthContext`, and `InternalAuthorizationInterceptor` would have nothing to decide on.

### 5.5 Idempotency

| Endpoint | Deduplicated | Condition |
|------|------|------|
| `POST /chat` | yes, only when `requestId` is non-empty | lease key = `requestId` |
| `POST /chat/stream`, `/confirm`, `/command` | no | — |

`proxyChatRequest` deduplicates only when the client supplied a `requestId` itself (an id Router generates is unique by definition; guarding it costs an extra round trip and rejects nothing). The semantics are an **in-flight lease**, not a replay window: `tryAcquire` claims the slot with `SET NX`, `release` deletes it in the request's `finally`, and the 60-second TTL only covers a process dying mid-request. When the lease cannot be taken, the answer is `code=429` with the message `Duplicate request: {requestId}`. When Redis is unreachable `tryAcquire` lets the request through (better to miss one duplicate than to stop serving).

## 6. Response and error semantics

### 6.1 Non-streaming: HTTP 200 plus a business code

`GlobalExceptionHandler` writes uncaught exceptions as a `ResultVo` while the HTTP status stays 200:

```json
{ "code": 500, "message": "No healthy agent-service instances available for session web-…", "data": null }
```

An `HarnaxException` contributes its own numeric code; every other exception comes out as `code=500`. This is the shape webui's request wrapper requires: it judges success from the `code` field of a 2xx response, and a non-2xx is swallowed as a network failure. **Judge success from `body.code`, not from the HTTP status.**

Non-500 business codes Router produces itself in its control flow: 429 (duplicate request), 404 (workspace read unbound, drain of an unknown instance), 409 (workspace upload unbound), 410 (heartbeat for an unknown instance).

### 6.2 Streaming: errors are events

`/chat/stream` and `/confirm` declare `text/event-stream`. Every failure path — ownership refusal, invalid id format, no instance, forwarding failure, relocation failure — terminates as an event stream:

```kotlin
ErrorChatEvent(code = HarnaxErrorCode.code, message = …)   // code is a string code
EndEventChatEvent()
```

The `code` is the string code of a `HarnaxErrorCode`: `2002` FORBIDDEN (ownership refusal), `1003` INVALID_PARAM (invalid id), `6011` ROUTER_NO_INSTANCE (placement failed), `6010` ROUTER_PROXY_ERROR (forwarding or relocation failed). **Do not read these numbers with HTTP status-code semantics.** Regular content events are `StreamThinkingChatEvent`, `StreamTextChatEvent`, `CallToolChatEvent`, `ToolResultChatEvent`, `ToolConfirmChatEvent` (HITL suspension, which must come back through `/confirm`), and the stream closes with `EndEventChatEvent` (carrying `tokenUsage`).

Caller contract: an `ErrorChatEvent` means a business failure and the end of the exchange; do not retry the whole stream.

### 6.3 Where real HTTP status codes are used

| Case | HTTP | Source |
|------|------|------|
| credential missing / invalid / Key disabled or expired | 401 | `UnifiedAuthFilter` |
| Origin outside the CORS allow-list | 403 (body `Invalid CORS request`) | `CorsFilter`, ahead of authentication |
| external credential calling `@InternalOnly` | 403 | `InternalAuthorizationInterceptor` |
| `@InternalOnly` but no `AuthContext` available | 401 | same (normally a misconfigured `skip-paths`) |
| external Key over its per-minute quota | 429 + `Retry-After: 60` | `RateLimitInterceptor` |
| invalid registration parameters / blocked address / port out of range | 400 | `InstanceRegistrationValidationFilter` |
| registration of a privileged port | 403 | same |
| download too large / download with no content | 413 / 404 | `AgentProxyController` |

## 7. The SSE streaming chain

### 7.1 End-to-end timeout budget

| Layer | Parameter | Value |
|----|------|-----|
| client → nginx (the SSE regex location) | `proxy_read_timeout` | 900s |
| client → nginx (`/api/router/`) | `proxy_read_timeout` | 60s |
| Router container async request | `spring.mvc.async.request-timeout` | 1800000ms (30min) |
| Router → agent (non-streaming JSON) | `router.proxy.read-timeout-ms` | 600000ms (10min), used both as `responseTimeout` and as `ReadTimeoutHandler` |
| Router → agent (write) | `router.proxy.write-timeout-seconds` | 30s, **an in-code default**: the key exists only as the `@Value` default on a `RouterConfig` constructor parameter, neither `harnax-session-router/src/main/resources/application.yml` nor compose carries it, so it is not adjustable through configuration |
| Router → agent (connect) | `router.proxy.connect-timeout-ms` | 5s |
| Router stream idle | `router.proxy.stream-idle-timeout-seconds` | 120s |
| Router stream wall clock | `router.proxy.stream-max-duration-minutes` | 30min |
| Router → Redis | command timeout / connect timeout | 3s / 2s |
| Router → admin | response timeout / connect timeout | 3s (plus a 1s total-budget margin) / 2s |
| idempotency lease | `router.idempotency.ttl-seconds` | 60s |
| agent per-turn budget | `HARNAX_TURN_TIMEOUT_SECONDS` | 300s |

The two stream bounds are applied at subscription time by `AgentServiceClient.withinStreamLimits`: `timeout(120s)` measures silence between events, `takeUntilOther(Mono.delay(30min))` measures the wall clock of the whole stream. Both end the stream with an exception, so the caller sees a failure rather than a silent stop. `streamingWebClient` deliberately carries no `responseTimeout` — an agent thinking for a minute without sending a byte is the very reason this endpoint exists, and cutting on a read timeout would remove exactly the requests it is meant to serve.

The container async timeout (30min) is aligned with the stream wall clock (30min) so that Router ends the stream itself instead of being cut off by the container. The Redis 3s command timeout is deliberately short: only a fast failure leaves room for the degradation paths.

### 7.2 Requirements on the nginx side

In `docker-new/nginx.conf`, `location ~ ^/api/router/agent/(chat/stream|confirm)` does the following; omit any one of them and the symptom is "the stream does not move":

| Directive | Value | Reason |
|------|-----|------|
| `proxy_http_version` | 1.1 | — |
| `proxy_set_header Connection` | `""` | keep-alive reuse without an `Upgrade`: SSE is not a protocol upgrade, and the location on this path does not forward an `Upgrade` header |
| `proxy_buffering` / `proxy_request_buffering` | off | neither response nor request is buffered |
| `proxy_cache` | off | skips any response cache |
| `gzip` | off, and `Accept-Encoding` emptied | compressing SSE makes nginx accumulate blocks |
| `X-Accel-Buffering` | `no` | also disables buffering behind one more upstream proxy |
| `chunked_transfer_encoding` | on | — |
| `proxy_next_upstream_tries` | 1 | a POST is never resubmitted to a different upstream |
| `proxy_read_timeout` | 900s | trips only on stream silence, and Router's 120s arrives first |

Regex locations take precedence over the `/api/router/` prefix location, so only these two endpoints take the unbuffered path.

### 7.3 Instance switch allowed before the first event, no replay after it

`buildStreamFlux` uses an `AtomicBoolean delivered` set in `doOnNext` to record "has an event already been delivered to the client", and `onErrorResume` switches instance only when all three hold: `isConnectivityError(e)` && `attempt < failover-max-retries(2)` && `!delivered`.

`isConnectivityError` recognizes only these exception classes (walking the cause chain): `ConnectException`, `SocketTimeoutException`, `NoRouteToHostException`, `UnknownHostException`, `ConnectTimeoutException`. Instance switching therefore happens only on "cannot connect". Once an event has been delivered Router does not replay — replaying a prompt that already produced text would append a second answer inside the same conversation and run the tools the agent already executed a second time.

During a switch the `excluded` set accumulates the instances already tried, and `placementExclusions` additionally excludes breaker-tripped instances; before switching, `sessionEvictor.requestEviction` is called so the instance being left stops the sandbox.

### 7.4 Connection pool and buffering

`RouterConfig` builds one `ConnectionProvider` (`router-pool`) shared by both WebClients. Reactor Netty pools per `host:port`, so the limits below are the amount **a single agent instance** may occupy, not a global amount — one wedged instance cannot fill up the whole Router:

| Parameter | Value | Meaning |
|------|-----|------|
| `max-connections-per-instance` | 50 | concurrent connection ceiling for one instance |
| `pending-acquire-timeout-ms` | 10000 | how long a waiter queues for a slot when the pool is full |
| `pending-acquire-max-count` | 100 | above this many waiters requests are rejected fast instead of everyone timing out |
| `pool-max-idle-seconds` | 60 | idle connection reclamation |
| `pool-max-lifetime-minutes` | 5 | forced reclamation by age; after a session moves, the connections to its former instance reach end of life and get no reuse |
| `evictInBackground` | 30s | — |
| `max-in-memory-size-mb` | 16 | non-streaming response buffer ceiling; downloads and streams do not take this path |

## 8. Tenant isolation

### 8.1 Where validation converges

All 14 session-scoped endpoints pass `SessionAccessGuard.requireAccessible(sessionId)` before any instance is looked up: write paths call it explicitly, read paths get it from `boundInstance()` uniformly (a new endpoint inherits the guard simply by reusing `boundInstance`). The two sides compared: the caller's `tenantId` (the JWT's `tenantId` claim or the tenant attached to the Key) and the owning tenant admin reports; a mismatch raises `SecurityException`.

Why this guard exists: when forwarding, Router presents its own service token, so agent-service sees "a peer service" and cannot block a cross-tenant call on that basis; and inbound authentication answers "may you use Router", never "may you read this session".

### 8.2 Conditions that let a request through

| Passed through | Basis |
|------|------|
| caller has no `tenantId` (internal service token without a tenant / Key without a tenant) | there is no side to compare. channel comes to route on behalf of a user it already authenticated, and manufacturing a refusal would only push operators towards turning authentication off |
| admin explicitly answers "no such session" (`Unknown`) | nothing is bound, the proxy endpoints answer "unbound", no instance is touched, and ownership can be neither proved nor disproved |
| admin unreachable or refusing (`Unreachable`) | the conversation chain is not blocked by an admin outage; a warning is logged and the request proceeds |

`Unknown` and `Unreachable` are two distinct outcomes inside `AdminClientService.lookupSession` (a 404 means "admin did answer"), and `SessionInfoClient` caches only the former while invalidating immediately on the latter — otherwise one admin hiccup would make the affected sessions read as "does not exist" for 5 minutes after recovery.

### 8.3 Session id prefix rules

`PrivilegedSessionPrefixes` blocks `task-` ahead of any ownership query: when the caller carries an end-user identity, a `task-` prefix raises `SecurityException` directly. This is a prefix rule rather than a query because admin parses `task-` from the id itself (`agent_task` already lives in scheduler's own database), so the ownership query cannot answer for it — a uniform `Unknown` would mean passing it through, and the taskId inside `task-{taskId}` is a monotonically increasing integer, enumerable with one valid credential. Internal callers without an end user, such as scheduler, keep working.

`chn-` is not on that list: its id is a UUID, and pointing at it already requires knowing it. admin's `/sessions/{id}/info` takes the tenant of a `chn-` id from the `channel` row and **ignores the `active` flag** — a soft-deleted channel still belongs to its original tenant (deleting clears neither the session nor the sandbox, so deletion is not what makes a read orphaned). Cross-tenant `chn-` reads are therefore refused by the tenant comparison above, while legitimate same-tenant page reads keep passing. `web-` and `mp-` are decided entirely by the tenant comparison.

### 8.4 Granularity and precision

**The isolation unit is the tenant, not the user.** Within one tenant, different logged-in users holding valid credentials for that tenant can read each other's sessions. User-level isolation must come from a higher layer (admin's session authorization, or the channel side's user-to-session mapping).

| Item | Value / behaviour |
|----|-----------|
| ownership lookup cache | 5000 entries, 5 minutes after write; `Unknown` is cached too |
| thread the lookup runs on | `runBlocking` on the request thread, worst case about 4s (3s response timeout + 1s margin) |
| the window the cache opens | a `chn-` id asked about before admin started answering keeps passing for 5 more minutes on its cached `Unknown` |

### 8.5 Input format and SSRF defences

| Defence point | Rule |
|--------|------|
| `sessionId` | `^[A-Za-z0-9._:-]{1,128}$` and no `..`; a violation raises `IllegalArgumentException` without echoing the rejected value |
| `instanceId` | `^[A-Za-z0-9._-]{1,64}$` |
| the `sessionIds` list | split on commas and validated one by one; an empty list is refused outright |
| registered address | IPv4 literals only (any letter is refused); `0.*`, `127.*`, `169.254.*` and hostnames such as `localhost` or `metadata*` are refused; private ranges are allowed. Port 8000–9999 |
| forwarded path | `sessionId` always becomes a path segment through `UriUtils.encodePathSegment`; query parameters through `encodeQueryParam`; the URI is built by `java.net.URI`, never by string concatenation |
| file names | `safeFileName` strips the directory, removes quotes and control characters, caps length at 128 |
| Redis deserialization | `GenericJackson2JsonRedisSerializer` with a `BasicPolymorphicTypeValidator` that allows only `com.agnetix.harnax.`, `java.util.` and `java.lang.` |

Format validation matters because `sessionId` appears simultaneously in a Redis key name, in a URL Router itself issues, and in logs: an id containing a quote or a backslash makes the reverse index derive a key name that never matches (the session quietly loses its instance mid-conversation), and an id containing `../` turns one session lookup into a request against a different internal endpoint.

## 9. Storage and the call log

### 9.1 Two datasource shapes

| Mode | Trigger | Routing state | Call log |
|------|----------|----------|----------|
| `local` (`router.cache.type=local`, default) | single node / development | in-process memory | SQLite, JDBC URL defaulting to `jdbc:sqlite:tmp/harnax-router/call-log.db`; the table is created by `SqliteInitConfig` running `db/sqlite-init.sql`, the directory by `SqliteDirectoryInitializer` |
| `redis` (cluster profile) | `CACHE_TYPE=redis` | Redis | MySQL database `harnax_router`, tables created by the Flyway migration `V1__create_session_router_tables.sql` |

The two DDLs carry the same columns (the SQLite one differs in constraints around the `instance_id` index and additionally creates `idx_instance_id`). The SQLite branch is active only when the datasource URL contains `sqlite` and Flyway is not enabled, so the cluster profile never reaches it.

The call-log database takes no part in readiness: failing to write a log must not take routing out of service.

### 9.2 `api_call_log` columns

| Column group | Columns |
|--------|------|
| caller | `caller_id`, `caller_type` (`INTERNAL_SERVICE` / `EXTERNAL_API`), `tenant_id` (nullable) |
| session and target | `session_id`, `agent_id`, `agent_name`, `model_id`, `model_name`, `instance_id` |
| request | `endpoint`, `method`, `request_type` (`CHAT` / `COMMAND` / `CONFIRM`), `request_id` |
| outcome | `status_code`, `success`, `error_message`, `start_time`, `end_time`, `duration_ms`, `create_time` |
| indexes | `idx_caller_id`, `idx_session_id`, `idx_start_time`, `idx_tenant_id` |

### 9.3 When it is captured and how it is buffered

`ApiCallLogFilter` (`@Order(HIGHEST_PRECEDENCE + 15)`) handles the `/api/router/agent/` prefix only; requests outside it are not logged:

- the row is written when the response actually ends. The basis is the response status, not whether the filter chain returned — for streams and coroutine endpoints the chain returns at the start of async processing, and recording then would log a 200 and a few milliseconds;
- SSE endpoints (path ending in `/stream`, or `/api/router/agent/confirm`) and coroutine batch endpoints (`/chat`, `/command`, `/session`, `/chat/history`, `/workspace` and its sub-paths) are not wrapped in a `ContentCachingResponseWrapper`: buffering would cut the event stream or flush out an empty body. These requests register an `AsyncListener` instead and write one row (exactly one) in `onComplete` / `onTimeout` / `onError`, with `duration_ms` covering the whole stream's life, and a timeout row marked as failure stating that the response was never written;
- the landing instance comes from the request attribute `router.routedInstanceId` written by `SessionRouterService.trackPlacement`, falling back to MDC. MDC belongs to the thread where placement happened and is invalidated by coroutine suspension; only a thread bound to an HTTP request can write that attribute;
- the four agent / model columns come from `SessionInfoClient`; when admin is unreachable they stay empty and the rest is recorded as usual;
- `ApiCallLogService` buffers in memory, flushing a batch insert at 50 rows or every 5 seconds, with a buffer ceiling of 10000 rows beyond which rows are dropped and counted; a failed batch insert falls back to row-by-row retries and bad rows count towards `poisonedCount`; one flush happens before the process exits. Each string column is truncated to its DDL width.
- there is no time-based deletion task anywhere; the table only grows.

### 9.4 Narrowed to the caller's tenant

The `tenantId` used by `GET /monitor/call-logs` does not come from a request parameter; `RouterMonitorController` takes it from the already validated credential:

| Caller | Sees |
|--------|------|
| a credential with a tenant | only rows with `tenant_id = own tenant` |
| no tenant (internal token without a tenant / Key without a tenant) | everything, including rows with `tenant_id IS NULL` |

Rows with `tenant_id IS NULL` were written by tenant-less callers in the first place, which is why the rule is not widened to `IS NULL OR = own tenant` — a row that cannot be attributed must not become everyone's row. `sessionId`, `instanceId`, `agentName`, `statusCode`, `success`, `minDurationMs`, `limit` and `offset` are optional caller-side filters, unrelated to the question of whose rows these are; paging and count reuse the same WHERE fragment.

## 10. Behaviour when Redis is unreachable

### 10.1 Degradation point by point

| Step | Behaviour | Consequence |
|------|------|------|
| request for an already bound session | when `getInstanceId` fails to read, fall back to this node's shadow cache `lastKnownBindings` (Caffeine, 50000 entries / 24h, repopulated on every successful read so it outlives a fault longer than the TTL) | stickiness is not guaranteed when another replica handles the same session |
| placement of a new session | the instance list comes from `RedisInstanceRegistry`'s `knownInstances` snapshot, discarded in full once entries age out on heartbeat timeout | after ageing, placement stops rather than being aimed at a fleet that may all be gone |
| load statistics | `degradedCounts` estimates from this node's shadow bindings | a single-node view only, and spreading is still better than choosing nothing |
| breaker decision | read failure lets the request pass | an instance inside the breaker window may be selected again |
| writing a new binding | `placeUnpersisted` takes effect on this node, and the write happens at the next placement after Redis recovers | other replicas cannot see this binding |
| idempotency lease | `tryAcquire` lets the request through | duplicates may get through during the fault |

Lettuce is configured with `DisconnectedBehavior.REJECT_COMMANDS`: with the connection down, commands throw immediately on the calling thread instead of queueing — queueing would exhaust the servlet thread pool, and only throwing reaches the fallback paths above.

**What is not promised**: cross-replica session stickiness while Redis is unreachable. What is promised is that request threads are not wedged and that service is not silently lost — not that routing results match what Redis would say after recovery. In cluster mode the readiness check (`/actuator/health/readiness`, which includes the `redis` member) takes that node out of load balancing.

### 10.2 Redis Cluster is not supported: fail-fast at startup

| Topology | Support |
|------|------|
| single standalone instance | supported, and the default; requires `maxmemory-policy=noeviction` |
| Sentinel (replication plus automatic failover) | supported, via `REDIS_SENTINEL_MASTER` / `REDIS_SENTINEL_NODES` / `REDIS_SENTINEL_PASSWORD` |
| Redis Cluster | not supported; a non-empty `REDIS_CLUSTER_NODES` fails startup |

The reason is in the key layout: scripts such as `BIND` / `MOVE_IF_FROM` / `REBIND_BATCH` operate on `router:session:*` and `router:instance_sessions:*` at the same time, and `HEARTBEAT` / `MARK_DOWN` / `MARK_DRAINING` operate on `router:instance:*` and `router:instances:healthy` at the same time. Under Cluster these keys do not land in one slot, so Redis answers `CROSSSLOT` before executing the script — the first heartbeat after registration fails, the health checker throws every 5 seconds, and failover does not work at all.

`RedisConfig.redisConnectionFactory` therefore puts the Cluster check first and throws an `IllegalStateException` whose message names the alternatives (standalone or Sentinel), instead of letting a node come online that is "process alive, registry unusable". `harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/config/RedisConfigTest.kt` locks this behaviour.

Router's entire state is "tens of thousands of session bindings + a few hundred instance records + a handful of index sets", an order of a few tens of MB; docker-compose gives Redis `maxmemory 512mb` with `noeviction`.

### 10.3 profile `cluster` and Redis Cluster are two different things

| Name | Meaning |
|------|------|
| Spring profile `cluster` (`SPRING_PROFILES_ACTIVE=cluster`, i.e. `application-cluster.yml`) | the deployment shape of multiple Router replicas + MySQL + Redis; the Redis it connects to is still standalone or Sentinel. This is the production shape |
| `REDIS_CLUSTER_NODES` | Redis's own sharded cluster; not supported, and setting it fails startup |

What `application-cluster.yml` overrides is the datasource (MySQL with Flyway enabled), `router.cache.type=redis`, the reverse-index reconciliation parameters, and the inclusion of `redis` in the readiness group.

## 11. Deployment shape (docker-new)

`docker-new/docker-compose.yml` is the only deployment entry point. The relevant lines of the `router` service:

| Item | Value |
|----|-----|
| image / container name | `harnax-router:latest` (built by `docker-new/Dockerfile.router`) / `harnax-router` |
| profile | `SPRING_PROFILES_ACTIVE: cluster` |
| storage | `DB_URL: jdbc:mysql://mysql:3306/harnax_router`, `DB_USERNAME` / `DB_PASSWORD`; the database itself is created by `docker-new/sql/init-databases.sql` |
| cache | `CACHE_TYPE: redis`, `REDIS_HOST: redis`, `REDIS_PORT: 6379`, `REDIS_PASSWORD` |
| identity | `SERVICE_ID` (`ROUTER_SERVICE_ID`, default `router-0`), `HARNAX_AUTH_SECRET`, `ADMIN_INTERNAL_API_SECRET` |
| upstream | `ADMIN_SERVICE_URL: http://admin:8080` |
| CORS | `ROUTER_CORS_ALLOWED_ORIGINS`, which must include the port-less `http(s)://localhost` and `127.0.0.1` variants |
| port | `28081:8081` published to the host (the standard convention: 20000 + service port) |
| depends on | `admin` started, `redis` healthy |
| health check | `wget /actuator/health/liveness` every 30s with `start_period` 30s; this is a liveness check and does not look at Redis |
| resources | CPU ceiling 2.0 / memory 1024M, JVM `-Xms128m -Xmx512m` |
| volumes | `router-logs:/app/logs`, host `/etc/localtime` read-only |

The proxy-side requirements are in the nginx section above: the SSE regex location must keep buffering off and a 900s read timeout; the `/api/router/` prefix location's 60s covers non-streaming and instance-management calls; `= /ui` is proxied to `router:8081` on its own.

### 11.1 Fail-fast at startup

| Check | Condition | Result |
|------|------|------|
| `RedisConfig` | `router.cache.type=redis` and `REDIS_CLUSTER_NODES` non-empty | throws `IllegalStateException`, process does not start |
| `RedisConfig` | `router.cache.type=redis` and neither a host nor a sentinel master | `check(...)` fails with a message to configure `REDIS_HOST` |
| `PlaceholderSecretCheck` | `redis` mode with `harnax.auth.enabled=true` while `HARNAX_AUTH_SECRET` or `ADMIN_INTERNAL_API_SECRET` still holds a placeholder value from the repository | throws and refuses to start |
| `SqliteInitConfig` / `SqliteDirectoryInitializer` | the datasource is SQLite and Flyway is not enabled | create the directory and the table; failure fails startup |

`PlaceholderSecretCheck` covers `redis` mode only: a placeholder secret buys nothing in single-node in-memory mode, and refusing to start a development environment over a strength requirement that is never exercised only pushes people towards turning authentication off.

### 11.2 Liveness, readiness and the scheduler pool

| Endpoint | Content |
|------|------|
| `GET /actuator/health/liveness` | whether the process is up. A container restart policy should look at nothing else |
| `GET /actuator/health/readiness` | whether routing can happen. Under the cluster profile it includes the `redis` member and excludes MySQL |
| `GET /metrics/cache` | the implementation classes currently in effect (internal identity) |

`spring.task.scheduling.pool.size` is 4 (`ROUTER_SCHEDULER_POOL_SIZE`): health checking, call-log flushing, rate limiting and binding cleanup are all `@Scheduled`, and sharing one thread would let a single health check waiting on Redis stop every other task including call-log flushing.

## 12. Observability

### 12.1 Metrics

`/actuator/prometheus` (exposing `health,info,prometheus,metrics`, all tagged with `application`):

| Metric | Type | Tags | Definition |
|------|------|------|------|
| `router.proxy.duration` | Timer | `endpoint=chat` | non-streaming chat duration; the timer also stops on the exception path |
| `router.proxy.requests` | Counter | `endpoint=chat\|stream`, `status=ok\|error` | numerator and denominator of the success rate |
| `router.failover.count` | Counter | `endpoint`, `attempt` | how often relocation happens and at which hop |
| `router.healthy.instances` | Gauge | — | how many instances this replica may place onto at scrape time (DRAINING is not in the healthy set and is therefore not counted) |

`router.healthy.instances` reads the registry at scrape time instead of accumulating a counter: registration, drain and another replica's DOWN decision all change the fleet outside this node, and a counter would drift from the view. With several replicas this metric must be aggregated with `min()`, not `avg()` — when replica views disagree, an average hides "one replica sees no instances at all".

### 12.2 Locating the landing instance from a sessionId

| Means | Availability |
|------|--------|
| query `api_call_log` by `session_id` and read `instance_id` | the first choice; includes the landing instance |
| `GET /api/router/monitor/instances` | session distribution and heartbeat age from the instance side |
| MDC in the application log | reliable on synchronous paths only. Streaming requests do not write MDC (events arrive on agent's event loop), and a relocation that completes on agent's event loop does not reach the call log's `instance_id` |

## 13. Current limits (source of truth)

| # | Fact | Effect | Handling today |
|---|------|------|----------|
| 1 | Redis Cluster is not supported; cross-key Lua is a design precondition, and a non-empty `REDIS_CLUSTER_NODES` refuses startup | Redis high availability comes from standalone or Sentinel only | Sentinel; the state footprint is a few tens of MB, so sharding buys this component nothing |
| 2 | outside the explicit branches, non-streaming business errors all collapse to `code=500` | callers cannot distinguish "unbound / no instance / invalid parameter" programmatically and must match on `message` | when a distinction is needed, check session and instance state first |
| 3 | streaming endpoints have no idempotency, and `/chat` deduplication requires the caller to bring a `requestId` | a client reconnect may trigger two model calls | deduplicate on the client; to get Router-side deduplication, generate and send a `requestId` |
| 4 | the nginx `proxy_read_timeout` for the `/api/router/` prefix is 60s while Router's non-streaming read timeout is 600s | a non-streaming `/chat` longer than 60s is cut by the gateway first while Router may still run it to success | send long work through `/chat/stream` (the 900s location), or align the two values |
| 5 | neither the read-idle timeout nor the pool-acquire timeout triggers relocation or the breaker: `isConnectivityError` knows only connectivity exceptions, `isRetryableError` only connectivity plus 5xx/429 | an instance that "accepts connections but never returns data" is not removed automatically, and the caller just gets a timeout | the 30s heartbeat timeout is the backstop; operators use `/instance/drain` |
| 6 | `api_call_log` has no retention period and no cleanup task | the table only grows | operators archive or delete by `start_time` |
| 7 | session ownership validation is at tenant granularity; different users inside one tenant can read each other's sessions | user-level isolation is not provided by Router | provided by admin's session authorization or the channel-side mapping |
| 8 | the ownership lookup runs `runBlocking` on the request thread (budget 3s + 1s) | a slow admin occupies request threads | keep admin healthy; a cache hit means no network call |
| 9 | `Unknown` results are cached for 5 minutes while `Unreachable` is not cached | ownership is decided from the cached value inside that window | when a change must take effect immediately, wait out the window or restart the node |
| 10 | `drain` is irreversible and there is no `undrain`; heartbeat preserves DRAINING | an accidental drain recovers only through a fresh `register` | confirm the instance is going away before draining it |
| 11 | `local` mode shares no state at all | with several replicas each places independently and stickiness drifts per replica | run `local` on a single replica (deployment constraint) |
| 12 | the rate-limit counter is in-process per replica | the effective quota of an external Key grows with the replica count | exact quotas need a shared counter |
| 13 | a relocation that completes on agent's event loop writes no `instance_id` into the call log | that landing instance is traceable only in the instance's own logs | landing is recorded by `trackPlacement` on synchronous paths |
| 14 | `GET /workspace/status` hands every id to the instance that owns the first id | sessions on other instances are answered only partially, and unbound ones come back empty | group the query by instance, or use the single-session endpoints |

## 14. Key file index

| Topic | Location |
|------|------|
| application entry point | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/SessionRouterApplication.kt` |
| proxy and placement main flow | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt` |
| conversation proxy endpoints | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt` |
| instance management endpoints | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/InstanceRegistryController.kt` |
| monitoring endpoints | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/RouterMonitorController.kt` |
| monitor page entry | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/RootController.kt` |
| session binding (Redis) | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisSessionMappingService.kt` |
| session binding (single node) | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/LocalSessionMappingService.kt` |
| instance registry | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisInstanceRegistry.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/LocalInstanceRegistry.kt` |
| reverse-index reconciliation | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/SessionIndexReconciler.kt` |
| circuit breaker | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/InstanceCircuitBreaker.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisCircuitBreaker.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/LocalInstanceCircuitBreaker.kt` |
| idempotency | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisIdempotencyService.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/CaffeineIdempotencyService.kt` |
| health check and relocation | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/health/HeartbeatHealthChecker.kt` |
| sandbox eviction | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/SessionEvictor.kt` |
| outbound client and stream bounds | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AgentServiceClient.kt` |
| connection pool and WebClients | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/RouterConfig.kt` |
| Redis wiring and topology decision | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/RedisConfig.kt` |
| tenant ownership | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/SessionAccessGuard.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/SessionInfoClient.kt` |
| admin client | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AdminClientService.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/RemoteApiKeyStore.kt` |
| input format and prefix rules | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/support/IdFormat.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/support/PrivilegedSessionPrefixes.kt` |
| instance entity and address validation | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/entity/AgentInstance.kt` |
| pre-auth registration parameter checks | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/InstanceRegistrationValidationFilter.kt` |
| call-log capture | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/ApiCallLogFilter.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/ApiCallLogService.kt`, `harnax-session-router/src/main/resources/mapper/ApiCallLogMapper.xml` |
| rate limiting | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/RateLimiter.kt`, `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/RateLimitInterceptor.kt` |
| startup secret check | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/PlaceholderSecretCheck.kt` |
| exception to response | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/GlobalExceptionHandler.kt` |
| configuration | `harnax-session-router/src/main/resources/application.yml`, `harnax-session-router/src/main/resources/application-cluster.yml` |
| schema scripts | `harnax-session-router/src/main/resources/db/migration/V1__create_session_router_tables.sql`, `harnax-session-router/src/main/resources/db/sqlite-init.sql` |
| inbound authentication | `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt`, `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/ExternalApiKeyValidator.kt`, `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/InternalTokenProvider.kt`, `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/InternalAuthorizationInterceptor.kt` |
| admin internal API secret comparison | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/InternalApiAuthFilter.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` |
| release failure refuses the delete | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionRuntimeReleaser.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentRuntimeClientImpl.kt` |
| agent-side clearing and sandbox release | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/KeepAliveSandboxManager.kt` |
| events and request protocol | `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt`, `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt` |
| error codes | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/error/HarnaxErrorCode.kt` |
| deployment and proxy | `docker-new/docker-compose.yml`, `docker-new/nginx.conf`, `docker-new/Dockerfile.router`, `docker-new/sql/init-databases.sql` |
