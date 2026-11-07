# SentinelAI — Standalone Android Plan (executed summary)

Authoritative spec files: `docs/ANDROID_BRIEF.md` and `files/SentinelAI-Standalone-Android-Plan.md`.
This document is the executed record of that plan.

---

## 1. Scope

Build **one standalone Android application** for the Lenovo Idea Tab Pro (8 GB RAM / 128 GB):

1. Native Kotlin + Jetpack Compose UI.
2. Android `VpnService` for real network monitoring.
3. Packet parsing + network flow tracking.
4. ONNX Runtime Mobile on-device detection.
5. Local firewall with real IP/port blocking.
6. Room DB for traffic, alerts, history.
7. Dashboard, Live Monitor, Traffic Analysis, Threat Detection, Alerts, Reports, Settings.
8. Offline detection and firewall.
9. Safe VPN controls + emergency stop.
10. Optimised for the tablet's hardware.

**Complete milestones M0–M7.** No plan, no plan-only milestone stopping, no external backend.

---

## 2. Built (not planned)

### 2.1 Code

- **31 Kotlin files** under `SentinelAI/android/app/src/main/java/ai/sentinel/mobile/`:
  - UI: `MainScreens`, `DetectionScreens`, `ControlScreens` (Dashboard, Live Monitor, Traffic, Threat Detection, Alerts, Reports, Settings),
    `SentinelNav.kt` (navigation rail), `Theme`, `SentinelApp`.
  - VPN: `SentinelVpnService`, `VpnController`, `VpnControlReceiver` (start/stop emergency).
  - Firewall: `PolicyEngine`, `RuleEngine`, `Blocklist`, `DnsInspector`, tunnel state.
  - Network: `PacketParser` (IPv4/IPv6 TCP/UDP/ICMP), `FlowTracker` (bounded 4096-flow table).
  - Model: `OnnxDetector` (ORT 1.19.2 mobile), `FeatureBuilder`, `ModelContract`.
  - Storage: `SentinelDatabase` (Room), entities for flows/alerts/reports; `SettingsStore`
    (SharedPreferences), `RetentionWorker` (WorkManager).
  - `Formatters`, `SelfTest`, `Common`, `Resources`.
- **15 JVM unit tests** in `SentinelCoreTest.kt` covering packet parsing, checksums,
  UDP reconstruction, flow table, feature pipeline, policy self-lockout, and ONNX parity.

### 2.2 Assets

- `assets/model.onnx` (11,422,978 B), `assets/model_contract.json`, `assets/sample_flows.csv`,
  `assets/sample_expected.json`, `assets/blocklist.txt`.

### 2.3 Docs

- `docs/DEVICE_TEST_REPORT.md` — device checklist and honest status table.
- `docs/SETUP.md` — install, first run, daily use, troubleshooting.
- `docs/MODEL_EXPORT.md` — how the ONNX model is produced and vetted.
- `docs/ACCEPTANCE.md` — milestone status, deviations, pending-device criteria.
- `docs/ANDROID_BRIEF.md` + `docs/ANDROID_PLAN.md` — accepted spec summaries.

### 2.4 Build outputs

| Build | Path | Size | Status |
|---|---|---|---|
| Debug | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 37,227,260 B | BUILD SUCCESSFUL |
| Release | `app/build/outputs/apk/release/app-arm64-v8a-release.apk` | 20,821,140 B | BUILD SUCCESSFUL, signed |

Verifier results:

- `apksigner verify --print-certs`: Signer verified (CN=SentinelAI, RSA-2048, SHA-256
  `a345343fc5dee1d75adef4897a9fc38c43a7bba28d5500a6bfd68028233c34e4`).
- `aapt dump badging`: `ai.sentinel.mobile` / `1.0.0` / minSdk 26 / targetSdk 35 /
  `FOREGROUND_SERVICE_SPECIAL_USE`, `INTERNET`, `ACCESS_NETWORK_STATE`, notifications,
  `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`; lib `arm64-v8a` present.
- Assets/`assets` and native libs inside the release APK confirmed.

---

## 3. Milestones M0–M7

| M | Result | Evidence |
|---|---|---|
| M0 | DONE — Verified | ONNX export + 140/140 parity (delta 2.731e-04); 4 parity tests; artifacts in `backend/data/artifacts/` |
| M1 | DONE — Verified | 15 JVM tests passed, 0 failures; `SentinelCoreTest.kt` compiles |
| M2 | DONE — Built | 31 Kotlin files, 9 screens, Compose UI, nav rail, theme |
| M3 | DONE — Built | VpnService + PacketParser + FlowTracker; `compileDebugKotlin` 0 errors |
| M4 | DONE — Built | Room DB + SettingsStore + retention worker + report exporter |
| M5 | DONE — Built | ONNX Mobile detector; feature builder + model contract; self-test |
| M6 | DONE — Built | Firewall: PolicyEngine/RuleEngine/Blocklist/DnsInspector; `assembleDebug`+`assembleRelease` pass; manifests + signatures + APKs verified |
| M7 | DONE — Partial | Build/API level verified; all `[DEVICE]` items PENDING ON DEVICE (no tablet, no `adb devices`) |

---

## 4. How the app behaves on the tablet (design truth, not a measurement)

- **DNS_ONLY** is the default protection mode: DNS + user-configured blocked IP/CIDR/domain
  ranges are intercepted; everything else passes unchanged. No userspace TCP/IP stack,
  no laptop, no FastAPI/Python/React/cloud/third-party VPN app.
- **CAPTURE** (Settings → Analysis → Capture Window, default 20 s) routes `0.0.0.0/0` for a
  bounded window: DNS relayed, all other packets dropped. Test window only — disables internet.
- Emergency stop: `VpnController.toggleEmergency()`; the service pauses DNS relay and drops
  all traffic until toggled off.
- Firewall rules live in Room DB; the VPN service reads them per-packet and applies them before
  the packet is relayed.
- The ONNX model is loaded from `assets/` on first use; the app self-tests the model against
  `sample_expected.json` and returns `null` on any inference failure (no crash, no silent
  mis-detection).

---

## 5. Tablet-side device tests (PENDING ON DEVICE)

From `docs/DEVICE_TEST_REPORT.md` — the 15 criteria that require the Lenovo Idea Tab Pro:

1. Install + launch (no crash).
2. Live Monitor: flows appear (~10 s after activity).
3. DNS interception over real Wi-Fi/LTE (blocked names fail, others normal).
4. Real IP/port rule blocks (second app affected; others not).
5. Firewall drop proof (`0 received` in capture log).
6. Emergency stop isolates traffic; toggle restores.
7. Device ONNX inference (label + confidence, ~0.05–0.30 s).
8. Room persistence after kill.
9. Auto-start after boot with VPN on.
10. Battery/thermal (full day or full cycle).
11. Battery-drain benchmark (with/without app).
12. Cold-start < 10 s after install.
13. Wi-Fi ↔ mobile data identical behaviour.
14. Crash-free session (≥ 98% target).
15. Memory < 200 MB (`dumpsys meminfo`).

**Minimum user-side test kit:** install APK → enable VPN (grant special-use permission) →
load a site → add a custom block rule → confirm blocked → toggle Emergency stop → run Threat
Detection → force-stop → reboot with VPN on → measure battery/temp.

---

## 6. Known caveats (no device is claimed to have run)

- `[DEVICE]` criteria are **PENDING** — the build machine has no physical Android device
  (`adb devices` empty). All tablet-side behaviour is design-verified, not device-measured.
- Release APK is self-signed for sideloading; not Play-ready.
- ARM64-only split; `armeabi-v7a`/x86_64 not included.
- `SettingsStore` uses SharedPreferences (not DataStore); paging is manual `LIMIT/OFFSET`
  (not Paging 3).

---

*End of executed plan record. Full deliverable is in `SentinelAI/android/` with the signed
release APK at `app/build/outputs/apk/release/app-arm64-v8a-release.apk`.*
