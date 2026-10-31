# Syntra OS — Universal AI Execution Platform: Audit & Architecture

Status: design proposal (no code changed). Target: Android, 8 GB RAM devices.

---

## 1. Repository audit (v0.8, ~4,300 lines Kotlin + 1 C++ JNI file)

| Area | What exists | Verdict |
|---|---|---|
| Inference | `syntra_llm.cpp` JNI over pinned llama.cpp (+mtmd vision): load, generate with KV-prefix reuse, system-prompt checkpoints to disk, shared-weight `fork`, `scoreLabels` (classifier), optional Vulkan | **Keep** — becomes the `LlamaCppAdapter` |
| Model lifecycle | `LocalEngines`: one generation at a time, RAM-aware eviction, background priority, 3-min cap | **Keep logic**, reshape into `ModelManager` |
| Model catalog | Hard-coded `ModelRole` enum (TEXT/VISION/AUDIO/OMNI/DECISION) + LFM3 HF repos | **Replace** — LFM-specific, violates "model-agnostic" |
| Prompt format | `LfmPrompt` (ChatML + LFM3 `<tool_call>` parser + fallbacks) | **Keep** as one `ChatTemplate` among several |
| Cloud | `ChatClient` (OpenAI-compatible) | **Keep** as `OpenAiCompatAdapter` |
| Local API | `LocalServer` on 127.0.0.1:8099: OpenAI + Anthropic Messages translation | **Keep** — exposes Syntra models to Linux tools |
| Agent | `Agent.kt`: one loop, ~35 tools in one `when`, router, approvals, MCP, team calls | **Rewrite** into Harness + ToolRegistry (good parts move over) |
| Router | Keyword → tool groups, optional d1 | **Keep idea**, becomes tool-selection stage |
| Memory | `Graph` JSON (nodes/edges, label-propagation communities), auto-extraction | **Keep** as *Conversation memory*; RAG is new |
| Skills | Markdown fetch from GitHub, frontmatter parse, off-until-enabled | **Replace** with manifest-based Extension Manager |
| Execution | Ubuntu via self-built proot, Termux RUN_COMMAND, accessibility phone control, notifications, WorkManager schedules, Telegram, MCP client, web search/fetch/Scrapling | **Keep**, wrap each as a `Tool` provider |
| Storage | JSON files, settings encrypted with Keystore | **Migrate** to Room (tasks, chunks, extensions) |
| UI | 6 tabs, one ViewModel (~400 lines), custom theme with hard-coded colors/shapes | **Redesign** (section 9) |

**Main limitations found**
1. LFM assumptions in types (`ModelRole`, `CATALOG`, `LfmPrompt` hard-wired in engine).
2. God objects: `AppViewModel` and `Agent` own everything; nothing is testable in isolation.
3. Tasks are not resumable: no checkpoints — a crash or model swap loses the run.
4. "Specialists" are swapped mid-loop with no saved state (cause of the v0.6 freeze).
5. No real RAG: memory is labels + notes, retrieved by substring.
6. Skills are just prompt text; no tools, dependencies, versions or permissions.
7. Results are never verified (a tool "succeeds" if it returns a string).
8. Linux tools still run arbitrary `curl | bash` installers without inspection.

---

## 2. Module layout

```
:core:runtime     — interfaces only: ModelAdapter, Tool, Extension, TaskStore, Retriever (no Android UI)
:core:models      — ModelManager, adapters (llama.cpp JNI, OpenAI-compat, Anthropic), templates, capability probe
:core:harness     — agent loop, planner, verifier, checkpoints, tool registry, permission broker
:core:memory      — conversation graph, task state, RAG (chunker, embedder, index)
:core:extensions  — manifest parser, installer, sandbox, dependency + compat checks
:exec:*           — tool providers: linux, termux, android, web, github, files, docs, media, mcp
:feature:*        — UI: chat, tasks, models, extensions, memory, terminal, settings
:app              — wiring (Koin), foreground service, navigation
```
Rule: `:core:*` never depends on `:exec:*` or `:feature:*`; tools are discovered through the registry.

---

## 3. Core Runtime API

```kotlin
interface SyntraRuntime {
    val models: ModelManager
    val tools: ToolRegistry
    val extensions: ExtensionManager
    val memory: MemoryService          // conversation graph
    val knowledge: KnowledgeService    // RAG
    val tasks: TaskEngine              // long-running, resumable runs
}

interface TaskEngine {
    fun start(request: TaskRequest): TaskId                   // returns immediately
    fun observe(id: TaskId): Flow<TaskEvent>                 // progress, tool calls, approvals, artifacts
    suspend fun resume(id: TaskId)                            // from last checkpoint (after crash/restart)
    fun cancel(id: TaskId)
    suspend fun answerApproval(id: TaskId, approvalId: String, allow: Boolean)
}

sealed interface TaskEvent {
    data class Planned(val steps: List<PlanStep>) : TaskEvent
    data class Token(val text: String) : TaskEvent
    data class ToolStarted(val call: ToolCall) : TaskEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult) : TaskEvent
    data class NeedsApproval(val id: String, val call: ToolCall, val risk: Risk) : TaskEvent
    data class Artifact(val file: ArtifactRef) : TaskEvent     // real files the user can open/share
    data class ModelSwitched(val from: String?, val to: String) : TaskEvent
    data class Done(val summary: String, val verified: Boolean) : TaskEvent
    data class Failed(val error: SyntraError, val resumable: Boolean) : TaskEvent
}
```
Runs execute inside the existing foreground service, so they survive leaving the app.

---

## 4. Model Adapter Interface

```kotlin
interface ModelAdapter {
    val id: String                                     // "llamacpp", "openai-compat", "anthropic"
    suspend fun load(spec: ModelSpec): LoadedModel      // no-op for cloud
    suspend fun unload(model: LoadedModel)
    fun capabilities(model: LoadedModel): Capabilities
}

interface LoadedModel {
    val spec: ModelSpec
    suspend fun chat(req: ChatRequest, onToken: (String) -> Unit = {}): ChatResponse   // tools in, ToolCalls out
    suspend fun embed(texts: List<String>): List<FloatArray>                          // if capabilities.embeddings
    suspend fun classify(prompt: String, labels: List<String>): Map<String, Float>     // logit scoring, 0 tokens
    fun cancel()
}

data class Capabilities(
    val contextLength: Int, val toolCalling: ToolCallStyle,   // NATIVE_JSON | TEMPLATE_MARKUP | NONE (→ prompted JSON)
    val vision: Boolean, val audioIn: Boolean, val embeddings: Boolean,
    val structuredOutput: Boolean, val classifierOnly: Boolean,  // e.g. Liquid d1
    val estRamBytes: Long,
)

data class ModelSpec(                     // user-installable entry, nothing hard-coded
    val id: String, val adapter: String, val source: ModelSource,  // HF repo+file, local path, or cloud base URL+model
    val template: String = "auto",        // from GGUF metadata (tokenizer.chat_template) → ChatML/Llama3/Gemma/LFM3/…
    val roles: Set<String> = setOf("main"),   // "main", "vision", "embed", "router", user-defined
)
```

**Capability detection**: GGUF metadata (`context_length`, chat template, mmproj present) + a one-time **probe**: send a tiny tool-call prompt and check the parser gets a valid call; record `toolCalling = NONE` if not, and the harness then falls back to constrained JSON (llama.cpp grammar) for tool selection. Cloud: `/models` + provider table.

**ModelManager** (one resident local model on 8 GB):
`install(spec) · remove(id) · ensureLoaded(id) · release() · status(): Flow<ModelStatus>` — with RAM check via `ActivityManager.MemoryInfo`, per-template prompt checkpoints, and metrics (load ms, tokens/s, peak RSS) stored per model.

---

## 5. Universal Agent Harness (execution loop)

```
Request ─▶ Understand ─▶ Retrieve context ─▶ Plan ─▶ ┌─ Select tools ─▶ Act ─▶ Observe ─┐ ─▶ Verify ─▶ Deliver
                                                     └──────── checkpoint each step ────┘
```

1. **Understand** — deterministic router (keywords) + optional classifier model → task type, needed capabilities.
2. **Retrieve** — top-k from RAG + conversation memory, injected within a token budget.
3. **Plan** — main model writes ≤7 steps (`todo` tool); skipped for one-shot questions.
4. **Act loop** — per step, expose only the tools that step needs (keeps prompts small on phones). Tool calls run through the **PermissionBroker** (risk: read / write-local / network / device-control / destructive / external-account).
5. **Errors** — typed `SyntraError` (ModelOOM, ToolTimeout, PermissionDenied, ParseError…) with policy: retry with backoff (idempotent tools), re-prompt with the error text (parse errors, max 2), or fail resumable.
6. **Checkpoint** — after every tool result: `TaskState` (messages, plan, step index, artifacts) written to Room. `resume()` rebuilds the prompt from it — the model's KV cache is never the source of truth.
7. **Verify** — each tool declares a `verify()` (file exists & non-empty, exit code 0, HTTP 2xx, screenshot shows target). The final answer is marked `verified=false` if any claimed artifact fails verification, and the UI says so.
8. **Deliver** — answer + `ArtifactRef`s (files in `/outputs`, openable/shareable via FileProvider).

Context management: rolling summary of older turns when > 60% of context; tool outputs truncated with a "full output saved to file" pointer.

---

## 6. Extension ecosystem

### Manifest — `syntra-extension.json` (in repo root, or supplied by a registry for repos that lack one)
```json
{
  "id": "io.github.pbakaus.impeccable",
  "name": "Impeccable",
  "version": "1.2.0",
  "type": "skill",                     // skill | agent | tool | package
  "syntra": ">=0.9",
  "source": { "repo": "pbakaus/impeccable", "ref": "v1.2.0", "sha256": "…" },
  "skills":  [{ "name": "impeccable", "file": "SKILL.md", "triggers": ["ui", "design review"] }],
  "agents":  [{ "name": "frontend-dev", "file": "agents/frontend.md", "tools": ["shell", "file_write"] }],
  "tools":   [{ "name": "scrape", "runtime": "linux-python", "entry": "tools/scrape.py",
                "schema": "tools/scrape.schema.json", "timeoutSec": 120 }],
  "dependencies": { "apt": ["python3"], "pip": ["scrapling==0.2.9"], "extensions": [] },
  "permissions": ["network", "linux.exec"],
  "requires": { "ramMB": 300, "capabilities": [] }
}
```

### Adapters for repos without a manifest
Heuristic importers produce a manifest for review: `SKILL.md` frontmatter (Claude skills, e.g. Ponytail, img2threejs) → skill; Agency-style `*.md` personas → agents; `pyproject.toml` / `package.json` with CLI entry → tool. **Never** guess install scripts.

### Extension Manager
`discover(registry|url) · inspect(url) → InstallPlan · install(plan, approved) · update(id) · remove(id) · enable/disable · checkCompat()`

Install flow: fetch at pinned commit → verify sha → show **InstallPlan** (files, deps, permissions, any scripts *with their contents*) → user approves → install deps inside Ubuntu sandbox → register tools in ToolRegistry → run the tool's self-test. Each extension gets its own directory and only the permissions it declared.

---

## 7. Specialist-model lifecycle

```
main running ─▶ need(vision|embed|…)? ─ no ─▶ continue with main + tools
                    │ yes, and capability missing in main
                    ▼
 1 checkpoint TaskState (Room)        5 validate result (schema / non-empty / verifier)
 2 release main (free ctx+weights)    6 unload specialist
 3 load specialist                    7 reload main (prompt checkpoint ⇒ fast)
 4 run subtask with minimal context   8 inject result as a tool result, resume loop
```
Rules: only if the main model lacks the capability *and* no tool can do it; at most one swap per step; one swap budget per task by default; batch all specialist work for a task together; on 12 GB+ devices both may stay resident. Embedding models (≈30–100 MB) are exempt and may stay loaded.

---

## 8. RAG and memory (three separate stores, one query API)

| Store | Content | Tech |
|---|---|---|
| **Knowledge (RAG)** | user documents, project files, web pages saved by tools | Room table `chunks(id, docId, text, meta JSON, embedding BLOB)` + FTS4 virtual table |
| **Conversation memory** | entities/relations (existing graph), chat summaries | existing Graph → Room tables |
| **Task state** | plans, checkpoints, artifacts | Room `tasks`, `task_steps` |

Pipeline: extract (PDF via PdfRenderer text / pdfbox-android, DOCX via zip-XML, HTML via Jsoup, code as-is) → chunk (≈400 tokens, 15% overlap, heading-aware) → embed with a small GGUF embedding model through the same llama.cpp adapter (e.g. a ~25–100 MB model; batch in background, charging-aware) → store.
Retrieval: **hybrid** = FTS4 BM25 top-30 ∪ cosine top-30 (brute-force over int8-quantized vectors — fine up to ~50k chunks on a phone) → reciprocal-rank fusion → metadata filter (project, type, date) → top-k packed into a token budget with source citations. Later: sqlite-vec if corpora grow.

---

## 9. UI redesign (AI-Native style, M3, clay #D97757)

Direction from UI/UX Pro Max: **"AI-Native UI"** — minimal chrome, streaming text, context cards, typing pulse; keep clay instead of the suggested purple. Android-UX audit of current UI: hard-coded colors and radii (fails M3 tokens), no headings semantics, 6 bottom tabs (> 5), no tablet layout.

- **Navigation (4 + drawer)**: Chat · Tasks · Workspace (files/terminal/memory) · Extensions; Models and Settings in a modal drawer. Navigation rail on medium+ widths, list-detail for Tasks.
- **Chat**: streaming bubbles; inline **run cards** (plan checklist that ticks live, tool calls collapsible, approval inline with risk color, artifact chips that open the file); model chip in the top bar showing loaded model + RAM; composer with attach (file/image/voice) and mode chips (Ask / Agent / Code).
- **Tasks**: live progress, resume/cancel, step timeline, artifacts; background notification with progress.
- **Models**: cards with capability badges (tools, vision, ctx, RAM), install from HF search or URL, benchmark button (tokens/s, load time).
- **Extensions**: store-like list, install plan sheet showing permissions & scripts before approve.
- **Workspace**: file browser for `/outputs`, terminal (Command/AI), knowledge sources with index status, memory graph.
- **Theme**: full M3 `ColorScheme` generated from clay seed (dark + light), surface-container tonal layers, `MaterialTheme.shapes`/typography only, Inter-like sans + mono for code, M3 motion tokens, reduced-motion respected, 48 dp targets, TalkBack headings.

---

## 10. Technical risks

| Risk | Mitigation |
|---|---|
| Small local models are unreliable at tool calling | capability probe, grammar-constrained JSON fallback, few tools per step, verifier |
| Prompt reading is the main latency on phone CPUs | per-template checkpoints, step-scoped tools, optional Vulkan, rolling summaries |
| 8 GB RAM: LMK kills during swaps | single resident model, checkpoint before swap, foreground service, measured RAM gate |
| Untrusted extension code | pinned sha, install plan review, per-extension permissions, execution only in proot sandbox; no Android-side code loading |
| proot compatibility varies by kernel | keep variant probe + Repair; Debian rootfs fallback |
| Play Protect blocks accessibility build | keep Full/Lite split |
| Embedding cost on battery | background, charging-aware indexing, small model, incremental |
| Rewrite risk | strangler approach: new modules behind interfaces, old features ported, not deleted, until parity |

---

## 11. Phased roadmap

| Phase | Deliverable | Exit test |
|---|---|---|
| **0. Foundations** (1 wk) | Gradle modules, Koin, Room, M3 theme from clay seed, new nav shell | app builds, old features reachable |
| **1. Models** (1–2 wk) | `ModelAdapter`, llama.cpp + OpenAI-compat + Anthropic adapters, GGUF template detection, capability probe, Models screen with any-HF-GGUF install & benchmark | load a non-LFM model (e.g. Qwen/Gemma GGUF) and chat |
| **2. Harness** (2 wk) | TaskEngine, ToolRegistry, PermissionBroker, checkpoints/resume, verifier, artifacts; port existing tools as providers | kill app mid-task → resume completes; artifacts verified |
| **3. New chat & tasks UI** (1–2 wk) | run cards, live plan, inline approvals, Tasks screen | M3 audit ≥ Pass on all 10 categories |
| **4. Extensions** (2 wk) | manifest, importers (SKILL.md, Agency agents, pyproject), install plan UI, sandboxed tool runtime | install Ponytail, Agency Agents, Scrapling; their tools run |
| **5. RAG** (2 wk) | extract/chunk/embed/hybrid search, Knowledge screen, context injection with citations | answer cites the right chunk from a 200-page PDF |
| **6. Specialists** (1 wk) | lifecycle in section 7, metrics | vision subtask on text-only main, no OOM on 8 GB |
| **7. Media/docs** (ongoing) | PDF/DOCX generation, ffmpeg in Linux, MCP tools as extensions | each tool has a verifier and a real output |
