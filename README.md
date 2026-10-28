# Syntra OS

A JARVIS-style agentic environment for Android: on-device LFM3 models, an always-listening voice assistant, phone control, self-written graph memory, and delegation to OpenClaw / Hermes agents.

## What's in v0.2
- **On-device LFM3 team (llama.cpp, no server):** Text (LFM3-1.7B), Vision (LFM3-VL-2B), Audio, Omni (LFM3-Omni-2B), Decision (d1-Omni-2B). Download each from Setup; they then run fully offline. Each model has its own engine, so models answer **in parallel** — the lead model can `consult_team` several teammates at once, and all share the graph memory and a task blackboard.
- **Always-on voice:** says "Syntra" or "Jarvis" — no tap, no popup. Replies are spoken with the best offline voice; after a reply it listens for a follow-up.
- **Phone control:** accessibility service reads the screen and taps, types, swipes, presses Back/Home, opens apps (the approach used by open-source Android agents like DroidRun and Mobile-Agent). Asks before acting unless you turn that off.
- **Termux shell** via Termux's `RUN_COMMAND` API (always asks first).
- **OpenClaw & Hermes:** both work as delegate agents (`delegate` tool) or as providers, through their OpenAI-compatible gateways (OpenClaw: enable `gateway.http.endpoints.chatCompletions`).
- **Self-written graph memory:** after every reply the lead model extracts people, projects, stories and relations into the Graphify-style graph. You can explore, search, edit and delete — but you never have to add things.
- **Images:** attach a photo; the Vision teammate sees it.
- Web fetch, GitHub REST, MCP (Streamable HTTP), workspace files, installable Agency-Agents skills, task log with cancel and approvals.

## Status / limits
- LFM3 text and vision run on llama.cpp master (pinned commit). **LFM3-Omni audio in/out needs an unmerged llama.cpp PR**, so voice uses Android speech recognition + TTS for now. The Audio and d1 repos download only once Liquid publishes their GGUFs.
- Voice listens while the app is open (no background service yet).
- Native code targets arm64 with dotprod/i8mm (2021+ phones).
- Not yet: scheduled jobs, notifications reading, encrypted key storage.

## Build
CI builds a debug APK on every push (Actions → Android CI → `syntra-os-debug-apk`). Locally: `./gradlew assembleDebug` (needs NDK + CMake 3.22.1).
