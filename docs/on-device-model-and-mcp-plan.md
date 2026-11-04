# On-device coach model, and MCP for your own agent

The owner's request (2026-11-04): a model that runs **on the phone** as a coach option, beside the
cloud providers and the self-hosted server; and **MCP**, so people can use PulseLoop's data with
their own agent. GMind and Cochilo already have both pieces, so this plan reuses what they proved
rather than inventing a third way.

## What already exists here

- **Cloud providers:** OpenAI, Gemini, OpenRouter and MiniMax, each with the user's own key.
- **`LOCAL_OPENAI_COMPAT`:** any OpenAI-compatible server the user runs (Ollama, LM Studio,
  llama.cpp, vLLM, SGLang), on the same Wi-Fi or over Tailscale (`docs/local-llm-coach.md`). It
  runs on *another* computer, not on the phone.
- **The coach is tool-driven:** `CoachOrchestrator` gives the model `ToolRegistry`'s tools
  (`CoachToolDef`: a name, a JSON schema, a handler) and loops up to 15 rounds. Write tools sit
  behind `CoachFeatureFlags.writeToolsEnabled`, and changes the user must confirm go through
  `PendingAction`.

## Part 1: a model on the phone

### How the sibling apps do it

| | Cochilo | GMind |
|---|---|---|
| Runtime | **LiteRT-LM** 0.16.1 for `.litertlm`, plus **MediaPipe LLM Inference** (`tasks-genai` 0.10.35) for `.task`, behind one `LocalModel` interface; the file's magic bytes pick which | **llama.cpp**, built from a pinned commit into its own `libgmind-llama.so` (JNI, CPU, streaming); **Gemini Nano** through ML Kit where Android offers it |
| Model | Gemma 3 1B int4 (about 600 MB); Gemma 3n E2B (about 3.5 GB) on 8 GB phones | Qwen2.5 1.5B Q4_K_M (1.1 GB) |
| Delivery | Downloaded on request from a **pre-release** of its own repo (`assistant-model-v1`), checked against the published SHA-256, Wi-Fi only unless the person chooses otherwise; a Play asset pack in the Play build | Downloaded on request, size and SHA-256 checked, kept in no-backup storage |
| How it answers | Never the source of truth: the app computes the facts, the model only phrases them (`Grounding.kt`), and safety questions get fixed replies before the model sees them | Retrieval first (`LocalPrompt`): the app finds the sources, the model writes from them. A JSON grammar for tools, because small models handle tool use poorly |
| Build cost | Two Gradle dependencies; Kotlin moved to 2.2.21 for LiteRT-LM | An NDK build pipeline, a pinned llama.cpp, ELF audits, host JNI tests |

### Options for PulseLoop

1. **LiteRT-LM / MediaPipe with Gemma (Cochilo's way). Recommended.** It costs two Gradle
   dependencies and no NDK work. Google maintains both runtimes, and Cochilo already ships them.
   The model is already published and hash-checked on Cochilo's `assistant-model-v1` pre-release,
   so PulseLoop can start from those files and move to its own pre-release later with the same
   workflow.
2. **llama.cpp (GMind's way).** It runs any GGUF model (Qwen, Llama, Phi), but needs GMind's
   whole native build pipeline. That is worth it only if a model outside Gemma's family proves
   clearly better at health coaching.
3. **Gemini Nano (ML Kit GenAI).** It needs no download, but only some phones have it, it is
   closed source, and it isn't on the Pixel 9a. It could be a bonus later, never the only path.

**Recommendation: option 1.** Gemma 3 1B goes on every phone, and Gemma 3n E2B on phones with
8 GB of memory, behind Cochilo's `LocalModel` interface, copied with its tests.

### The hard part: tools

A 1B model can't run the coach's 15-round tool loop reliably, and a 3n can only sometimes. So the
on-device coach answers like Cochilo, **grounded, without tools**:

1. **The app gathers the facts first.** `CoachContextBuilder` already builds the per-turn packet:
   today, last night, the vitals, the goals and the warnings. For the question asked, the app also
   runs the read-only tools that obviously apply (sleep, heart, activity, nutrition), picked by
   keyword, and adds their results as compact text, as GMind's `LocalPrompt` does.
2. **The model only phrases the answer**, in at most a few short paragraphs, in the app's
   language. The rules are those of the cloud coach's system prompt, shortened: it never
   diagnoses, never doses, and sends people to a clinician.
3. **Safety comes before the model.** Crisis, medication, pregnancy, postpartum and lab-diagnosis
   questions get the reviewed replies the app already has, and the model never sees them.
4. **No write tools and no confirm cards** on this provider. Goals, meals and workouts are still
   changed from the app, or with a cloud or self-hosted coach.

The coach screen says which brain answered ("On this phone · Gemma 3 1B"), and that nothing left
the phone.

### What the person sees

- **Settings › AI Coach › "On this phone"**, a new provider beside the others.
- **Download** shows the model's size before anything moves (Wi-Fi only unless they tap "Download
  now on mobile data"), with progress and Cancel, using Android's download manager. Remove frees
  the space. The Gemma licence is linked in the sheet.
- On a phone below the memory floor (about 4 GB), the option says why it isn't offered.

### Build steps

1. Add `tasks-genai` and the `LocalModel` interface, with `MediaPipeModel`.
2. Add `ModelDownload` and `ModelFile`, copied with their tests.
3. Add `CoachProviderMode.ON_DEVICE` and an `OnDeviceCoach` that does the grounded prompt and
   the safety routing.
4. Add the Settings row and the download sheet, with all four design skills and an audit.
5. **Later:** bump Kotlin (2.0.21 → 2.2.x), KSP and Room (2.6.1 → 2.7.x) for LiteRT-LM and Gemma
   3n, as Cochilo did in 0.55.0. This is a separate PR, because it touches the whole build.

### As built (step 1, 2026-11-04)

MediaPipe only; LiteRT-LM and Gemma 3n wait for the Kotlin bump (step 3).

- **Runtime:** `com.google.mediapipe:tasks-genai:0.10.35`, CPU backend, one engine kept loaded
  (`coach/ondevice/LocalModel.kt`, `MediaPipeModel`, `MAX_TOKENS` 1280 with 384 kept for the
  answer, as Cochilo runs the same file). R8 keeps `com.google.mediapipe.**` and protobuf
  (`proguard-rules.pro`).
- **The file:** `gemma3-1b-it-int4.task` from Cochilo's `assistant-model-v1` pre-release
  (`ModelDownload.REPO` / `TAG`, so moving it to PulseLoop's own pre-release is two constants),
  checked against the `.sha256` beside it, at least 50 MB and not text (`ModelFile`). It lands
  in the app's external files folder (the only place Android's download manager can write
  without a permission) and moves into `noBackupFilesDir/models/` once checked
  (`OnDeviceModelStore`). Wi-Fi only unless the user taps "Download now on mobile data". A file
  the user already has can be chosen instead (Storage Access Framework → `ModelFile.copy`).
- **The provider:** `CoachProviderMode.ON_DEVICE` ("onDevice"). `CoachClientResolver` returns
  `OnDeviceResponsesClient` with the sentinel `"on-device"` only when the file is on the phone;
  the model slug is `gemma3-1b-on-device`, priced at $0.
- **How a turn runs:** the orchestrator is unchanged. `OnDeviceResponsesClient` ignores the
  tools, replaces the cloud system prompt with `OnDevicePrompt.instructions` (its safety rules,
  shortened, plus `L10n.coachInstruction()`), keeps the developer message's context packet, and
  cuts the prompt to the window (earlier messages first, then the context; instructions and
  question always stay). It answers in Gemma's turn format and wraps the plain-text answer in a
  complete `coach_response` object, so the tool loop ends after one call and `parseFinal` accepts
  it without a repair round. Any other schema (meal estimate, lab report, summary) is refused;
  the meal and lab screens say so plainly.
- **Safety first:** `OnDeviceSafety` routes crisis (CVV 188, SAMU 192), emergency (SAMU 192) and
  medicine/dose/supplement questions to fixed replies before the model is loaded, in English and
  Portuguese, accent-insensitive.
- **Off on this provider:** web search, write tools, live measurements, image attachments.
- **Not built yet:** the keyword-picked read-only tool results (step 1 of "The hard part"), and
  routing pregnancy, postpartum and lab-diagnosis questions to their reviewed replies.

## Part 2: MCP, so people can use their data with their own agent

### How GMind does it

GMind has two surfaces:

- **On your network:** an opt-in, **read-only** MCP server on the phone (`McpServer`,
  `McpService`, `McpTokens`). It speaks MCP's streamable HTTP (`POST /mcp`, JSON-RPC 2.0,
  protocol `2025-06-18`) on port 8765, so Claude Desktop, Cursor and others on the same Wi-Fi or
  Tailscale can call GMind's lookup tools. Every request needs that tool's own pairing token.
  Requests from non-private addresses, or with an `Origin` header (a web page), are refused before
  anything is read. Connections, timeouts and body sizes are bounded. A foreground service keeps
  it alive while it is on. It has its own threat model (`docs/MCP-THREAT-MODEL.md`).
- **Anywhere:** an encrypted mirror on the person's own free Cloudflare Worker (`mcp-mirror/`).
  It stores only ciphertext, has a 30-day lease, and serves Claude on the web and phone.

### For PulseLoop

**Phase A (recommended first): the on-network server, GMind's way.**

- **Tools:** the coach's own read-only tools from `ToolRegistry`, plus one `summary` tool (today,
  last night and the week in one reply), so an agent gets a useful picture in one call. They are
  listed from the same `CoachToolDef` schemas, so nothing has a second definition.
  - **Never offered:** write tools, live ring measurements, the coach's API keys, raw ring
    packets, or lab-report PDFs.
- **Pairing:** Settings › Connected apps › **"AI tools on your network"**, off by default. Each
  agent gets its own token (shown once, revocable), and the connection's address and a ready-made
  config are copied for Claude Desktop or Cursor.
- **Limits:** only private and Tailscale addresses; no `Origin` header; bounded body size,
  connections and time. A foreground notification shows while it runs, and it turns off by
  itself after a set time (default 8 hours).
- **Privacy:** the readings are health data, so each pairing says what the agent will be able to
  read, and the coach's rules travel in the server's `instructions` (it is not a diagnosis tool).
  The server ignores the demo-data switch: an agent gets what the screens show.

**As built (Phase A, step 2).** Package `app/src/main/java/com/pulseloop/mcp/`, screen
`ui/screens/McpSettingsUi.kt` (route `settings/mcp`, one row under Connected apps), threat model
`docs/mcp-threat-model.md`, rules in AGENTS.md "MCP on your network".

- **Tools:** an explicit allowlist of 22 coach read tools (`McpCoachTools.ALLOWLIST`) plus
  `summary`. The registry's always-on set turned out to hold writes too (`log_meal`, `set_goal`,
  `log_drink`, `set_drink_plan`, `save_food_preference`) and `search_food_database` writes a cache
  and goes to the network, so the flags alone were not enough. `prepare_chart` is left out: it
  carries no data. `summary` reuses `CoachContextBuilder.build` without its labs, postpartum,
  drinks and goal-plan blocks.
- **Pure JVM core:** `McpHttp` (parse/write, limits), `McpNet` (address rules, URLs), `McpTokens`
  (32-byte base64url tokens, `pulseloop_` prefix, SHA-256 only, constant-time check),
  `McpHandler` (JSON-RPC: `initialize`, `ping`, `tools/list`, `tools/call`, 202 for
  notifications, one tool call at a time), `McpServer` (sockets). Tested in
  `app/src/test/java/com/pulseloop/mcp/`, including a real loopback round trip.
- **Android:** `McpService`, a `dataSync` foreground service with its own channel and a
  tap-to-stop notification; auto-off after 1, 4 or 8 hours (default 8). On Android 15 the system
  caps `dataSync` at about 6 hours a day, so the 8-hour choice ends there (`onTimeout`).
  `McpPrefs` keeps the switch and the pairings in `mcp_server`, which is not archived.
- **Connecting:** the pairing dialog shows the token once with a Claude Desktop / Cursor entry
  (`npx -y mcp-remote <url> --allow-http --header Authorization:${PULSELOOP_AUTH}`, the bearer in
  `env`) and the plain URL and header for clients that speak HTTP.
- **Not done here:** the design canvas board, the flow-map card and the four-skill audit for the
  new screen; the pt-BR catalog entries (listed in the PR for the catalog owner).

**Phase B (owner's call): an encrypted mirror, so Claude anywhere can read it.** This is GMind's
Worker, with the same push, lease and delete behaviour. It would put a copy of health data, even
encrypted, on a server outside the phone, so it is the owner's decision. It would also be opt-in,
with summaries and no raw rows.

## Order and size

| Step | What | Size |
|---|---|---|
| 1 | On-device coach: MediaPipe + Gemma 3 1B, grounded, download sheet | Medium |
| 2 | MCP on your network: server, tokens, Settings row, threat model doc | Medium |
| 3 | Kotlin/KSP/Room bump → LiteRT-LM + Gemma 3n on 8 GB phones | Medium (build-wide) |
| 4 | Encrypted MCP mirror for Claude anywhere | Owner's call |

None of this can be checked here on a phone. CI compiles and runs the unit tests, but speed,
memory and answer quality on the owner's phone are the first thing to try after each release.
