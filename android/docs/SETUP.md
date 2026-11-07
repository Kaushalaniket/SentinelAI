# SentinelAI — Sideload & Device Setup Guide (Lenovo Idea Tab Pro)

This document explains how to get **SentinelAI** onto the tablet and running
standalone — no laptop, no FastAPI backend, no Python, no React, no cloud,
no third-party VPN app. Everything runs on the tablet.

---

## 1. What you are installing

| Item | Value |
|---|---|
| App | SentinelAI (Kotlin + Jetpack Compose) |
| Package / version | `ai.sentinel.mobile` / `1.0.0` |
| Target device | Lenovo Idea Tab Pro (8 GB RAM, 128 GB) |
| CPU/ABI | ARM64 (aarch64) — `armeabi-v7a`/`x86_64` not split in this build |
| Build | Signed release APK, self-signed keystore `app/sentinelai-release.jks` |
| Signature | SHA-256 `a345343fc5dee1d75adef4897a9fc38c43a7bba28d5500a6bfd68028233c34e4` |
| Minimum Android | 8.0 (API 26) — your Lenovo Idea Tab Pro runs Android 11+ (API 30); safe |
| Permissions | VPN (special-use foreground service), network state, notifications, boot-receiver |

---

## 2. Install on the tablet

### Option A — with adb (recommended, fastest)

From a computer with **USB debugging enabled** on the tablet:

```bash
# 1. Enable Developer Options: tap "Build number" 7 times in Settings → About tablet
# 2. Settings → Developer options → enable "USB debugging"
# 3. Connect the tablet via USB and trust the computer when asked

# 4. Install the release APK (replace with your actual path)
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk

# 5. Verify it was installed
adb shell pm list packages | grep sentinel
```

Expected output: `package:ai.sentinel.mobile`.

### Option B — manual install

1. Transfer `app-arm64-v8a-release.apk` (20.82 MB) to the tablet (USB cable, cloud,
   or pull it from the SentinelAI folder on this machine).
2. Open **Files** on the tablet, tap the APK → **Install**.
3. Open **Settings → Apps → SentinelAI** and allow it to run.

---

## 3. First run — what you will see and do

| Step | What happens |
|---|---|
| Launch | First-run screen: app name, version, a short "how it works" note. |
| Dashboard | Summary of connected networks, active flows, protection status. |
| Live Monitor | Real-time flow table (IP, port, direction, bytes, protocol). |
| Traffic Analysis | Historical traffic view (Room DB). |
| Threat Detection | ONNX on-device detection over the last N seconds of flow data. |
| Alerts | Notifications for suspicious flows (Tor, VPN, scraper-class flows, etc.). |
| Reports | Export a traffic/alert report (CSV) from **Reports**. |
| Settings | 12+ settings: VPN toggle, firewall rules, capture window, notifications, retention, data export, restart on boot. |

### Granting VPN permission — the only "dangerous" permission

- Open **Settings → VPN** (or **Network & internet → VPN**) and turn on **SentinelAI**.
- The system asks: **"SentinelAI has not been verified by Google Play"** — tap **Allow** (or **Continue**).
  - On the Lenovo Idea Tab Pro, a special-use foreground-service permission (`android.permission.FOREGROUND_SERVICE_SPECIAL_USE`) is declared, which is why the system shows a network-management prompt instead of a generic "this app can see all your data" prompt. That is intentional and part of the safety design.
- Once allowed, the app shows a **VPN status card** and the dashboard shows the connected network.

---

## 4. Using SentinelAI — day-to-day

### Protection (DNS_ONLY) — the default, recommended
1. **Settings → Enable VPN** → toggle **On**.
2. The app now:
   - relays DNS for safe domains,
   - blocks **user-configured** IP/CIDR/domain-resolved dangerous ranges,
   - leaves all other traffic (including all non-DNS traffic) untouched.
3. Blocked destinations are silently dropped → real blocking, with no laptop/cloud.
4. Verify blocking with **Live Monitor**: a blocked site shows zero traffic after the
   DNS lookup, while other sites load normally.

### Emergency stop
1. **Settings → Emergency stop** → toggle **On**.
2. All non-DNS traffic is dropped and DNS relay stops → internet pauses for every app.
3. Toggle **Off** to restore normal operation. This is the "safe VPN controls" escape hatch.

### Firewall — real IP/port blocking
1. **Settings → Firewall → Add rule**.
2. Choose **IP address / CIDR** or **Domain / port**; enter e.g. a known nuisance IP or port.
3. **Block** (default) or **Allow**.
4. Rules persist in Room DB; the app re-reads them on start and applies them in the VPN flow.
5. Test with **Live Monitor** before rebooting.

### Threat Detection (on-device AI)
1. Open **Threat Detection**.
2. The app builds the last 70-feature vector from the flow window and runs the bundled
   ONNX model (`assets/model.onnx`, 11.4 MB).
3. Shows the predicted class with a confidence score and a latency hint
   (typically ~0.05–0.30 s on a Cortex-A53 tablet for this model size).

### Reports
1. **Reports → Export** → choose CSV/JSON → the file is stored in the app's internal
   storage and can be copied to the tablet's Files app.
2. Retention: **Settings → Data retention** — configure how long flows/alerts are kept
   (default is room-safe; the app purges old rows on dashboard refresh).

---

## 5. Rebooting the tablet

- The app is set to restart automatically after boot (`RECEIVE_BOOT_COMPLETED` + WorkManager).
- On next boot with VPN enabled, the app resumes in the background, reconnects to the network,
  and resumes monitoring (Room DB persists flows and alerts).
- If the VPN fails to start after boot, manually re-enable it in **Settings → VPN**.

---

## 6. What the tablet must do (device-test checklist)

These are the criteria that genuinely require the Lenovo tablet. Everything else (build,
unit tests, ONNX parity) is already verified on the build machine.

| # | Test | Expected result |
|---|---|---|
| 1 | Install APK (adbb or manual) | App opens, no crash |
| 2 | Enable VPN (grant permission) | VPN status card shows connected |
| 3 | Load a normal site w/ VPN on | Page loads normally; flows appear in Live Monitor |
| 4 | Block a known IP (Settings → Firewall) | Subsequent connection to that IP fails / shows blocked |
| 5 | Block a known port (e.g. a DNS-over-HTTPS port) | App using that port drops/shows blocked |
| 6 | Emergency stop | Internet pauses; toggle off → restored |
| 7 | Threat Detection run | Label + confidence displayed |
| 8 | Force-stop app | History/alerts re-appear on reopen |
| 9 | Reboot with VPN on | App auto-starts, monitoring resumes |
| 10 | Battery/thermal (don't forget) | Drain and temp within normal tablet range over a full day |
| 11 | Memory check | `adb shell dumpsys meminfo ai.sentinel.mobile` — Native + ART/Dalvik < ~200 MB |
| 12 | Cold start time | First Flow/Threat run after install: < 10 s |
| 13 | Wi-Fi ↔ mobile data toggle | Identical blocking/DNS behaviour on both |

---

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| App closes immediately after opening | Content_filter / VPN not granted — check Settings → Apps → SentinelAI → permissions |
| VPN switch shows "Denied" | Tablet blocked the special-use request; re-grant under **Settings → VPN** or **Settings → Developer options → VPN** |
| No flows appear in Live Monitor | Connected to mobile data but "Allow mobile data" is off in VPN settings; or app was launched in demo mode (test button) |
| Blocked site still loads | Check you added the **domain** not just IP; DNS-only mode resolves then blocks; try CAPTURE mode for full test |
| App consumes high battery | Keep VPN on only while needed; check retention settings; confirm the capture window is off by default |
| Memory > 200 MB | Refresh the dashboard; old flows are pruned per the retention setting; consider clearing the app's cache |

---

## 8. Removal

- **Settings → Apps → SentinelAI → Uninstall** (also removes its Room DB).
- Or `adb uninstall ai.sentinel.mobile`.
- No leftover processes, no cloud accounts, no background daemons after uninstall.

---

*This is a complete standalone APK — the app ships with the ONNX model,
blocklist, feature/vector code and all 9 screens bundled. Nothing is downloaded
at runtime. Protect your tablet with no laptop and no cloud.*
