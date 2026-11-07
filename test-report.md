# SentinelAI — build and test report

Generated during the v1.0.0 rebuild. Only executed checks are listed as passing.

## Environment
- JDK 17.0.12, Gradle 8.9 (wrapper), Android SDK Platform 35, Build Tools 35.0.0
- ABI arm64-v8a, minSdk 26, targetSdk 35
- No Android device or emulator available (no system image, no usable hardware acceleration)

## Executed and passing
1. `./gradlew --version` — wrapper downloads and runs Gradle 8.9.
2. `./gradlew assembleDebug` — BUILD SUCCESSFUL; `app-arm64-v8a-debug.apk` produced.
3. `./gradlew testDebugUnitTest` — 15 tests, 0 failures, 0 skipped.
   Covers IPv4/IPv6/TCP/UDP/DNS parsing, malformed and truncated packets, IPv4 checksum,
   bidirectional flow aggregation, bounded flow table with eviction, allow-over-block
   precedence, CIDR boundaries, expired/disabled rules, self-lockout validation, domain and
   subdomain matching, DNS answer parsing, and the no-fabricated-category guarantee.
4. `./gradlew assembleDebugAndroidTest` — instrumentation tests compile.
5. `./gradlew assembleRelease` — R8 minification and resource shrinking succeed; unsigned APK.
6. `aapt dump badging` — valid package structure, label SentinelAI, native-code arm64-v8a.
7. ONNX model check (onnxruntime 1.31.1, model as shipped in assets):
   input `[None,70]` float32; outputs `label [None]` string and `probabilities [None,9]` float32.
   140/140 bundled reference flows classified identically to the exported reference labels.
   NaN-imputation path executes without error.

## Compiled but NOT executed
`DatabasePersistenceTest`, `NavigationTest` (instrumented). They require a device or emulator.

## Not verified
App startup, navigation rendering, VPN consent and lifecycle, internet continuity under VPN,
firewall block/unblock against a live destination, on-device inference, portrait/landscape
layout. These require the target tablet.
