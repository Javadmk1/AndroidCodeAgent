# Android Code Agent

A native Android coding assistant foundation built with Kotlin and Jetpack Compose.

## Implemented

- Android app shell (min SDK 26, JDK 17, Compose Material 3)
- OpenAI Chat Completions streaming (SSE)
- Anthropic Messages streaming (SSE)
- OpenAI-compatible endpoints for providers such as DeepSeek, Qwen, Kimi and compatible gateways
- Editable provider ID, model name and HTTPS base URL
- API keys encrypted with AES-256-GCM; encryption key is held in Android Keystore
- Model-driven tool loop for OpenAI-compatible Chat Completions and Anthropic Messages APIs, with bounded rounds
- Local workspace tools: list, read, search and approval-gated write
- User approval dialog before every model-requested file write, including an 800-character content preview
- Bounded local conversation history (last 80 messages)
- SAF folder import into the private workspace and ZIP export
- User-cancellable agent jobs (network cancellation is best-effort while a provider socket is blocked)
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

GitHub Actions runs unit tests and builds a debug APK on pushes to `main` and manual workflow dispatch. Open the Actions tab, select a successful run, and download the `android-code-agent-debug` artifact.

A signed release APK is built only when all four repository Actions secrets are configured:
- `ANDROID_KEYSTORE_BASE64`: Base64-encoded Java keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the original keystore and passwords backed up securely. Losing the signing key can prevent future updates from being accepted as updates to the same app. Never commit the keystore or passwords to the repository. Without these secrets, CI skips the signed release and still produces the debug APK.

## Security boundaries and current limitations

- API keys are never sent to the build pipeline.
- Local file tools are confined to the app-owned workspace directory.
- File writes require an approval callback from the controller.
- Arbitrary shell execution is deliberately not enabled.
- Model-driven tools are supported for OpenAI-compatible Chat Completions and Anthropic Messages. Some third-party compatible gateways may not implement tool calling correctly.
- Imported projects are copied into an app-private workspace. Import/export is bounded to 100 MB and 5,000 files; hidden folders, .git, .gradle and build outputs are skipped.
- Building an imported third-party Android project into an APK directly on the phone is not implemented. Import/export is available; APK compilation still requires a configured build environment.
- Release signing is supported by CI when the owner configures the four signing secrets described above; no signing key is bundled in the app.
- Conversation history uses bounded app-private SharedPreferences JSON, not Room.

Do not commit API keys, signing keys, or local machine configuration.
