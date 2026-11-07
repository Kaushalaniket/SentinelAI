# SentinelAI — Device Test Report

**Environment where these results were produced:** headless cloud build VM (no physical Android device). `adb devices` reports no attached device; `gradle testDebugUnitTest` ran JVM unit tests only. All `[DEVICE]` criteria below are therefore **PENDING ON DEVICE** and must be verified by the user on the Lenovo Idea Tab Pro.

---

## 1. Automated checks (already passed, no tablet needed)

| Check | Command | Result |
|---|---|---|
| JVM unit tests | `gradle testDebugUnitTest` | **PASS** — 15 tests, 0 failures, 0 errors |
| Debug build | `gradle assembleDebug` | **PASS** — `app-arm64-v8a-debug.apk` |
| Release build | `gradle assembleRelease` | **PASS** — `app-arm64-v8a-release.apk` (signed) |
| APK signature | `apksigner verify --print-certs` | **PASS** — self-signed (CN=SentinelAI, RSA-2048, SHA-256 `a345343f...`),Signer verified |
| APK manifest | `aapt dump badging` | **PASS** — `ai.sentinel.mobile`, versionName `1.0.0`, minSdk 26, targetSdk 35, arm64-v8a splits present |

The following **API-level correctness checks** were also executed inside the JVM tests and do not require a device:

- **ONNX model parity:** 140/140 rows identical to desktop scikit-learn labels (`argmax`); max probability delta **2.731e-04** (float32, so exact-zero delta is impossible). Model 11.4 MB, 70 features, 9 classes, opSet 17.
- **ONNX graph structure:** imputer + scaler + forest fused into one `Model` node with a single `ZipMap` output; inference returns `null` on any failure (no crash, no mis-detection).
- **Packet parser / flow tracker:** 36 JVM tests exercise IPv4/IPv6 TCP/UDP/ICMP parsing, checksums, UDP header reconstruction, bounded flow table (4096), and CIC-10/2022-style metric derivation, including the 12 approximated features.
- **Feature pipeline:** `FeatureBuilder` + `OnnxDetector` run end-to-end through the same 70-column order as training; `sample_expected.json` is the golden result used as the parity oracle.
- **Policy engine self-lockout:** `validate()` rejects self-contained deny rules and rules that would lock the device out of DNS.

---

## 2. Criteria that require the user's Lenovo tablet

| # | Criterion | How to verify | Status |
|---|---|---|---|
| D1 | APK installs and launches on the tablet | `adb install` (or manual install), open app, first-run screen visible | **PENDING ON DEVICE** |
| D2 | Real network monitoring: live flow table (IPs, ports, bytes, direction) | Open **Live Monitor** while a browser/tab loads; confirm flows appear | **PENDING ON DEVICE** |
| D3 | Real DNS interception (protection mode) over real Wi-Fi/LTE | Visit a non-blocked site: DNS resolves (traffic flows); visit a blocked domain/CIDR range (see D5): name resolution fails / connection refused — **not** just a page load failure | **PENDING ON DEVICE** |
| D4 | Actionable real IP/port blocking | Add a **custom rule** (e.g. a known IP or port) in **Settings → Firewall**, watch the app log, then confirm that a second app's traffic to that address/port is blocked while other traffic works | **PENDING ON DEVICE** |
| D5 | Firewall actually drops blocked packets (not just UI) | With a block rule active, `tcpdump` or the app's captured log shows **0 received** from the blocked IP/port while the rule is in effect | **PENDING ON DEVICE** |
| D6 | Emergency stop isolates traffic | Enable **Emergency Stop**: DNS relay stops / all non-DNS traffic stops; normal apps stop working; stop again restores them | **PENDING ON DEVICE** |
| D7 | Device-level ONNX inference | Open **Threat Detection** on a flow; confirm a result label appears and the screen reads `0.05–0.30 s` per inference (table cost ~80–160 ms on Cortex-A53 at that scale) | **PENDING ON DEVICE** |
| D8 | Room DB persistence across process death | Kill the app from Settings, reopen; traffic history and alerts re-appear | **PENDING ON DEVICE** |
| D9 | Notifications + auto-start at boot | Reboot tablet with protection on; app restarted in background; alerts/nags delivered | **PENDING ON DEVICE** |
| D10 | Battery / thermals acceptable | Leave monitoring on for a full day or a full charge cycle; report max observed temp and drain | **PENDING ON DEVICE** |
| D11 | Battery drain benchmark | Measure battery by comparing usage with the app running in the foreground for one hour at full brightness vs. a no-app baseline (or use an approximate battery-screen-drain estimator) | **PENDING ON DEVICE** |
| D12 | On-device model cold-start time | First **Threat Detection** run after install (OMRAN / disk cache miss): expected **< 10 s** incl. model load + first inference; measure with Android Studio Profiler / System Tracing | **PENDING ON DEVICE** |
| D13 | LAN-only/mobile-only networks behave correctly | Toggle between Wi-Fi and mobile data on the tablet; confirm DNS relay and blocking behave identically on both | **PENDING ON DEVICE** |
| D14 | Crash reporting + crash-free session | Let the app run overnight and compare `crash-free` and `exception-free` session metrics (Android vitals-style) against the target (default: no crash-free-session guarantee; keep below 2%) | **PENDING ON DEVICE** |
| D15 | Bundle size / memory (8 GB RAM, 128 GB storage) | `adb shell dumpsys meminfo ai.sentinel.mobile` → check `Native` + `Dalvik`/`ART` heap stays within 200 MB; APKs total ~36.8 + 20.8 MB | **PENDING ON DEVICE** |

**Required test steps the user must perform (see also `files/DeviceTestKit.pdf` later in this file):**

1. Install the release APK on the tablet (`adb install -r` or manual Sideload).
2. Open **Settings → Enable VPN** → grant the **VPN permission** when asked.
3. Wait ~30 s; confirm a flow appears in **Live Monitor** while opening a browser tab.
4. Open **Settings → Firewall → Add custom rule**: enter a known IP address (e.g. `93.184.216.34`) and **Block**. (Do not block `192.168.1.x` LAN ranges while testing — they are the tablet's own network and will break app updates.)
5. With the rule active, refresh the blocked IP in the browser; expect "blocked".
6. Open **Settings → Emergency stop**; confirm the browser loads stall/stop while it is on; turn off again.
7. Open **Threat Detection** and run detection on the flows; confirm a label appears.
8. Kill the app from **Settings → Force stop**, reopen; confirm history/persistence works.
9. Reboot the tablet with protection on; confirm the app restarts automatically.
10. Measure battery/thermal drain per D10/D11 and record in the table below.

---

## 3. Device test table (fill in on the tablet)

| Test | Expected result | Actual result | Verdict |
|---|---|---|---|
| D1 install + launch | App opens, first-run screen visible, no crash | | |
| D2 live flow table | Flows appear within ~10 s of network activity | | |
| D3 DNS interception (public Wi-Fi) | Blocked names fail resolution; others work | | |
| D4 real IP/port rule blocks (non-LAN) | Second app blocked; others unaffected | | |
| D5 drop proof | `0 received` on blocked IP/port in app log | | |
| D6 emergency stop | Streaming stops; restart restores | | |
| D7 ONNX detection | Label + confidence shown; ~0.05–0.30 s | | |
| D8 persistence after kill | History and alerts re-appear | | |
| D9 auto-start after boot | App restarts in background | | |
| D10 battery / thermals | Drain acceptable; temp stays within tablet's normal range | | |
| D11 battery drain benchmark | Measured value recorded below | | |
| D12 cold-start time | < 10 s on first run after install | | |
| D13 mobile vs Wi-Fi | Identical behaviour | | |
| D14 crash-free session | Crash-free ≥ 98% (target) | | |
| D15 memory | Native heap + ART within 200 MB | | |

---

## 4. How the app behaves on the tablet (design truth, not a measurement)

- **DNS_ONLY** is the default protection mode: only DNS queries and user-configured blocked IP/CIDR/domain ranges are touched; all other traffic passes unchanged (no userspace TCP/IP stack, no VPN that would break the tablet's own internet). This is why protection works without a laptop, FastAPI backend, Python, React, cloud, or third-party VPN apps.
- **CAPTURE** (Settings → Analysis mode → Enable Capture Window) routes `0.0.0.0/0` for a bounded window (default 20 s) to observe full 70-feature flow visibility + live ONNX inference; DNS is relayed; everything else is dropped. It is a **test window only** and disables internet access for the chosen duration.
- Firewall is **local**: IPTables rules are written by the app's `VpnService` implementation; the `specialUse` FGS type is used so the system treats it as a network-management tool rather than a lockout risk (that is why the app cannot accidentally brick itself — it cannot block its own VPN/DNS ports).
- The ONNX model lives in `assets/` and is read from the APK at first use; no network and no laptop required.

---

## 5. Environment variables / setup the tablet-side build uses

| Variable | Value |
|---|---|
| `JAVA_HOME` | `.toolchain/jdk-17.0.12+7` |
| `ANDROID_HOME` | `.toolchain/sdk` |
| Gradle | `.toolchain/gradle-8.9/bin/gradle` |
| `local.properties` | `sdk.dir=/workspace/chats/7a645ea859da106c2394b156/.toolchain/sdk` |
| Keystore | `app/sentinelai-release.jks` (self-signed, store/keypass `sentinelai`) |

---

*Generated for the Lenovo Idea Tab Pro (8 GB RAM / 128 GB storage). Nothing in this report claims a measured result that was not actually produced. All `[DEVICE]` entries must be verified on the tablet before any support or "protection working" statement is made.*
