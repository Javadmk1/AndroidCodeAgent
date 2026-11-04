# Android Code Agent

A native Android coding assistant foundation built with Kotlin and Jetpack Compose.

## Implemented

- Android app shell (min SDK 26, JDK 17, Compose Material 3)
- OpenAI-compatible and Anthropic Messages API support
- Streaming agent responses for OpenAI-compatible Chat Completions and Anthropic Messages, including streamed tool-call assembly
- OpenAI-compatible endpoints for providers such as DeepSeek, Qwen, Kimi and compatible gateways
- Editable provider ID, model name and HTTPS base URL
- API keys encrypted with AES-256-GCM; encryption key is held in Android Keystore
- Model-driven tool loop for OpenAI-compatible Chat Completions and Anthropic Messages APIs, with bounded rounds
- Local workspace tools: list, read, search and approval-gated write
- In-app project browser, text-file editor, create-file action and explicit save confirmation
- User approval dialog before every model-requested file write, including an 800-character content preview
- Bounded local conversation history (last 80 messages)
- SAF folder import into a staging directory, then safe replacement of the previous workspace only after a successful import; ZIP export
- User-cancellable agent jobs (network cancellation is best-effort while a provider socket is blocked)
- Workspace path traversal and symlink escape protection; 1 MB per-file limit
- HTTPS endpoint, provider ID and model-name validation
- GitHub Actions debug APK build and optional permanently signed release APK

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

A signed release APK is built only when all four repository Actions secrets are configured. See [release signing from Android](docs/RELEASE_SIGNING.md) for the phone-based keystore setup:
- `ANDROID_KEYSTORE_BASE64`: Base64-encoded Java keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the original keystore and passwords backed up securely. Losing the signing key can prevent future updates from being accepted as updates to the same app. Never commit the keystore or passwords to the repository. Without these secrets, CI skips the signed release and still produces the debug APK.

## Build imported projects with a remote worker\n\nThe workflow `.github/workflows/build-imported-project.yml` provides a separate, isolated GitHub Actions build worker for **public GitHub repositories**. Open Actions → **Build imported Android project** → Run workflow, then enter the source repository (`owner/name`), branch/tag/commit, project folder, and Gradle task (normally `assembleDebug`). Download the `imported-project-apks` artifact. A `imported-project-build-log` artifact is also uploaded, including when the build fails, so the log can be imported into the app workspace and analyzed by the coding agent. The worker does not receive API keys or write credentials and disables persisted checkout credentials.\n\nThis worker builds a project already pushed to a public GitHub repository. It does not yet upload a project imported only from local phone storage, and it does not build private repositories.\n\n## Security boundaries and current limitations

- API keys are never sent to the build pipeline.
- Local file tools are confined to the app-owned workspace directory.
- File writes require an approval callback from the controller.
- Arbitrary shell execution is deliberately not enabled.
- Model-driven tools and streamed responses are supported for OpenAI-compatible Chat Completions and Anthropic Messages. Some third-party compatible gateways may not implement tool calling or streaming correctly.\n- Build-log analysis and code repair can be performed by the agent after the log is imported, with per-file write approval. A fully unattended build → AI patch → rebuild loop is not enabled: it would require explicit source-upload permissions and a trusted repair/build boundary.
- Imported projects replace the current app-private workspace only after a complete staged copy succeeds. Import/export is bounded to 100 MB and 5,000 files; hidden entries, .git, .gradle and build outputs are skipped.
- The in-app editor handles text files up to 1 MB; it is not a full IDE and does not provide syntax-aware editing, Git operations or a terminal.
- Building arbitrary local imports directly on the phone is not implemented. The remote worker builds public GitHub repositories only; a local-only import must first be pushed to a public repository. Private repository and direct ZIP-upload build support remain outstanding.
- Release signing is implemented in CI but cannot be activated until the repository owner creates and configures a permanent keystore using the instructions above. No private signing key is bundled or generated automatically.
- Conversation history uses bounded app-private SharedPreferences JSON, not Room.

Do not commit API keys, signing keys, or local machine configuration.
