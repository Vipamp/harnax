# Leader Agent vs. Sub-agent: Architectural Trade-offs (Should the Lead Call Tools Itself?)

> Chinese version: [multi-agent-leader-subagent-design.zh-CN.md](./multi-agent-leader-subagent-design.zh-CN.md).
>
> Written 2026-09-20. This is a **general architecture analysis**, not bound to a specific implementation; only §10 returns to this project.
> Sources are public first-party material (§12). Key figures and quotations were verified individually on 2026-09-20; unverified inference is labelled as such.
> How to read it: §0 is the memorizable conclusion card; §3, §6, §8, and §9 are where you get pressed; §11 is the follow-up-question drill.

---

## 0. Conclusion Card

**One sentence**: mainstream practice is not a choice between "the lead calls tools" and "the lead only schedules" — it is **the lead orchestrating as its main job while keeping only decision-type and read-only capabilities, with heavy execution and side effects pushed down**.

**Three tests** (apply to any capability):

| Test | If yes | Why |
|------|--------|-----|
| Will this call's response body occupy the lead's context for a long time? | Push down | The lead's context is the most expensive resource in the system; every member's result eventually converges back into it |
| Is the work parallelizable or repetitive? | Push down | Parallelism is the only guaranteed payoff of delegation |
| Does it have side effects that must be funneled? | Push down, and keep **one single exit** | Concurrent-write and audit-attribution problems |
| Is it pure knowledge (skill, methodology, acceptance criteria)? | **Keep with the lead** | It doesn't refill the context, and it lowers the chance of a bad dispatch |

**One counter-warning**: with only one lead and 2–3 members, and tasks that don't decompose in parallel, the extra hop is usually a net loss (relay overhead + doubled tokens). Multi-agent pays back on **breadth-first, parallel-decomposable** work.

---

## 1. Fix the Terminology First: Delegation Has Three Distinct Semantics

Mixing these up is the fastest way to be seen through.

| Semantics | Control flow | Context | Typical implementation | Fits |
|-----------|--------------|---------|------------------------|------|
| **Handoff** | **Transferred** to the sub-agent; the lead no longer intervenes | Whole conversation history travels with it | OpenAI Agents SDK (a handoff is exposed to the model as a tool) | Triage/routing to a specialist who faces the user directly |
| **Agent-as-tool** | Stays with the lead | Sub-agent sees only the call arguments; returns a summary | Register a sub-agent as a tool | Lead must synthesize several specialists' conclusions |
| **Orchestrator-workers** | Stays with the lead | Lead decomposes dynamically, dispatches in parallel, synthesizes afterwards | Anthropic research system | Breadth-first retrieval, exploratory decomposable tasks |
| **Group chat / negotiation** | No fixed owner | Shared broadcast messages | AutoGen GroupChat | Multi-perspective discussion; hard to control termination |
| **Fixed pipeline / graph** | Determined by the graph | Determined by node contracts | Explicit LangGraph graph | Steps are known and reproducibility matters |

**Why the distinction has real consequences**: three things all differ — who owns interrupt/cancel semantics, which runtime a human confirmation returns to, and whose bill the tokens land on. Choosing handoff but still wanting to synthesize afterwards, or choosing as_tool while handing the member the full conversation history, are common design errors.

---

## 2. Division of Responsibilities

| Responsibility | Lead | Sub-agent |
|----------------|------|-----------|
| Clarify the goal, ask the user | ✅ Exclusive (only it faces the user) | ❌ |
| Decompose the task and set boundaries | ✅ Exclusive | ❌ |
| Write a self-contained brief per subtask | ✅ Exclusive; quality decides success | — |
| Evidence-gathering tool calls (search, read, query) | 🔸 Fine for narrow read-only | ✅ Primary |
| Production / side-effecting actions (write, commit, send, deploy) | ❌ | ✅ Single exit |
| Progress coordination, re-dispatch, timeout and cancellation | ✅ Exclusive | ❌ |
| Accept members' artifacts | ✅ Must retain the ability to verify, otherwise it degenerates into blind trust | — |
| Final synthesis and outward expression | ✅ Exclusive | ❌ |

The key is the third row from the bottom: **the lead must keep a minimal ability to verify on its own**. A pure router (zero tools) and "do everything myself" are the two opposite failure ends — the first blindly trusts member reports, the second turns delegation into paraphrase.

---

## 3. The Core Debate: Should the Lead Call Tools Itself?

### 3.1 The Spectrum of External Positions

| Position | Source | Stance on lead tool access | Key argument |
|----------|--------|----------------------------|--------------|
| Lead keeps planning and memory tools; evidence gathering is fully delegated | Anthropic, *How we built our multi-agent research system* | Middle ground: lead has memory/plan, heavy actions such as search belong to members | Each sub-agent has its own context window and can cover different facets of a query in parallel |
| Orchestrator-workers defined as "decompose — delegate — synthesize" | Anthropic, *Building Effective Agents* | What the lead calls are **other LLMs**, not business tools | "a central LLM dynamically breaks down tasks, delegates them to worker LLMs, and synthesizes their results" |
| **The lead should act less, not more** | LangChain, *Benchmarking Multi-Agent Architectures* | Explicitly restricts direct lead calls | Measured: most supervisor-architecture losses came from "translation" errors when the supervisor played telephone between sub-agents and the user; the fix was to make the lead do less and hand over complete task descriptions |
| Handoff and call semantics coexist; agents always keep their own tools | OpenAI Agents SDK docs | No restriction — the chosen semantic carries the consequences | A handoff is "a transfer of control represented as a tool to the LLM"; `as_tool` is the nested-specialist form |
| **Against handing execution to context-thin sub-agents** | Cognition, *Don't Build Multi-Agents* | Whoever executes must see full context; sub-agents should answer questions, not perform writes | Two principles: share full context and traces; actions carry implicit decisions |
| In narrow-domain systems the lead holding domain tools is normal | arXiv 2412.05449 (AgentOrchestra) | Allows lead business tools | Paper's example supervisor owns domain tools and carries the team goal |

**Spectrum conclusion**: nobody advocates "the lead has zero tools", and nobody advocates "lead and members share the same toolset, whoever calls doesn't matter". Disagreement concentrates on **how much** the lead may call, and it converges on: split by response-body size and side effects, not by business vs. non-business.

### 3.2 The Three Viable Designs

| Design | Pros | Cons | Fits scale |
|--------|------|------|------------|
| **A. Pure router** (lead has zero business tools) | Cleanest permission boundary; unambiguous cost attribution; cannot concurrently mutate state with members | Lacks methodology → vague dispatch briefs; cannot verify → blind trust; every small task costs an extra hop | Many members, genuinely parallel work, strict compliance needs |
| **B. Read-only lead** (skills + small-response read-only tools; no writes, no heavy evidence-gathering MCP) | Balances judgement with context hygiene; side effects still single-exit | Requires a **allowlist** maintained long-term; the "read-only" line erodes as tools are added | Generally optimal; works for 1–10 members |
| **C. Full-capability lead** (lead's toolset ≈ members'; it executes when convenient) | Simplest; saves one hop; most efficient for strictly serial work | Lead context inflates fast; permissions and audit lack a single exit; in practice degenerates into "one agent that occasionally spawns another" | ≤3 members, non-parallelizable tasks |

My view: **default to B**. Go to C when the system is small and work is serial. Choose A only with strong compliance/multi-tenant requirements and willingness to accept lower dispatch quality.

### 3.3 Why Skills Go to the Lead but MCP Splits in Half

**Skills and tools are not the same kind of object.** A skill is instructions on *how to do it and how to accept it*; once triggered it enters the instruction layer and does not refill large response bodies. Without the relevant skill, the lead fails in two concrete ways:

1. It cannot write a competent dispatch brief — it doesn't know what output format or tool boundaries to demand.
2. It cannot judge what the member returned, so row "accept artifacts" in §2 collapses.

**MCP servers and business tools actually execute actions**, so split them with the three tests:

- Keep with the lead: look up one object's state, list options, narrow retrieval, send a confirmation to the user. These serve **verification and decision-making**.
- Push down: full-corpus retrieval, page scraping, large-result queries, directory walks (large response bodies); writes, commits, deployments, outbound messages to third parties (side effects needing a funnel).

### 3.4 Two Anti-patterns

- **The lead calls every tool itself and then relays conclusions to members**: pays the tokens twice and loses information in the relay. This is the concrete shape of LangChain's "telephone" loss.
- **The lead redoes a member's work afterwards**: usually appears when the lead holds the full write toolset and acceptance criteria were left vague. It turns multi-agent into a single agent at 2× cost.

---

## 4. Capability Attribution Matrix (copy this into a design)

| Capability type | Lead | Member | Note |
|-----------------|------|--------|------|
| Planning / to-do / task coordination | ✅ | 🔸 Not in the first release | Single level initially; members cannot re-delegate |
| Process and methodology skills | ✅ | ✅ | Both; the lead needs them to write and check dispatches |
| Execution skills (CLI, Shell included) | ❌ | ✅ | Handing these to the lead invites it to just do the work |
| Read-only, small-response query tools/MCP | ✅ | ✅ | On the lead side, purely for verification |
| Large-response retrieval/scraping | 🔸 Very narrow only | ✅ | Main risk is polluting the lead's context |
| Side-effecting write tools/MCP | ❌ | ✅ Single exit | Concurrent writes + audit attribution |
| Initiating human confirmation | ✅ Exclusive | 🔸 Propagated up via the lead | The user-facing exit must be unique |
| User identity and credentials (e.g. MCP OAuth tokens) | ✅ Held by the root run | 🔸 Issued scoped to the root identity | A member must never act as "the member's creator" |
| Execution sandbox | ❌ | ✅ One per member | Member environments stay uncontaminated |

One easily missed line of defence: **capability restriction must be enforced in assembly and runtime validation, not by a prompt saying "do not execute tasks yourself."** Default registration paths (framework Shell, default sub-agents, mandatory tool sets) will leak capability straight back in.

---

## 5. Context Economics (why the split above holds)

Verified quantitative facts (all from the Anthropic engineering blog, re-checked 2026-09-20):

- Agentic chats use about **4×** the tokens of ordinary chat; **multi-agent systems about 15×**.
- On their internal research evaluation, the multi-agent system beat the single-agent baseline by **90.2%**.
- Parallelizing tool calls / sub-agents cut research time by **up to 90%** on complex queries.
- A delegation brief must contain four elements: **an objective, an output format, guidance on the tools and sources to use, and clear task boundaries**.

**How to read those four numbers** (your anchor when pressed on cost):

1. 15× and 90.2% are **two sides of one coin** — multi-agent accuracy is bought with more tokens and more parallel windows, so it inherently pays back only on breadth-first, parallelizable work. Treating it as the default architecture means spending 15× for a benefit you may not need.
2. That 90.2% comes from a **research** evaluation, not a coding one. Quoting it across task types is wrong, and that is exactly where Cognition pushes back.
3. Delegation is fundamentally a **context-engineering technique**: isolated windows in exchange for a clean lead window. So the test between "compress the context" and "delegate to a sub-agent" is: does this content belong to the decision I am making now? If yes, compress and keep it; if no, delegate and bring back only the conclusion.
4. The 15× also explains why **each extra large-response tool call by the lead compounds**: that content is re-billed at every subsequent step.

---

## 6. The Case Against (be able to state it yourself or you will be exposed)

Cognition's *Don't Build Multi-Agents*, two principles:

1. **Share full context and traces**, not summaries.
2. **Actions carry implicit decisions** — every tool call and every code block encodes decisions (which style, how edge cases are handled, how it stitches to existing code). Letting parallel sub-agents that only saw a summary execute means handing decision authority to the party with the thinnest context, and the result is **conflicting assumptions acted upon in parallel**.

Their alternative: a single-threaded linear agent with context compression, or sub-agents that answer questions and give recommendations but never perform writes.

**Where they are right**: for write-heavy, tightly-coupled step sequences (coding being typical), parallel delegation genuinely is a net loss. LangChain itself concedes that "most coding tasks involve fewer truly parallelizable tasks than research", and agrees context sharing is the crux.

**Where their scope ends** (three rebuttal anchors):

1. They object to **parallel execution of writes**, not to role separation. For breadth-first retrieval, members return conclusions without making write decisions — the two positions don't conflict there.
2. Their premise is that **one context window suffices**. When it doesn't, summary-based delegation isn't an optional optimization but the only route; so the real question is "what content deserves the lead's window", not "whether to delegate".
3. They don't address **permission and identity attribution**. A multi-tenant platform needs a structural boundary for "who may call which MCP, as whom"; a single agent makes that question disappear rather than solving it.

My synthesis: **Cognition's constraints apply to the execution side; Anthropic's practice applies to the evidence-gathering side. Together they yield exactly the middle ground in §3 — the lead keeps judgement and verification, members do execution.**

---

## 7. When Not to Use Multi-agent

Fall back to a single agent plus context engineering if any of these hold:

- Steps are strongly dependent — the previous output determines the **concrete next action** (coding, debugging, migration).
- The same state must be repeatedly read and modified (no unique write exit).
- The member count caps out at 2–3 with no parallelizable retrieval surface.
- Correctness depends on cross-turn memory, and the end-to-end eval cannot tolerate 15× cost.
- The goal is too vague to write "objective + output format + boundaries" — **if you cannot write the brief, the task is not ready to be split**.

---

## 8. Production Failure Modes

| # | Symptom | Root cause | Defence |
|---|---------|------------|---------|
| F1 | The lead keeps relaying between user and members; answers drift | Telephone/translation loss (LangChain's measured primary loss) | Self-contained briefs; the lead never relays a second time |
| F2 | Members duplicate work, or all miss the same part | Task boundaries not fixed | The four-element brief: objective / output format / tool-and-source guidance / boundaries |
| F3 | 50 sub-agents spawned for a trivial query | No "scale effort to complexity" rule | Explicit rule: one agent when simple, only then 10+ when complex |
| F4 | Two members write the same artifact and overwrite each other | No unique write exit | One owner per side effect; or immutable artifacts with reference handoff |
| F5 | The lead accepts member conclusions wholesale; final answer contains hallucinations | Lead has no verification capability | Keep narrow read-only queries (§4) |
| F6 | Cost runs away and nobody can say where | Cost not attributed to the root run | Bill at the root run; child-run cost attributable but not double-counted |
| F7 | Endless digging, never converges | No completion condition or budget | Three gates per child run: steps / wall clock / budget |
| F8 | Nested delegation cycles, or cancelling only stops the root | Depth and cancellation propagation undefined | Lock to a single level initially; stop must block new delegation |
| F9 | A member uses credentials it shouldn't have | Identity lost during delegation | Issue scoped to the root user's identity; refuse exchange for unknown child IDs |
| F10 | Failure silently rerouted onto the lead's model, so output semantics shift | Framework fallback treated as a feature | No silent degradation for member failure; report explicitly |
| F11 | "The UI can pick a team" gets reported as "every entry point supports teams" | New entry points never validated separately | Validate channels and scheduled tasks one by one for concurrency, confirmation, identity propagation |

F1 and F5 are the two opposite ends of the same axis: over-delegation causes telephone loss, over-thorough delegation causes blind trust. **Both must be defended simultaneously** — that is the empirical reason the middle ground holds.

---

## 9. How to Prove It Works (evaluation and observability)

The question "how do you know multi-agent beats single-agent" is asked next; without an answer, everything before it reads as memorized.

- **LLM-as-judge with an explicit rubric**: suitable for short-horizon tasks; Anthropic's phrasing is that it "scales when done well". The rubric must score process ("did it use all the sources", "did it split correctly"), not only the final answer — otherwise F2 goes undetected.
- **End-state / state-based evaluation**: long-horizon tasks cannot be judged on trajectory alone; look at the environment's final state. The hard part is that "each action changes the environment", unlike read-only retrieval — which means side-effecting scenarios must be evaluated in resettable sandbox environments.
- **Ablation, not comparison**: run the same task set at three settings — single agent / zero-tool lead / read-only lead — and compare accuracy, tokens, latency, and duplicate-work rate. Three settings suffice to answer the §3 choice.
- **Process visualization**: render each agent's reasoning and the delegation tree. Multi-agent bugs are mostly **structural** (a wrong dispatch boundary); looking only at final answers never locates them.
- **Metrics must include cost**: reporting accuracy gains alone looks like buying score with tokens. Accuracy and tokens must be reported as a pair.

---

## 10. Back to This Project: Our Position and Its Price

This project (a self-hosted agent system, a personal project) chose a **tightened variant of A**, not the B recommended in §3.2:

- A Team is an independent grouping configuration; lead and members both reference existing Agents, and standalone Agent behavior is unchanged.
- The lead only decomposes, delegates, coordinates, accepts, re-dispatches, and summarizes; **no business Tool or MCP is loaded, no execution Skill / CLI / Shell is exposed, and no execution sandbox is created**. Members load according to their own configuration and use independent sandboxes.
- Members do not share a writable workspace; they hand off through MinIO publish / reference / fetch.
- Restrictions are enforced by assembly and runtime validation, not by prompts.

**Where we diverge from external consensus, and the price paid** (record this honestly — don't only present the upside in an interview):

| External recommendation | This project's choice | What it buys | What it costs |
|-------------------------|------------------------|--------------|---------------|
| Lead keeps process skills | No execution skills for the lead at all | Zero execution entry point on the lead side; a simple acceptance gate (V2) | The lead may lack acceptance methodology → F5 risk |
| Lead keeps small-response read-only MCP/tools | The lead connects to no MCP at all | Unique credential exit; clean OAuth scoping (F9 disappears) | The lead can only trust member reports → F5 risk |
| Decide delegation by task size | Fixed single-level serial delegation in the first release | Event correlation, confirmation resume, and cancellation propagation are verifiable | The parallel payoff on breadth tasks is forfeited (the F3-related upside too) |

Put plainly: **this project trades "zero lead capability" for "zero holes in the permission and audit boundary"**. That is a deliberately stricter setting, not a general recommendation. If F5 actually materializes (the lead cannot accept member artifacts), the first thing to relax is **read-only, small-response** verification capability — not write capability.

Full decision and acceptance list: [multi-agent-team-design.en-US.md](./multi-agent-team-design.en-US.md) (D3/D5/D6, §5, V2/V3/V10).

---

## 11. Follow-up Question Drill

**Chain 1 — "Can your lead Agent call tools?"**

- Why asked: one sentence tells whether you actually designed multi-agent or copied an architecture diagram.
- Follow-ups: then how does it judge whether a member did it right? → why not give it read-only tools? → how do you define the permission boundary? → how is cost counted?
- Anchor: give the tests first (response size + side effects), then our choice (tightened to zero, in order to keep the credential exit unique), then volunteer the cost (F5, and which class you'd relax first).
- Backing: §0, §3.2, §10 here; the Team design doc §5, V2, V10.
- Risk: 🟢 Well supported. Admitting where your option is sub-optimal is a plus.

**Chain 2 — "Is multi-agent always better than single-agent?"**

- Why asked: checks whether you're following a trend.
- Follow-ups: why do you use it then? → do you have data? → Cognition says don't build multi-agents — your take?
- Anchor: cite with the task-type limit attached (90.2% on a research eval, at roughly 15× tokens); concede that most coding tasks have little true parallelism; then give the fallback checklist (§7).
- Backing: §5, §6, §7.
- Risk: 🟡 **Never present external figures as your own measurements** (personal project: no numbers at all). The safe phrasing is "the publicly reported order of magnitude from Anthropic is…".

**Chain 3 — "What happens when a sub-agent fails, returns wrong results, or runs too long?"**

- Why asked: tests real production experience.
- Follow-ups: does a timeout move it to background? → after an interruption, are side-effecting operations replayed? → how does cancellation propagate? → will the lead's model pick up a failed member's work?
- Anchor: three gates (steps / deadline / budget) → stop must block new delegation → no automatic replay of side-effecting operations → no silent member-model fallback (F10).
- Backing: §8 F7/F8/F10; the Team design doc §9.3, V9, V12.
- Risk: 🔴 If pressed for exact thresholds, stay qualitative per the wording rule; do not invent numbers.

**Chain 4 — "How do Skills and MCP get distributed across agents?"**

- Why asked: only people who actually did capability assembly get tripped by this detail.
- Follow-ups: why treat skills and tools separately? → if the lead doesn't load member tools, how does it delegate? → where do member credentials come from?
- Anchor: skills enter the instruction layer and don't refill context, so both sides get them; tools execute actions, so split by side effects; member capabilities come from **each member's own full assembly**, not pruning from the lead's set (mounting the union of member tools/MCP onto the lead is an explicit rejection).
- Backing: §3.3, §4; the Team design doc §10.3.
- Risk: 🟢 The highest-signal question here; answering concretely is a clear plus.

---

## 12. Sources (verified 2026-09-20)

- [How we built our multi-agent research system — Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system): lead tools and memory, 90.2% / 4× / 15× / up to 90% time saved, the four-element delegation brief, failure modes.
- [Building Effective AI Agents — Anthropic](https://www.anthropic.com/engineering/building-effective-agents): the orchestrator-workers definition.
- [Benchmarking Multi-Agent Architectures — LangChain](https://www.langchain.com/blog/benchmarking-multi-agent-architectures): the measured supervisor "telephone / translation" loss.
- [How and when to build multi-agent systems — LangChain](https://www.langchain.com/blog/how-and-when-to-build-multi-agent-systems): breadth-first tasks are where it pays; coding parallelizes poorly.
- [Handoffs — OpenAI Agents SDK](https://openai.github.io/openai-agents-python/handoffs/): handoffs represented as tools, agents retain their own tools, `as_tool` nesting semantics.
- [Don't Build Multi-Agents — Cognition](https://cognition.com/blog/dont-build-multi-agents): the two opposing principles and their alternative.
- [Towards Effective GenAI Multi-Agent Collaboration (AgentOrchestra) — arXiv 2412.05449](https://arxiv.org/html/2412.05449): an example of a supervisor owning domain tools.
- [Orchestrator-Worker Agents: A Practical Comparison of Common Agent Frameworks — Arize](https://arize.com/blog/orchestrator-worker-agents-a-practical-comparison-of-common-agent-frameworks/): cross-framework comparison of orchestrator-worker implementations.
