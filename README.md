# Android Code Agent

A native Android coding assistant foundation built with Kotlin and Jetpack Compose.

## Implemented

- Android app shell (min SDK 26, JDK 17, Compose Material 3)
- OpenAI Chat Completions streaming (SSE)
- Anthropic Messages streaming (SSE)
- OpenAI-compatible endpoints for providers such as DeepSeek, Qwen, Kimi and compatible gateways
- Editable provider ID, model name and HTTPS base URL
- API keys encrypted with AES-256-GCM; encryption key is held in Android Keystore
- Model-driven OpenAI-compatible tool loop with a bounded number of rounds
- Local workspace tools: list, read, search and approval-gated write
- User approval dialog before every model-requested file write
- Bounded local conversation history (last 80 messages)
- Workspace path traversal protection and 1 MB per-file limit
- GitHub Actions debug APK build

## Configure an API

Open Settings and enter:
- Provider ID: `openai`, `anthropic`, or `compatible` (any non-anthropic provider uses the OpenAI-compatible protocol)
- Model: the exact model identifier supported by your provider
- Base URL: HTTPS API root, normally ending in `/v1`
- API key: stored encrypted on the device

Examples:
- OpenAI: `https://api.openai.com/v1`
- Anthropic: `https://api.anthropic.com/v1`
- DeepSeek: `https://api.deepseek.com/v1`

The app does not include API credits. Provider charges and rate limits still apply.

## Build APK

GitHub Actions builds a debug APK on pushes to `main` and manual workflow dispatch. Open the Actions tab, select a successful run, and download the `android-code-agent-debug` artifact.

## Security boundaries and current limitations

- API keys are never sent to the build pipeline.
- Local file tools are confined to the app-owned workspace directory.
- File writes require an approval callback from the controller.
- Arbitrary shell execution is deliberately not enabled.
- Model-driven tool calling currently uses OpenAI-compatible Chat Completions providers; Anthropic is currently chat-only.
- Workspace is app-private. User-selected external project folders and SAF import/export are not implemented yet.
- On-device Gradle/Android SDK build worker, APK signing/release flow, and automated repair loop are not implemented yet.
- Conversation history uses bounded app-private SharedPreferences JSON, not Room.

Do not commit API keys, signing keys, or local machine configuration.
