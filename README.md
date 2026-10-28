# Syntra OS

A JARVIS-style agentic environment for Android (Kotlin + Jetpack Compose).

## What works in v0.1
- **Model team (LFM3 family):** five role slots — Text (LFM3-1.7B), Vision (LFM3-VL-2B), Audio (LFM3-Audio-2B), Omni-Generative (LFM3-Omni-2B), Decision (d1-Omni-2B). You pick the lead model by hand; it calls the others through the `ask_specialist` tool, and every model sees the same graph memory and task transcript. No automatic router.
- **Providers:** any OpenAI-compatible endpoint with a key — Hermes Agent's API server, OpenRouter, Liquid's API, or `llama-server` / Ollama running in Termux (`http://127.0.0.1:8080/v1`).
- **Agent loop:** multi-step tool calling, live task log, cancel, and a human-approval gate for writes (GitHub non-GET, MCP tool calls).
- **Graph memory (Graphify-style):** persistent entities and relations, community detection, force-directed interactive graph — pan, pinch-zoom, tap to inspect, search, filter by community, edit/delete nodes, add/remove links. Agents read and write it via `memory_search`, `memory_remember`, `memory_link`.
- **Tools:** web fetch + text/link extraction (Jsoup), GitHub REST, MCP client (Streamable HTTP: `tools/list`, `tools/call`).
- **Skills:** built-in specialist roles + install Agency-Agents-style markdown roles from GitHub. Installed skills are off until you review and enable them.
- **Voice:** speech input (system recognizer) and spoken replies (TTS).

## Needs your setup
- An LLM endpoint: either a cloud key, or a local server in Termux.
- GitHub token (Setup → GitHub) for private repos / writes.
- MCP server URLs.

## Not done yet (next phases)
- On-device LFM inference via Liquid's LEAP Android SDK (currently LFM runs through an OpenAI-compatible server).
- Hermes Agent embedded natively (it is Python; today it connects as a provider).
- Scrapling (Python) as a sidecar service; Accessibility-service app control; Termux `RUN_COMMAND` shell bridge; scheduled jobs; image/audio input to vision/audio slots; encrypted key storage.

## Build
CI builds a debug APK on every push (Actions → Android CI → artifact `syntra-os-debug-apk`). Locally: `./gradlew assembleDebug`.
