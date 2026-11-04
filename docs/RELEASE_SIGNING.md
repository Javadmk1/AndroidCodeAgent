# Release signing from an Android phone

A production signing key belongs to the app owner. CI must not generate a new key on every run: that would prevent future APK updates from being accepted.

## 1. Create one permanent keystore

On Android, install Termux from its official F-Droid or GitHub distribution, then run:

```sh
pkg update
pkg install openjdk-17 coreutils
mkdir -p ~/android-code-agent-signing
cd ~/android-code-agent-signing
keytool -genkeypair -v -keystore android-code-agent-release.jks -alias android-code-agent -keyalg RSA -keysize 3072 -validity 10000
```

Choose strong unique passwords when prompted. Keep the keystore and passwords private and maintain a secure backup. Do not put them in the app, chat, source code, or a public repository.

## 2. Encode the keystore

```sh
base64 -w 0 android-code-agent-release.jks > android-code-agent-release.base64
```

Open the generated `.base64` file locally and copy its entire content. Never share it in chat or screenshots.

## 3. Add GitHub Actions secrets

In the AndroidCodeAgent repository open **Settings → Secrets and variables → Actions → New repository secret** and add:

- `ANDROID_KEYSTORE_BASE64`: full Base64 content from the generated file
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: `android-code-agent`
- `ANDROID_KEY_PASSWORD`: key password

These must be Actions secrets, not ordinary repository variables.

## 4. Build

Push a commit to `main` or run **Actions → Android debug build → Run workflow**. When all four secrets exist, the workflow also creates the signed release APK artifact named `android-code-agent-release`.

## Important

- Never regenerate the keystore for an update to the same app ID.
- Back up the `.jks` file securely. GitHub Secrets are not a backup.
- If the keystore is lost, a future APK signed with a different key cannot update the installed app normally.
- This workflow signs the APK; Play Store publication and Play App Signing enrollment are separate steps.