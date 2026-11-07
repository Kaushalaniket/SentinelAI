# SentinelAI — Android Build Brief (derived from the approved build brief)

## Project layout (SentinelAI/android/)

| Dir | Contents |
|---|---|
| `app/src/main/java/ai/sentinel/mobile/` | 31 Kotlin files: UI screens, nav, services, db, detectors, policy, packets, flows, settings, formatters, self-test, report exporter, retention worker |
| `app/src/main/res/` | Manifest (`AndroidManifest.xml`), layouts (Compose), drawables, raw assets |
| `app/src/test/java/ai/sentinel/mobile/` | `SentinelCoreTest.kt` — 15 JVM tests (parser, flow, feature, policy, detector) |
| `app/src/androidTest/` | (none at present — no instrumented test apk was included in the original plan) |
| `app/src/main/assets/` | `model.onnx`, `model_contract.json`, `sample_flows.csv`, `sample_expected.json`, `blocklist.txt` |
| `app/src/main/AndroidManifest.xml` | `<uses-permission>` VPN (special-use), network, notifications, boot; FileProvider; VpnService |
| `app/src/main/res/xml/file_paths.xml` | FileProvider authority `${applicationId}.files` |
| `app/sentinelai-release.jks` | Self-signed release keystore (sideload only) |

## Build configuration

| Item | Value |
|---|---|
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 26 (Android 8.0) |
| Application ID | `ai.sentinel.mobile` |
| Build tools | AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28 |
| Compose BOM | 2024.10.01 |
| Room | 2.6.1 |
| WorkManager | 2.9.1 |
| ONNX Runtime Android | `com.microsoft.onnxruntime:onnxruntime-android:1.19.2` (arm64-v8a) |
| Gradle | 8.9 (wrapper) |
| Java | 17 (Temurin 17.0.12+7) |
| Packaging | `bundleInBundle` + `bundleInApk` off; splits `arm64-v8a` only |

## Installation & release commands

```bash
export JAVA_HOME=/path/to/temurin-17
export ANDROID_HOME=/path/to/sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd SentinelAI/android

# Debug build (development)
./gradlew assembleDebug

# Release build (signed)
./gradlew assembleRelease
```

Keystore: `app/sentinelai-release.jks` (genkey: CN=SentinelAI, RSA-2048, 10,000 days,
store/keypass `sentinelai`). The release APK is signed with it and is **not Play-ready**.

## Acceptance criteria (verified)

1. `gradle testDebugUnitTest` → BUILD SUCCESSFUL, 15 tests, 0 failures, 0 errors.
2. `gradle assembleDebug` + `assembleRelease` → BUILD SUCCESSFUL.
3. `apksigner verify --print-certs` → Signer verified.
4. `aapt dump badging` → `ai.sentinel.mobile` / 1.0.0 / minSdk 26 / targetSdk 35 / arm64-v8a.
5. APK contains `assets/model.onnx` + `lib/arm64-v8a/libonnxruntime.so` + other assets.
6. `sample_expected.json` parity gate: 140/140 argmax, delta 2.731e-04.

## Known deviations (recorded)

- SharedPreferences `SettingsStore` (not DataStore).
- Manual `LIMIT/OFFSET` paging (not Paging 3).
- ARM64-only split.
- Self-signed keystore (sideload only).
- `specialUse` FGS type for VPN (prevents lockout/bricking).
