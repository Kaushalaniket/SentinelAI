# SentinelAI — build and test report

Version 1.1.0 (versionCode 2), adaptive layout build. Only executed checks are listed as passing.

## Environment
- JDK 17.0.12, Gradle 8.9 (wrapper), Android SDK Platform 35, Build Tools 35.0.0
- ABI arm64-v8a, minSdk 26, targetSdk 35
- No Android device or emulator available (no system image, no usable hardware acceleration)

## Executed and passing
1. `./gradlew assembleDebug` — BUILD SUCCESSFUL.
2. `./gradlew testDebugUnitTest` — 22 tests, 0 failures, 0 skipped:
   - `SentinelCoreTest` (15): packet parsing (IPv4/IPv6/TCP/UDP/DNS), malformed and truncated
     packets, IPv4 checksum, bidirectional flow aggregation, bounded flow table, allow-over-block
     precedence, CIDR boundaries, expired/disabled rules, self-lockout validation, domain and
     subdomain matching, DNS answer parsing, no fabricated categories.
   - `AdaptiveLayoutTest` (7): Material 3 width breakpoints across 320–1366 dp; two tiles per row
     on a 360 dp phone; no cell overflow on any tested form factor; five tiles across on a tablet;
     column count never below one or above the item count; spacing/chart/tile metrics increase
     from compact to expanded.
3. `./gradlew assembleDebugAndroidTest` — instrumentation tests compile.
4. `./gradlew assembleRelease` — R8 minification and resource shrinking succeed; unsigned APK.
5. ONNX model check (onnxruntime 1.31.1, model as shipped in assets): input `[None,70]` float32;
   outputs `label [None]` string and `probabilities [None,9]` float32; 140/140 bundled reference
   flows classified identically to the exported reference labels.

## Compiled but NOT executed
`DatabasePersistenceTest`, `NavigationTest` (instrumented). They require a device or emulator.

## Not verified
Rendered layout at any size class or orientation, app startup, navigation rendering, VPN consent
and lifecycle, internet continuity under VPN, firewall block/unblock against a live destination,
on-device inference. These require real hardware.
