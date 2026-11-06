# Android Code Agent

A native Android coding assistant built with Kotlin and Jetpack Compose. It connects to an AI provider, works with app-private projects, and can request approved file operations.

## Current capabilities

- OpenAI-compatible and Anthropic Messages API support, including streaming and tool calls.
- Provider profiles with HTTPS endpoint/model settings.
- API keys encrypted with AES-256-GCM; the encryption key is protected by Android Keystore.
- Multi-project workspaces with create, switch, rename and delete.
- Project import/export through Android's Storage Access Framework.
- File browser and multi-tab text editor with explicit save approval.
- Agent tools for listing, reading, searching, generating bounded file diffs, approval-gated writing and undo, plus AI-created plan steps and explicitly requested queued tasks.
- Persistent per-project task center with a request queue and manually managed implementation checklist; queued prompts survive app restarts and can be loaded into chat for review before sending.
- Three parallel read-only review agents (security, bug/logic, architecture/testing), each operating on its own bounded snapshot so reviewers cannot modify the live project.
- GitHub integration for public/private repository metadata, branch listing and open Pull Request listing; creating a Pull Request requires a stored GitHub token and a separate confirmation.
- Bounded local chat history and cancellable agent requests.
- GitHub Actions workflows for building this app and building public GitHub Android projects.

The app does not include AI API credits. Provider usage limits and charges may apply.

## Configure an AI provider

In Settings, configure:
- Provider ID: `openai`, `anthropic`, or `compatible`.
- Model: the exact model identifier supported by the provider.
- Base URL: HTTPS API root (commonly ending in `/v1`).
- API key: stored encrypted on the device.

Examples include OpenAI, Anthropic, DeepSeek and other OpenAI-compatible gateways. Compatibility depends on whether the gateway implements the required streaming and tool-call protocol.

## Build this Android Code Agent app

The `.github/workflows/android-build.yml` workflow runs unit tests, builds a debug APK, verifies its APK signature and uploads the artifact.

1. Open the repository's **Actions** tab.
2. Select **Android debug build**.
3. Select **Run workflow** (or use a successful push run).
4. Download the `android-code-agent-debug` artifact.

A permanently signed release APK is built only when all four repository Actions secrets are configured:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

See [release signing from Android](docs/RELEASE_SIGNING.md). Keep the keystore and passwords backed up securely. Never commit them. A debug APK is not a production release.

## Build another Android project

The `.github/workflows/build-imported-project.yml` workflow uses GitHub-hosted runners. It does not require a separately purchased Linux VM or a self-hosted Docker builder.

1. Push the target project to a **public GitHub repository**.
2. Open **Actions → Build imported Android project → Run workflow**.
3. Enter the source repository as `owner/name`, the branch/tag/commit, and the project directory.
4. Select one of the allowlisted tasks: `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `check`, or `tasks`.
5. For `assembleDebug`, download `imported-project-apks`. The Gradle log is uploaded separately, including when a build fails.

Important limits:
- This workflow currently builds public GitHub repositories only.
- A project that exists only in local phone storage cannot be sent directly to this workflow yet.
- Private repositories are not supported by this workflow.
- Gradle build scripts from the selected source project execute on an ephemeral GitHub-hosted runner. The workflow has read-only repository permission and does not pass AI API keys or signing secrets to the imported project.
- Do not put credentials, signing keys, or other secrets in a project being built.

## Current workflow limits

- The task queue and project checklist are stored locally per project. Queued prompts survive app restarts, but they are deliberately loaded into the chat for a user-reviewed send; they are not automatically executed in the background.
- Parallel reviewers run three independent, bounded, read-only snapshots. They can inspect and report findings, but they do not edit files or run builds.
- GitHub support currently covers repository metadata, up to 100 branches, up to 50 open pull requests, and confirmed Pull Request creation from an existing remote branch. It does not yet provide local Git clone/commit/push, branch creation, conflict resolution, or automatic merge.
- Long-running model calls are not yet hosted in an Android foreground service. Closing the app can stop an active request; use the persistent queue to retain work that has not yet been sent.

## Security and product boundaries

- API keys are not included in project exports or build uploads.
- File tools are confined to the app-owned project directory.
- Model-requested file writes require explicit user approval.
- Arbitrary shell execution is intentionally not exposed.
- The in-app editor is a lightweight text editor, not a full Android Studio replacement.
- The current imported-project workflow does not provide private-repository checkout or direct local ZIP upload.
- Cloud Builder code remains in the repository as a legacy optional component; the recommended build path is GitHub Actions and it does not require deploying Cloud Builder.

## Version 1.0 release gate

Version 1.0 is not declared complete by changing the version string. Release requires:
- Fresh CI for unit tests, lint/static checks and debug/release packaging.
- Verified APK artifact and checksum.
- A stable signed release key configured by the repository owner.
- Security tests for project import/export, file path boundaries and secret handling.
- Verified project lifecycle, chat, provider setup, approval flow and build-log workflow.
- Installation and smoke testing on real Android devices, including a lower-memory device.
- Updated setup, backup, privacy and troubleshooting documentation.

See [the 1.0 release checklist](docs/V1_RELEASE_CHECKLIST.md).
