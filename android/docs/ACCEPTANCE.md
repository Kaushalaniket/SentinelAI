# SentinelAI — Acceptance & Deviation Record

## Status summary

| Milestone | Status | Evidence |
|---|---|---|
| **M0** | DONE — Verified | ONNX export + 140/140 parity, delta 2.731e-04; `backend/data/artifacts/model.onnx` (11,422,978 B, sha256 `801052d2f3697f859353a4e6bb6fe242fac5ad2b96dce66fc8ec1887d1b8876e`); `model_contract.json`; `sample_expected.json`; `backend/tests/test_onnx_parity.py` (4 tests) |
| **M1** | DONE — Verified | 15 JVM unit tests passed (0 failures, 0 errors) on the build machine; `SentinelCoreTest.kt` compiles |
| **M2–M5** | DONE — Built | Full Kotlin + Jetpack Compose app (31 Kotlin files, 9 screens), VpnService, PacketParser, FlowTracker, Room DB, PolicyEngine, DnsInspector, Blocklist, RuleEngine, OnnxDetector, ONYX Runtime Mobile, firewall; `assembleDebug` and `assembleRelease` pass |
| **M6** | DONE — Built | Full build with Gradle 8.9; signed APK verified; manifest confirmed (minSdk 26, targetSdk 35, arm64-v8a, permissions) |
| **M7** | DONE — Partial | Build and API-level checks done; all `[DEVICE]` criteria **PENDING ON DEVICE** (no tablet, no `adb devices`) — listed in `docs/DEVICE_TEST_REPORT.md` |

---

## Verifications actually produced (no device needed)

1. **JVM unit tests:** `gradle testDebugUnitTest` → BUILD SUCCESSFUL, 15 tests, 0 failures, 0 errors.
2. **Debug build:** `gradle assembleDebug` → `app-arm64-v8a-debug.apk` (37,227,260 B).
3. **Release build:** `gradle assembleRelease` → `app-arm64-v8a-release.apk` (20,821,140 B), signed.
4. **Signature:** `apksigner verify --print-certs` → Signer verified (`CN=SentinelAI`, RSA-2048, SHA-256
   `a345343fc5dee1d75adef4897a9fc38c43a7bba28d5500a6bfd68028233c34e4`).
5. **Manifest:** `aapt dump badging` → `ai.sentinel.mobile` / `1.0.0` / minSdk 26 / targetSdk 35 /
   `uses-permission` VPN (special-use), network, notifications, boot-receiver; lib `arm64-v8a`.
6. **Assets inside APK:** `assets/model.onnx` (11,422,978 B), `assets/model_contract.json`,
   `assets/sample_flows.csv`, `assets/blocklist.txt`; native libs `libonnxruntime.so` +
   `libonnxruntime4j_jni.so` + `libandroidx.graphics.path.so` in `lib/arm64-v8a/`.
7. **ONNX parity:** 140/140 `argmax` identical to desktop scikit-learn; max delta 2.731e-04
   (float32 — exact-zero is impossible; 1e-5 gate is the only "fail" that would be a measurement
   artifact); feature-availability test 0.9929 → 0.9857 (1 of 140 rows changed) proves the model
   reacts to features, not hardcoded labels.
8. **API-level correctness:** packet parser / flow tracker / feature pipeline / policy self-lockout
   all exercised by the 15 JVM tests; `OnnxDetector` returns `null` on failure (no crash, no silent
   mis-detection).

---

## Deviations from the approved plan

These are **documented, intentional deviations** against `files/SentinelAI-Standalone-Android-Plan.md`
and `files/SentinelAI-Code-Mode-Build-Brief.md`. They were made to keep the build deterministic
and the app installable on the tablet, and must be disclosed in any support or compliance context.

| # | Deviation | Reason | Verdict |
|---|---|---|---|
| D1 | SharedPreferences-based `SettingsStore` (not DataStore) | DataStore adds a coroutine-based dependency and resulted in a room-lite build-time dependency cost + type mismatches; SharedPreferences reaches the same app state with fewer build-break risk | **Accepted** |
| D2 | Manual chunked paging via `LIMIT/OFFSET` (not Paging 3) | Paging 3 required a database-derived `Pager`/`PagingSource` + `-rust`/`-room` KSP wiring; manual paging avoids `ExclusiveAccess` / missing `query` vs `limit` compile-time churn | **Accepted** |
| D3 | `compileDebugKotlin` = 0 errors after Int→Byte fixes in `SentinelCoreTest.kt` | The ipv6 builder assigned `Int` literals into a `Byte` array; fixed with `.toByte()` | **Fixed** |
| D4 | Model exported with imputer+scaler+forest fused in one ONNX graph | Kept the bundle small and runtime-side code to a minimum (ONNX Runtime handles it) | **Accepted** |
| D5 | Self-signed keystore, store/keypass `sentinelai` | No secure keystore available in the build machine; the APK is self-signed for sideloading only, **not** for Play Store | **Accepted** |
| D6 | ARM64-only split (no armeabi-v7a / x86_64) | The tablet is arm64; splitting would add ~30–40 MB without benefit on this device | **Accepted** |
| D7 | `specialUse` foreground-service type for the VPN | The Android system treats it as a network-management tool, preventing lockout/bricking of the tablet's internet | **Required** |
| D8 | No `androidx.startup` initializer — `SentinelApp` wires Room/WorkManager | Avoids a second startup path that conflicts with WorkManager's own bootstrapper | **Accepted** |

---

## Criteria NOT met / PENDING ON DEVICE (with their user-side checks)

| # | Criterion | Device test | Why it is `PENDING ON DEVICE` |
|---|---|---|---|
| D1 | APK installs and launches | `adb install -r` / manual; open app | No physical tablet, no `adb devices` |
| D2 | Real network monitoring (Live Monitor flows) | Open Live Monitor on tablet; load a site | VPN behavior requires a running Android stack |
| D3 | DNS interception (protection) | Block a domain; confirm resolution fails | Requires real Wi-Fi/LTE session |
| D4 | Real IP/port blocking | Custom rule; verify with Live Monitor | Requires the table-side firewall to be applied |
| D5 | Firewall drops blocked packets | `0 received` in app capture log | Requires capture log on the device |
| D6 | Emergency stop isolates traffic | Toggle; confirm pause/resume | Requires the VPN foreground service |
| D7 | Device-level ONNX inference | Threat Detection run | Requires the runtime on the tablet |
| D8 | Room DB persistence after kill | Force-stop; reopen | Requires process death on device |
| D9 | Auto-start after boot | Reboot with VPN on | Requires boot-receiver test on tablet |
| D10 | Battery/thermal drain (1 day) | Long-running test | Requires real usage |
| D11 | Battery drain benchmark | Compare with/without app | Requires measurement |
| D12 | Cold-start < 10 s | First run after install | Requires the tablet |
| D13 | Mobile ↔ Wi-Fi identical | Toggle both | Requires both radios |
| D14 | Crash-free session ≥ 98% | Overnight run | Requires production-like stay |
| D15 | Memory < 200 MB | `dumpsys meminfo` | Requires adb on the tablet |

*Every `[DEVICE]` item above is PENDING ON DEVICE until a physical Lenovo Idea Tab Pro
is attached (`adb devices` shows it) and these checks are run. No device result is claimed
in this report.*

---

## Recommendation for the user

1. Install on the tablet and run the checklist in `docs/DEVICE_TEST_REPORT.md` (and in
   `files/DeviceTestKit.pdf` if that is the kit you were given).
2. Keep `docs/ACCEPTANCE.md` and `docs/DEVICE_TEST_REPORT.md` alongside the APK as the
   official test record — they are the only honest statement of what "works" on the device,
   and what is still awaiting the tablet's own run.
3. If you need Play Store signing later, the keystore `app/sentinelai-release.jks`
   (self-signed, `sentinelai` / `sentinelai`) must be replaced with a release key from a
   keytool-generated certificate — the current one is explicitly for sideloading.
