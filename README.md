# Android Code Agent

Native Android coding assistant built with Kotlin and Jetpack Compose.

## Stage 1 (current)
- Android application foundation (min SDK 26)
- Compose chat shell and provider settings
- Encrypted API-key storage using Android Keystore + AES-GCM
- GitHub Actions debug APK build workflow

## Not implemented yet
Streaming model clients, agent/tool execution, project workspace, shell approvals, remote build workers, APK signing and install automation are later stages.

## Build
Open the `android` directory in Android Studio, or run:

```bash
gradle -p android assembleDebug
```

The GitHub Actions workflow builds the debug APK and uploads it as an artifact.

## Security
API keys are encrypted before being persisted. The encryption key is generated in Android Keystore and is not exported. Never commit API keys, signing keys, or local.properties.
