# Version 1.0.0 release checklist

This checklist is the release contract. A green APK build alone is not sufficient.

## Core app
- [ ] First launch, migration from 0.5.x, and restart persistence.
- [ ] Create, rename, switch and delete isolated projects.
- [ ] Import/export projects; reject unsafe ZIP entries and preserve the previous project on failed import.
- [ ] Create, open, edit, save, close and reopen multiple editor tabs without losing unsaved changes silently.
- [ ] Provider setup, key replacement, key deletion and app restart.
- [ ] OpenAI-compatible and Anthropic streaming/tool-call flows, including cancellation and provider errors.
- [ ] Explicit approval before every source-changing tool action.
- [ ] Chat history limits and recovery from malformed/old persisted data.

## Build and diagnostics
- [ ] GitHub Actions Android workflow passes unit tests, lint, APK packaging and apksigner verification.
- [ ] Imported-project workflow rejects non-allowlisted Gradle tasks and invalid paths.
- [ ] Successful assembleDebug uploads APKs and SHA-256 manifest.
- [ ] Failed builds upload diagnostic logs and never claim an APK exists.
- [ ] Imported projects receive no AI API keys, signing keys or write-capable repository token.
- [ ] App can import a build log and use it to explain a failure without silently changing files.

## Security
- [ ] Review all file path canonicalization and symlink handling.
- [ ] Test ZIP traversal, absolute paths, duplicate entries, oversized archives and excessive file counts.
- [ ] Confirm exports/build archives exclude API keys, keystores, passwords, local.properties and common credential files.
- [ ] Confirm model tool arguments cannot escape the active project root.
- [ ] Confirm all source-changing actions remain approval-gated.
- [ ] Review Android backup/data-extraction policy for secrets and private project data.
- [ ] Review network security configuration and cleartext traffic policy.

## Device validation
- [ ] Install and smoke-test on Android 8+ (API 26+) where practical.
- [ ] Test a low-memory/low-performance Android device.
- [ ] Test rotation, process death, app restart, background/resume and network loss.
- [ ] Test large project import/export and long AI responses.
- [ ] Check crashes and ANRs from a real device run.

## Release operations
- [ ] Owner configures and backs up the permanent release keystore.
- [ ] Signed release APK is produced and verified.
- [ ] Version code/name are incremented only for the actual release candidate.
- [ ] Privacy policy and data-use disclosures are published and linked in-app/store listing.
- [ ] README, setup, backup, troubleshooting and known limitations are current.
- [ ] Tag the verified commit; attach APK and checksum to the release.

## Current known limitations at start of this work
- The imported-project workflow supports public GitHub repositories only; direct phone ZIP upload and private repositories are not implemented.
- The app's Cloud Builder implementation is a legacy optional path and is not a hosted service.
- No physical Android device test is implied by a successful GitHub Actions run.
- Release signing requires the repository owner's permanent signing key; it must never be generated or stored by this coding session.
