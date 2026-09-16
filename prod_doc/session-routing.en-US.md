# Harnax Session Routing (English)

> 中文版本见 [session-routing.zh-CN.md](./session-routing.zh-CN.md)
>
> Based on a survey of the current codebase, this document describes the contract that `harnax-session-router` (below, "the Router") offers to its **callers**: the endpoint inventory, error semantics, tenant isolation guarantees, deployment shapes, timeout and capacity budgets, observability, and the list of current limitations.
>
> **How the Router documents divide the work** (each answers a different question — don't read them as substitutes):
>
> | Document | Audience | Question it answers |
> |----------|----------|---------------------|
> | This one (`prod_doc/session-routing`) | Callers (channel / webui / app / mini-program / SDK), product and operations decision-makers | What the capability guarantees, what its semantics are, where its edges are |
> | [`harnax-session-router/README.md`](../harnax-session-router/README.md) | Developers of this module and on-call ops | How the internals work, Redis key layout, source structure, alerting rules |
> | [`docs/deploy-harnax-session-router.md`](../docs/deploy-harnax-session-router.md) | Whoever runs the deployment | Environment variables, startup steps, SQLite / MySQL paths |
>
> When numbers disagree, code wins: defaults live in `harnax-session-router/src/main/resources/application.yml`, behaviour lives in `proxy/SessionRouterService.kt`.

## 1. Scope and boundaries

### 1.1 The problem it solves

A session (`sessionId`) must keep landing on **the same agent-service instance** for its whole lifetime. Agent state is not in the database — it lives in that process's memory and local disk:

- the Agent object and its ReAct loop context;
- the workspace sandbox directory (tool output files, the plan in progress);
- the suspended HITL tool-confirmation state, which only resumes on the same instance.

The Router exists to serve that sticky-session constraint: it makes the placement decision and forwards the request. Port **8081**, Spring MVC with Kotlin coroutines, stateless and horizontally scalable.

### 1.2 What it deliberately does not do

| The Router does not | Who does |
|---------------------|----------|
| Run agents or call models | `harnax-agent-service` (:8082) |
| Store transcripts, plans or workspace files | agent-service (local workspace + snapshot) |
| Decide whether an API key is valid or which tenant owns it | `harnax-admin` (the Router queries and caches) |
| Decide which tenant owns a session (query only, no adjudication) | admin (`SessionInfoClient` → admin internal API) |
| Parse and deliver channel messages | `harnax-channel` |
| Schedule agent tasks | `harnax-scheduler` (:8084) |

> In one line: **the Router is a session-aware layer-7 reverse proxy plus a placement decider — not a business service.** Its only state is the routing table (instance registry, session bindings, circuit-breaker state).

## 2. End-to-end flow

### 2.1 Topology

```
                          ┌─ channel-service   (DingTalk / Feishu / WeChat user messages)
                          ├─ webui             (Ant Design Pro console)
External callers ─ nginx ─┼─ harnax-app        (uni-app H5 / mini-program)
   :80                    ├─ harnax-client SDK (external integrations)
                          └─ harnax-admin      (proxies workspace / session calls)
                                   │
                                   ↓  X-Api-Key or Bearer JWT
                          ┌────────────────────┐
                          │  Router :8081      │──JWT──> agent-service :8082 (×N instances)
                          │  sticky routing    │            │
                          └────────────────────┘            │ register / heartbeat (every 10s)
                            │      │      │                 ↓
                            ↓      ↓      ↓        ┌── Router /api/router/instance/*
                     admin :8080  Redis   MySQL    │  (agent-service registers itself)
                   (key check,    (shared  (call
                    session        state)   logs only)
                    ownership)
```

### 2.2 One streaming chat call (the fullest path)

For `POST /api/router/agent/chat/stream`:

| Step | Action | What failure looks like |
|------|--------|-------------------------|
| 1 | `UnifiedAuthFilter` validates the credential (Bearer JWT or `X-Api-Key`) and populates `AuthContextHolder` | 401 (real HTTP status) |
| 2 | `SessionAccessGuard.requireAccessible(sessionId)` checks tenant ownership | 403 / `SecurityException` (see 6.4) |
| 3 | Look up the binding; if present, use that instance | Not bound → a "not bound" error (see 6.3) |
| 4 | Otherwise place it: exclude DRAINING and tripped instances, then weighted-random over the reverse index with weight `1/(count+1)` | No usable instance → error |
| 5 | Write the binding atomically (one Lua script updates the session key and the per-instance reverse index together) | Redis unavailable → a node-local shadow binding; degraded, still served |
| 6 | Forward with WebClient to `http://{host}:{port}`, stream SSE events back | See step 7 |
| 7 | On a connectivity failure **before any event reached the client**, retry on another instance, up to `failover-max-retries=2` | After the first event there is no replay (no duplicated content) |
| 8 | Retryable failures feed the circuit breaker; 3 in a row trips that instance for 30s | While tripped, no **new** session is placed on it |

**The key contract**: step 7's "no replay after the first event" means that once the client has seen one token, the Router will not silently move it to another instance. If the instance dies at that point, the client sees a terminating error event — not a continued stream.

### 2.3 Caller matrix

| Caller | Endpoints | Credential | Notes |
|--------|-----------|------------|-------|
| channel-service | `/agent/chat/stream`, `/agent/command`, `/agent/session/{id}` DELETE | Internal service token (JWT) | Calls on behalf of users; the Router does not compare tenants (see 7.2) |
| webui | All of `/agent/**` including workspace | Bearer JWT or `X-Api-Key` | Through the nginx `/api/router/` proxy |
| harnax-app (H5 / mini-program) | Chat plus a workspace subset | `X-Api-Key` | Mini-program uses a separate proxy path |
| harnax-client SDK | Methods mapping to `/agent/**` | `X-Api-Key` | `SpringHarnaxClient` / `SingleClient` |
| agent-service | `/instance/register`, `/instance/heartbeat`, `/instance/unregister` | Internal service token | `@InternalOnly`; external credentials always rejected |
| admin / ops | `/instance/drain`, `/instance/list`, `/health`, `/metrics/cache`, `/monitor/**` | Internal token or a management API key | — |

## 3. Deployment shapes

### 3.1 The two cache modes

`router.cache.type` decides where all shared state (instance registry, session bindings, circuit breaker, idempotency) lives:

| Capability | `local` (default) | `redis` |
|------------|-------------------|---------|
| Routing state | Process memory (`ConcurrentHashMap`) | Redis, shared across replicas |
| Call logs | SQLite (embedded) | MySQL |
| Replica count | 1 | Any |
| Circuit-breaker consistency | This node only | Shared by all nodes |
| Failover | Executed by this node | Executed by any node (CAS race, one winner) |
| Session affinity | Visible to this node only | Visible to all nodes |
| State persistence | Lost on restart | Survives router restart (Redis durability depends on your AOF/RDB) |
| Use for | Development / testing / single box | Production |

> **The binding lifetime is the same value in both modes** (`RedisSessionMappingService.SESSION_TTL` = 24h), not two separate knobs. "How long until a session may move to another agent" therefore behaves identically in dev and in a cluster.

### 3.2 Redis topology support matrix

| Topology | Supported | Notes |
|----------|-----------|-------|
| Standalone | ✅ | Default. Run with `maxmemory-policy=noeviction` |
| Sentinel (replication + automatic failover) | ✅ | `REDIS_SENTINEL_MASTER` / `REDIS_SENTINEL_NODES` |
| **Redis Cluster** | ❌ **Not supported; configuring it fails startup** | See below |

The reason is a hard constraint, not "untested": Router state transitions rely heavily on **multi-key Lua scripts** —

- writing one session binding also updates that instance's reverse index (two keys: `router:session:*` and `router:instance_sessions:*`);
- an instance status transition must complete atomically with the healthy set (`router:instance:*` and `router:instances:healthy`).

Those keys do not hash to the same slot in Cluster mode, and Redis returns `CROSSSLOT` **before** executing the script. The consequence is not partial degradation but immediate total failure: the first heartbeat after registration answers 500, `checkInstanceHealth()` throws every 5 seconds, and failover never happens.

`RedisConfig` therefore throws `IllegalStateException` at startup when `REDIS_CLUSTER_NODES` is non-empty, naming the alternative — rather than letting a process that "looks alive with a broken registry" reach production. `RedisConfigTest` locks this behaviour.

**Capacity view**: all Router state is "tens of thousands of bindings plus a few hundred instance records", on the order of tens of megabytes. One node is nowhere near its limit, and Cluster's horizontal scaling buys this component nothing.

### 3.3 Two things called "cluster" are not the same thing

| Name | Meaning |
|------|---------|
| Spring profile `cluster` (`--spring.profiles.active=cluster`, i.e. `application-cluster.yml`) | **The Router deployment shape: multiple replicas + MySQL + Redis.** Still connects to standalone / Sentinel Redis. This is the supported production mode |
| Redis Cluster (`REDIS_CLUSTER_NODES`) | Redis' own sharded cluster. **Not supported** |

In docker-compose the Router runs with `SPRING_PROFILES_ACTIVE: cluster`; that is the former, unrelated to Redis Cluster.

### 3.4 What is promised while Redis is unreachable

"Does a Redis outage take the Router down?" is the most common ops question. The answer has four layers and callers need each one's semantics:

| State | Behaviour while Redis is unreachable | Cost |
|-------|--------------------------------------|------|
| Requests for already-bound sessions | Read this node's shadow bindings (`lastKnownBindings`, 50k entries / 24h) | Affinity across router replicas is not guaranteed during the window |
| Placement of new sessions | Read the last Redis snapshot of instances (`knownInstances`) | Snapshots age out by heartbeat timeout; once aged out the Router prefers not to route at all over hammering a fleet that may be entirely gone |
| Circuit-breaker decisions | Read failure → allow the request (fail-open) | A tripped instance may be selected again during the window |
| Writing a new binding | `placeUnpersisted`: effective on this node only | Other replicas cannot see the binding |

**Explicitly not promised**: cross-replica session affinity while Redis is unreachable. The Router guarantees "request threads are not exhausted, the service is not silently dead", not "routing results match what Redis would have said once healthy".

## 4. Session binding lifecycle

### 4.1 Creation and expiry

| Event | Behaviour |
|-------|-----------|
| First request for an unbound session | Placement decision + atomic binding write |
| Every subsequent request through that session | Binding TTL refreshed (24h) |
| 24h with no request | Binding expires; the next request places it afresh |
| Session cleared (`DELETE /agent/session/{sessionId}`) | Forwarded to the instance for CLEAR, then unbound |
| The bound instance is judged DOWN | Batch re-route to other instances (see 4.3) |

> The 24h TTL means "a session that has been out of the Router's sight for a day may change instance". It is not the business lifetime of a session — that lives in admin's session table.

### 4.2 Instance state machine

```
  register          heartbeat older than 30s
UP ──────────── DOWN ◄────────────  health check (scan every 5s)
│                ↑
│ /drain         │ heartbeat recovers (only non-DRAINING resets to UP)
↓                │
DRAINING ────────┘
   │
   └─ existing sessions keep being served, new ones are refused; no way back
```

| State | Accepts new sessions | Serves existing ones | Counted as healthy |
|-------|----------------------|----------------------|--------------------|
| `UP` | ✅ | ✅ | ✅ |
| `DRAINING` | ❌ | ✅ | ✅ (alive, just not taking new work) |
| `DOWN` | ❌ | Triggers re-route | ❌ |

**DRAINING is currently a one-way door**: after `/instance/drain` there is no `undrain`, and the heartbeat script deliberately preserves DRAINING instead of resetting it to UP. The only way back to accepting new sessions is for the instance to `register` again. Operationally that means **drain is for "about to shut down", not for "temporarily pull it out to debug"**.

### 4.3 Failover

When the health check (`router.health.check-interval-ms=5000`) finds an instance whose heartbeat is older than 30s:

1. CAS-mark it DOWN — with several replicas only one wins; the others see `rows == 0` and skip;
2. Sessions on it are **re-routed in batches** of 500, up to 200 batches;
3. Target selection applies **overload avoidance**: if the target holds more than 2× the cluster average, pick one at or below 1.5× instead;
4. Per-instance failover has a 10-second cooldown (`failoverCooldownMs`, hard-coded) so flapping cannot churn sessions;
5. After re-routing, the old instance gets an **eviction notice** (see 4.4).

> **Placement exclusion is not eviction**: tripped or DRAINING instances only affect where **new** sessions go. A bound session is never moved because of a circuit breaker — only when its instance goes DOWN.

### 4.4 Eviction semantics: STOP_SANDBOX, not CLEAR

After a session moves, the Router tells the old instance to stop its sandbox — with `STOP_SANDBOX`:

- the workspace snapshot, transcript and plans are **kept**;
- only the sandbox process and its reserved resources stop.

This is deliberate: a session may simply have been placed on a better instance, and the user should find everything intact when they return. Controlled by `router.migration.evict-old-instance` (default `true`); eviction runs asynchronously on a small thread pool (queue depth 200, excess dropped with a warning) and never occupies a request thread.

### 4.5 Clearing a session

`DELETE /api/router/agent/session/{sessionId}` is forwarded to the bound instance. Note the difference from unbinding: clearing the transcript does not move the session — it stays bound to the same instance.

## 5. Endpoint contract

### 5.1 Agent proxy (14 endpoints; credential plus ownership check)

All under `/api/router/agent`, all routed by `sessionId`.

| Method and path | Body | Returns | Purpose |
|-----------------|------|---------|---------|
| `POST /chat` | `ChatAgentRequest` | `ResultVo<ChatResponse>` | Non-streaming chat |
| `POST /chat/stream` | `ChatAgentRequest` | `Flux<ChatEvent>` (SSE) | Streaming chat (main path) |
| `POST /command` | `CommandAgentRequest` | `ResultVo<CommandResponse>` | Imperative control (`/clear`, `/stop`, `/compact`, `/enable`, …) |
| `POST /confirm` | `ConfirmAgentRequest` | `Flux<ChatEvent>` (SSE) | HITL tool confirmation, resumes the suspended run |
| `DELETE /session/{sessionId}` | — | `ResultVo<String>` | Clear the transcript |
| `GET /chat/history/{sessionId}` | — | `ResultVo` (passed through) | Load transcript |
| `GET /session/{sessionId}/plans` | — | `ResultVo` (passed through) | Plan list |
| `GET /session/{sessionId}/current-plan` | — | `ResultVo` (passed through) | Current plan |
| `GET /workspace/{sessionId}/files` | `path` etc., passed through | `ResultVo` (passed through) | List directory |
| `GET /workspace/{sessionId}/read` | `path`, passed through | `ResultVo` (passed through) | Read a text file |
| `POST /workspace/{sessionId}/upload` | multipart | `ResultVo` (passed through) | Upload into the workspace |
| `GET /workspace/{sessionId}/download` | `path`, passed through | file stream | Download a produced file |
| `GET /workspace/status` | — | `ResultVo` | Workspace status for all sessions (console view) |
| `GET /workspace/{sessionId}/status` | — | `ResultVo` | One session's status, including whether its sandbox is live |

Request body essentials:

```kotlin
ChatAgentRequest(sessionId, message, imageUrls = [], requestId = "", userId = null)
CommandAgentRequest(sessionId, command: CommandType, args = "", userId = null)
ConfirmAgentRequest(sessionId, isConfirmed, toolInfoList = [], toolResults = [], userId = null)
```

`userId` precedence: the end-user identity from the authentication context **wins over** `userId` in the body. When a trustworthy identity exists, a forged body value has no effect; only when no identity is available (e.g. an internal service token carrying no user) does it fall back to the body value, logging one info line.

### 5.2 Instance registry (`@InternalOnly`, prefix `/api/router`)

| Method and path | Caller | Semantics |
|-----------------|--------|-----------|
| `POST /instance/register` | agent-service | Registers itself (`host` + `port`, port must be 8000–9999) |
| `POST /instance/heartbeat` | agent-service | Refreshes the heartbeat (every 10s by default, `AGENT_HEARTBEAT_INTERVAL`); **when the instance is unknown the body carries `code=410`** (HTTP stays 200), which the agent turns into a re-registration |
| `POST /instance/unregister` | agent-service | Takes itself out and unbinds all of its sessions |
| `POST /instance/drain` | ops / admin | Graceful shutdown: no new sessions, existing ones kept (one-way door, see 4.2) |
| `GET /instance/list` | ops | Instances and their states |
| `GET /health` | Probes | Router-view health |
| `GET /metrics/cache` | ops | Cache mode and state size |

External API keys calling these always get 403 — `@InternalOnly` requires an internal service identity.

### 5.3 Monitoring and UI

| Path | Auth | Content |
|------|------|---------|
| `GET /api/router/monitor/instances` | **Credential required** | Map of the cluster's internal addresses; sensitive |
| `GET /api/router/monitor/call-logs` | **Credential required** | Recorded calls, with session ids and error text, **scoped to the caller's tenant**: a tenant-bearing credential sees only its own rows; only a caller with no tenant (internal service token / SYSTEM key) sees the whole table — including the rows whose `tenant_id` is NULL, which is what those callers write |
| `/ui`, `/index.html`, `/static/`, `/style.css`, `/app.js`, `/favicon.ico` | Open | Only the static files that render the page |

> The open paths (`harnax.auth.skip-paths`) contain **static resources only**. Never add `/api/router/` — `/instance/heartbeat` and `/instance/register` depend on `UnifiedAuthFilter` establishing `AuthContext`, which is what `InternalAuthorizationInterceptor` judges.

### 5.4 Idempotency

| Endpoint | Deduplicated | Basis |
|----------|--------------|-------|
| `POST /chat` | ✅ only when `requestId` is non-empty | Dedupe key = `requestId`, 60s window |
| `POST /chat/stream`, `/confirm`, `/command` | ❌ none | See limitation #4 |

A duplicate `/chat` returns `code=429` with `Duplicate request: {requestId}`. **Callers that want dedupe must generate and send `requestId` themselves** — channel currently does not, so this path is inert in practice.

## 6. Error semantics (the contract callers care about most)

### 6.1 Non-streaming: HTTP 200 plus a business code

**Most Router business errors return HTTP 200 with the error in the body**:

```json
{ "code": 500, "message": "Session xxx is not bound to any instance", "data": null }
```

This is an intentional convention: webui's request wrapper only reads `code`/`message` out of 2xx responses, and treats anything else as a swallowed network failure. **Judge success by `body.code`, not by the HTTP status.**

Only errors raised in filters and interceptors — before a controller is entered — use real HTTP statuses:

| Situation | HTTP | Response |
|-----------|------|----------|
| Missing or invalid credential | 401 | Status written directly by the filter |
| Origin not in the CORS allow-list | 403 | `Invalid CORS request` (happens *before* authentication, easily misread as "no permission") |
| External key on `@InternalOnly` | 403 | Rejected by the interceptor |
| Session belongs to another tenant | see 6.4 | `SecurityException` |

> **Known defect**: once control reaches the exception handler, everything except a few explicit branches (such as the 429 dedupe) collapses into business code `500`. Callers currently **cannot** distinguish "not bound" from "no instance available" or "invalid argument" programmatically, and must match on `message` text. See limitation #3.

### 6.2 Streaming: errors travel as events

`/chat/stream` and `/confirm` declare `produces = text/event-stream`. On the normal failure path the error is emitted as an event and the stream closes:

| Event type | Payload | Meaning |
|------------|---------|---------|
| `ErrorChatEvent` | `code: String`, `message: String` | **`code` is a `HarnaxErrorCode` string, not an HTTP status** — do not compare it to numbers |
| `EndEventChatEvent` | `tokenUsage?` | Normal end of output |

Content events: `StreamThinkingChatEvent`, `StreamTextChatEvent`, `CallToolChatEvent`, `ToolResultChatEvent`, `ToolConfirmChatEvent` (HITL suspension; the caller must answer via `/confirm`).

**Caller rule**: treat `ErrorChatEvent` as a business failure and stop. Do not retry the whole stream — if content was already delivered, a retry duplicates it. The Router obeys the same "no replay after the first event" rule internally.

### 6.3 Session not bound

When a session never had a conversation, or its binding expired, the Router does **not** contact any agent-service and answers with a not-bound error. Semantically this is "this session has not started yet". Workspace endpoints behave the same way — no binding means no files to list.

### 6.4 How an ownership rejection currently surfaces on SSE endpoints (mind this)

| Endpoint kind | Current behaviour | What the caller sees |
|---------------|-------------------|----------------------|
| Non-streaming (`/chat`, `/command`, history, workspace) | Exception reaches the global handler → JSON `ResultVo` | A readable error body (HTTP 200) |
| **Streaming (`/chat/stream`, `/confirm`)** | The guard sits outside the `try`, so the exception also escapes to the global handler, which tries to write JSON on an endpoint declared `text/event-stream` | Possibly a 406 negotiation failure; even when JSON is written, channel's `bodyToFlux(ChatEvent)` fails to decode it and its fallback reports "cannot reach the router" |

The consequence: **a correctly rejected cross-tenant access shows up as a misleading network fault**, sending debugging in the wrong direction. This is a known defect (limitation #2); until it is fixed, a stream that fails instantly with a "network" error should first be checked for a 403 ownership rejection.

## 7. Tenant isolation guarantees

### 7.1 Where it is enforced

All 14 session-scoped proxy endpoints pass `SessionAccessGuard.requireAccessible(sessionId)` **before** the Router looks for an instance. The convergence point is `boundInstance()`, so there is no "one endpoint forgot the guard" bypass.

What is compared: the caller's `tenantId` (from the JWT `tenantId` claim, or the tenant attached to the API key) against the owning `tenantId` that admin reports for the session. A mismatch throws `SecurityException`.

A `chn-` session became **attributable** in release 3: instead of answering "no such session" for that prefix, admin reads the owner off the `channel` row and **deliberately ignores its `active` flag** — a soft-deleted channel still belongs to the tenant stamped on the row, because deleting one cleans up neither the session nor the sandbox, and a guard that let anyone make a still-readable conversation unattributable would be a way to erase accountability. So "read another tenant's channel conversation or workspace with my own login" is a refusal today, not a pass. `task-` does not go this way: it is still refused by the prefix rule before any lookup, for callers that have an end user behind them (its owner lives in the scheduler domain and will come from that service's endpoint).

Worth recording why this guard exists at all: the Router stamps **its own** service token on the outbound call, so agent-service sees a peer service and cannot block cross-tenant access on the Router's behalf; and inbound authentication only answers "may this caller use the Router", never "may this caller read *this* session".

### 7.2 Three deliberate pass-throughs (not holes, but they define the guarantee's edge)

| Pass-through condition | Reason |
|------------------------|--------|
| Caller has no `tenantId` (internal service token, `SYSTEM`-scoped API key) | channel routes on behalf of users it already authenticated; there is nothing to compare, and inventing a denial would only push operators to disable authentication |
| admin does not know the session (`Unknown`) | Nothing is bound to it, so the proxy endpoints answer "not bound" without touching an agent — and ownership cannot be proven either way |
| admin unreachable (`Unreachable`) | Chat is not blocked because admin is down; logged at warn and allowed |

### 7.3 Granularity and precision

**The granularity must be stated plainly**: the isolation unit is the **tenant**, not the user.

- Different logged-in users **inside the same tenant can read each other's sessions** (anyone holding a valid credential of that tenant).
- User-level isolation has to come from above (admin's session authorisation, or the channel-side user↔session mapping). The Router does not provide it.

**Precision edges**:

| Item | Value / behaviour |
|------|-------------------|
| Ownership lookup cache | 5000 entries, 5 minutes after write → ownership is judged from the cached value inside that window |
| Lookup timeout | `admin.internal-api.timeout-response-ms=3000`, plus a 1s fallback bound |
| Where the lookup happens | On the request thread, via `runBlocking` — see limitation #8 |

### 7.4 Input and SSRF defences

| Defence | Rule |
|---------|------|
| `sessionId` format | `[A-Za-z0-9._:-]{1,128}`, `..` rejected; anything else fails as `IllegalArgumentException` |
| Instance registration address | `isValidIpAddress` plus loopback / private-range blocklist (`isBlockedHost`), port restricted to **8000–9999** |
| Workspace `path` | `substringBefore(';')` strips matrix parameters, then `UriUtils.encodePathSegment` / `pathSegment()` builds the URI — never string concatenation |

> The registration endpoint is the main SSRF surface (registering `169.254.169.254:80` would make the Router issue requests for the attacker), which is why validation happens at the **registration entry filter**, not at use time — dirty records never reach the registry.

## 8. Timeout and capacity budgets

### 8.1 Layered timeouts (current actual values)

| Layer | Parameter | Value | Notes |
|-------|-----------|-------|-------|
| Client → nginx | SSE location `proxy_read_timeout` | **900s** | Covers the streaming endpoints |
| Client → nginx | Plain `/api/router/` `proxy_read_timeout` | **60s** ⚠️ | Misaligned with the next row |
| nginx → Router | Follows the above | 60s ⚠️ | Long non-streaming calls get cut by the gateway first |
| Router → agent-service (non-streaming) | `router.proxy.read-timeout-ms` | **600s** | AI processing budget |
| Router → agent-service (connect) | `router.proxy.connect-timeout-ms` | 5s | |
| Router stream, idle | `stream-idle-timeout-seconds` | 120s | Silence this long ends the stream |
| Router stream, wall clock | `stream-max-duration-minutes` | 30min | Cap regardless of how chatty it is |
| Router → Redis | `spring.data.redis.timeout` / `connect-timeout` | 3s / 2s | **Deliberately short**: fast failure is what reaches the fallback path |
| Router → admin | `admin.internal-api.timeout-response-ms` | 3s | Plus a 1s fallback bound |
| Idempotency window | `router.idempotency.ttl-seconds` | 60s | |

⚠️ **Two of these disagree**: a non-streaming `/chat` may run 600s inside the Router while nginx gives up at 60s — the caller sees 504 while the Router may still be finishing successfully (duplicate model work and billing risk). See limitation #5.

### 8.2 Connection pool (keeping one bad instance from absorbing the Router)

| Parameter | Value | Semantics |
|-----------|-------|-----------|
| `max-connections-per-instance` | 50 | Reactor Netty keeps one pool per `host:port`, so this caps what a **single agent instance** may occupy |
| `pending-acquire-timeout-ms` | 10s | How long a caller waits for a slot when the pool is saturated |
| `pending-acquire-max-count` | 100 | Waiters beyond this are refused fast instead of timing out together |
| `pool-max-idle-seconds` | 60s | Idle connection recycling |
| `pool-max-lifetime-minutes` | 5min | Age-based recycle, so a re-balanced agent is not pinned |
| `max-in-memory-size-mb` | 16 | Buffer ceiling for non-streaming responses; **large downloads do not take this path** |

### 8.3 Circuit breaker

| Parameter | Value | Semantics |
|-----------|-------|-----------|
| `failure-threshold` | 3 | Consecutive retryable failures before opening |
| `open-duration-ms` | 30s | While open, no **new** session is placed on that instance |
| `probe-lease-ms` | 30s | Half-open state leases a **single** probe slot, so a concurrent burst cannot storm a half-open breaker |

Breaker state is shared through Redis in multi-replica deployments: an instance tripped by replica A is not used by B or C. In `local` mode it is node-local.

## 9. Observability

### 9.1 Metrics (`/actuator/prometheus`)

| Metric | Type | Tags | Use |
|--------|------|------|-----|
| `router.proxy.duration` | Timer | `endpoint` | Proxy latency distribution |
| `router.proxy.requests` | Counter | `endpoint`, `status` (`ok`/`error`) | Success rate |
| `router.failover.count` | Counter | `endpoint`, `attempt` | How often failover happens, and on which hop |
| `router.healthy.instances` | Gauge | — | Instances this replica could place on right now (DRAINING excluded) |

> Alerting note: use **`min()`, not `avg()`** for `router.healthy.instances` — when replicas disagree, an average hides "this replica cannot see any instance at all". Rule samples are in the README's alerting section.

### 9.2 Call logs (`api_call_log`)

Written to MySQL in `redis` mode; `local` mode keeps the same columns in SQLite.

| Field group | Fields |
|-------------|--------|
| Caller | `caller_id`, `caller_type` (`INTERNAL_SERVICE`/`EXTERNAL_API`), `tenant_id` |
| Session and target | `session_id`, `agent_id`, `agent_name`, `model_id`, `model_name`, `instance_id` |
| Request | `endpoint`, `method`, `request_type` (`CHAT`/`COMMAND`/`CONFIRM`), `request_id` |
| Result | `status_code`, `success`, `error_message`, `start_time`, `end_time`, `duration_ms` |

How it is collected: `ApiCallLogFilter` uses an allow-list strategy (only response bodies of paths worth capturing are cached), then batches asynchronously to the database (batch 50, every 5s) with a 10000-row buffer; overflow is dropped with a warning. Streaming requests are logged by an `AsyncListener` once the stream closes, so `duration_ms` covers the whole stream.

**Retention: none.** There is no time-based DELETE anywhere; the table only grows. Plan archiving or pruning yourself — see limitation #7.

### 9.3 Tracing a problem by sessionId

| Means | Reliability |
|-------|-------------|
| Query `api_call_log` by `session_id` | ✅ Most reliable; includes `instance_id`, so you know which agent to look at |
| `GET /api/router/monitor/instances` | ✅ Instance-level session distribution |
| MDC in application logs | ⚠️ Unreliable: MDC does not follow coroutine suspension and can leak onto the next request handled by the same Tomcat thread (limitation #11). **Do not confirm which instance served a call from log lines — use `api_call_log.instance_id`** |

## 10. Current limitations (source of truth)

Ordered by caller impact. "Workaround" is what to do until it is fixed.

| # | Limitation | Impact | Workaround |
|---|------------|--------|------------|
| 1 | **Redis Cluster is not supported**; `REDIS_CLUSTER_NODES` aborts startup | Redis HA must be standalone or Sentinel | Use Sentinel. If Cluster is mandatory, the key layout needs reworking first (single-hash-tag route, see README) |
| 2 | **An ownership rejection on SSE endpoints answers JSON, not an event stream** | Cross-tenant access looks like "cannot reach the router", misleading debugging | When a stream fails instantly with a network error, check for a 403 rejection first (Router log: `Rejected a cross-tenant session access`) |
| 3 | All non-streaming business errors collapse to code `500` | Callers cannot programmatically tell not-bound from no-instance from bad-argument | Match `message` text, or query session state first |
| 4 | **No idempotency on streaming endpoints**; `/chat` dedupe needs a caller-supplied `requestId` (channel sends none) | A client retry can trigger a second model call | Dedupe client-side; pass `requestId` if you want Router-side dedupe |
| 5 | nginx `60s` vs Router `600s` non-streaming read timeouts disagree | Long non-streaming calls get cut at the gateway while the Router may still succeed → duplicate work / billing | Use `/chat/stream` for long work (900s at nginx), or align the two settings |
| 6 | Hang-class failures (`ReadTimeoutException`, pool acquire timeout) neither trip the breaker nor fail over | An instance that accepts connections but never answers is not evicted automatically; callers just time out | Heartbeat timeout (30s) is the backstop; `/instance/drain` manually if needed |
| 7 | `api_call_log` has no retention policy or cleanup job | The table grows without bound | Schedule archive/delete by `start_time` yourself |
| 8 | Ownership check is **tenant-granular**, and the lookup uses `runBlocking` on the request thread | Users inside one tenant are not isolated from each other; a slow admin occupies request threads (up to ~4s worst case) | Enforce user-level isolation above the Router; keep admin healthy |
| 9 | `drain` is irreversible; there is no `undrain` | A mistaken drain needs an instance restart to recover | Confirm the instance is really leaving service before draining |
| 10 | `local` mode shares nothing | With several replicas each places independently and affinity drifts per node | Deploy `local` as a single replica (a deployment constraint, not a bug) |
| 11 | MDC is not cleared across coroutine suspension points | Log lines of a later request on the same Tomcat thread can carry an earlier session | Attribute via `api_call_log`, not application logs |

## 11. Code and document index

| Topic | Location |
|-------|----------|
| Proxy and placement flow | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt` |
| Endpoint definitions | `.../router/controller/AgentProxyController.kt`, `InstanceRegistryController.kt`, `RouterMonitorController.kt` |
| Session mapping (Redis / local) | `.../router/service/impl/RedisSessionMappingService.kt`, `LocalSessionMappingService.kt` |
| Instance registry and health | `.../router/service/impl/RedisInstanceRegistry.kt`, `.../router/health/HeartbeatHealthChecker.kt` |
| Circuit breaker | `.../router/service/InstanceCircuitBreaker.kt` with `impl/RedisCircuitBreaker.kt`, `LocalInstanceCircuitBreaker.kt` |
| Reverse-index reconciliation | `.../router/service/impl/SessionIndexReconciler.kt` |
| Tenant isolation | `.../router/service/SessionAccessGuard.kt`, `SessionInfoClient.kt` |
| Input format and SSRF defences | `.../router/support/IdFormat.kt`, `.../router/entity/AgentInstance.kt` |
| Call logging | `.../router/config/ApiCallLogFilter.kt`, `.../router/service/ApiCallLogService.kt` |
| Topology decision (Cluster rejection) | `.../router/config/RedisConfig.kt`; test `src/test/.../config/RedisConfigTest.kt` |
| Event and request protocol | `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/` (`AgentRequest.kt`, `ChatEvent.kt`) |
| Module internals and alerting rules | [`harnax-session-router/README.md`](../harnax-session-router/README.md) |
| Deployment steps | [`docs/deploy-harnax-session-router.md`](../docs/deploy-harnax-session-router.md) |
| Channel-side integration | [`prod_doc/channel-integration.zh-CN.md`](./channel-integration.zh-CN.md) |
| Cross-service call map | [`docs/http-call-network.md`](../docs/http-call-network.md) |
