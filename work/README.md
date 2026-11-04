# Xiaomi Mi TV 4A Pro 43 (`MiTV-AXSO0` / `dangalFHD`) — Customizations & Session Reference

- **Device**: Xiaomi Mi TV 4A Pro 43 (`MiTV-AXSO0`, codename `dangal`, product `dangalFHD`)
- **SoC / Hardware**: Amlogic Cortex-A53 Quad-Core @ up to 1.5 GHz (`Mali-450 MP` GPU), 1 GB RAM (`990 MB` usable), 4.4 GB `/data` storage, `1280×720 @ 60 Hz` display
- **OS / Firmware**: Android TV 9 (Pie, SDK 28), build `PI.6036`
- **Network**: `TV_HOST=192.168.1.50:5555`
- **Critical Invariants**: See [`work/DEVICE_RULES.md`](file:///Users/ashobhit/.gemini/jetski/scratch/cleanyourtv/work/DEVICE_RULES.md)

---

## 1. System Debloat & Package State

### Disabled (`pm disable-user --user 0`, reversible via `work/restore-all.sh`)
- **Google Feeds & Stock Home**:
  - `com.google.android.tvlauncher` (Stock Android TV Home)
  - `com.google.android.tvrecommendations`
  - `com.google.android.leanbacklauncher.recommendations`
- **Xiaomi PatchWall & Telemetry**:
  - `com.mitv.tvhome.atv` (PatchWall)
  - `com.mitv.tvhome.mitvplus` (Xiaomi TV+)
- **Miracast / Mi Cast**:
  - `com.mitv.milinkservice`
  - `com.xiaomi.mitv.smartshare`
  - `com.xiaomi.wfdsinkhelperservice`
- **Unused Preloads**:
  - `com.xiaomi.mimusic2`, `com.mitv.gallery`, `com.amazon.minitv.androidtv.app`, `com.nordvpn.android`

### Restored System Packages (`cmd package install-existing`)
An earlier third-party tool (`ADB AppControl`) had uninstalled 27 system packages for `user 0`. Two critical system packages were restored from `/system`:
1. `com.android.tv` — Required by Settings → Inputs and the launcher's HDMI/AV input switcher (`com.droidlogic.tvinput` PASSTHROUGH inputs). Hidden from the launcher's app grid via `hidden_apps`.
2. `com.xiaomi.android.tvsetup.partnercustomizer` — Required for Bluetooth remote pairing and global key handling (`Netflix`, `Prime Video`, and `Mi` button broadcasts/logs).

### System Settings Applied
| Setting | Current Value | Purpose | Undo Command |
|---|---|---|---|
| Private DNS (AdGuard) | `private_dns_mode=hostname`, `private_dns_specifier=dns.adguard-dns.com` | System-wide DNS ad/tracker blocking | `settings put global private_dns_mode opportunistic` |
| Power-on behavior | `set_auto_play=0` | Boot directly to Home instead of last HDMI/Live TV input | `settings put global set_auto_play 1` |
| Accessibility services | `enabled_accessibility_services=""`, `accessibility_enabled=0` | Preserves native long-hold volume key repeat (see Issue #1 below) | Keep disabled |

---

## 2. Custom Theme & Visual Setup (`work/themes/liquid-glass`)

Pushed via `tools/theme.sh work/themes/liquid-glass` to `/sdcard/Android/data/com.gathin.tvhome/files/`:
- **Cinematic Apple TV+ / Liquid Glass Aesthetic**:
  - 4-app glass dock (`Netflix`, `Prime Video`, `YouTube`, `JioHotstar`) with horizon focus glow (`"glow_style": "horizon"`, `"dock_glass": true`) and zero rows visible below the dock until scrolling down (`"rows_below_dock": 0`).
  - Time-of-day dynamic 4K aerial wallpapers (`01-marin-headlands-sunset.jpg`, `02-aerial-dawn.jpg`, `03-aerial-day.jpg`, `04-aerial-midnight-aurora.jpg`) mapped across `morning`, `evening`, and `night` moods.
  - Custom high-resolution 16:9 app banners (`banner_netflix`, `banner_prime`, `banner_youtube`, `banner_hotstar`, `banner_smarttube`, `banner_sonyliv`, `banner_playstore`, `banner_media`, `banner_adb`) baked into `launcher/app/src/main/res/drawable-nodpi/`.
  - Top bar quick-action pill (`"top_bar_apps": ["com.gathin.btconnect"]`) for one-click Bluetooth speaker/headphone reconnect.
  - Idle clock screensaver after 4 minutes (`"home_idle_clock_minutes": 4`, `"back_opens_clock": true`) with live weather effects (snow/rain/clouds/stars) driven by live Open-Meteo weather data.

---

## 3. Launcher (`com.gathin.tvhome`) Customizations & Code Audit Fixes

### Feature Additions
1. **Live Weather in Top Bar & Fullscreen Clock (`work/chill-radio` → `MainActivity` / `ClockActivity`)**:
   - Reads live temperature and WMO weather condition (`temp`, `wmo`, `condition`) from `content://com.gathin.chillradio.now` (Open-Meteo API for `12.97, 77.59`) and displays it in the top header and fullscreen clock screensaver, automatically syncing weather particle animations (`ClockView`) with real outdoor weather.
2. **Module Card Visibility Fix (`MainActivity.java`)**:
   - Fixed a bug where add-on apps that also have a `MAIN/LEANBACK_LAUNCHER` activity (`com.lionlev.tv.capacity` and `io.github.maxhayim.remoteaccess`) were only added to `moduleCards` inside `discoverModuleCards()` when not hidden from the app grid (`hidden_apps`). Now `moduleCards` discovery is decoupled from `hidden_apps`, and `module_card_visibility` (`compact` / `normal` / `hidden`) controls module cards independently.
3. **Top-Bar Bluetooth Pill Dynamic Title (`MainActivity.java`)**:
   - Queries `content://com.gathin.btconnect.card/now` in the background and updates the top-bar Bluetooth button's label and content description with the connected device name and battery level.
4. **Auto Background Memory Reaper (`MainActivity.java` ↔ `work/chill-radio`)**:
   - On return to Home from heavy streaming apps (`Netflix`, `YouTube`, `Prime Video`, `Hotstar`, `Sony LIV`, `SmartTube`), triggers `content://com.gathin.chillradio.now/reap` after a 1.5s delay to free cached background processes on this 1 GB RAM TV.

### Multi-Agent Codebase Audit Fixes in `launcher/`
- **60fps Zero-Allocation Render Path**:
  - Replaced per-frame `RadialGradient` and `LinearGradient` allocations in `AuroraView`, `ClockView`, and `BackdropView` with unit-space shaders transformed via reusable `Matrix` instances.
  - Precomputed trig tables (`SIN_TABLE`) in `ClockView` and cached `LayoutParams` in `MainActivity.onScrollStateChanged`.
- **Memory & Lifecycle Hardening**:
  - Downsampled custom wallpapers via `inSampleSize` + `Bitmap.Config.RGB_565` in `WallpaperHelper` (~50% bitmap RAM reduction).
  - Unregistered `FrameMetrics` listener when overlay goes invisible, cleared static `AppRepository.sCachedEntries` on theme reload, and eliminated `Handler` runnable leaks on `onDestroy()`.
  - Fixed `SettingsOverlay` confirmation dialog leaks and `Move` mode cleanup on activity stop.

---

## 4. Installed Add-ons (`4` Modules) — Features & Code Fixes

### 4.1. `com.lionlev.tv.capacity` (`addons/capacity-stats`)
- **Calm, Subtle Real-Time Hardware Dashboard (`StatsActivity.java`)**:
  - Refreshed every `1s` while open, with smooth `60fps` cubic-out animated progress bars (`BarView`) and color interpolation (`#56B886` soft green → `#D49B35` warm amber → `#E06C65` soft coral).
  - **Default Calm View**: Displays only **Memory** (`MB free`, `%`, `used/total MB · Swap MB`) and **Storage** (`GB free`, `%`, `used/total GB`) in spacious, low-contrast dark slate cards (`#151C24` on `#0E1318`).
  - **Advanced Toggles (`CPU: Off/On`, `GPU: Off/On`)**: Header pill toggles (off by default, persisted in `SharedPreferences("capacity_prefs")`) reveal the **Processor (CPU)** card (live MHz, governor, core count, load %) and **Graphics (GPU)** card (`Mali-450 MP`, live frame time `ms`, `FPS`, resolution & refresh rate) only when enabled. When off, CPU/GPU polling is completely paused.
  - **`Free Up RAM` Action**: Calls `content://com.gathin.chillradio.now/reap` (`am kill-all` + `ActivityManager.killBackgroundProcesses`) and reports exact `MB` reclaimed.
- **Audit Fixes**:
  - Replaced 4-pass `/proc/meminfo` scanning with a single-pass reader (`MemoryCapacity.readDetailedKb()`) using a thread-safe `char[2048]` buffer.
  - Added caller UID check in `NowProvider.query()`.

### 4.2. `com.gathin.chillradio` (`work/chill-radio`)
- **Features**:
  - Ambient soundscapes (Procedural Rain, Ocean Waves, Crackling Fire, Aurora Hum) + 6 live SomaFM internet radio stations (`Groove Salad`, `Drone Zone`, `Lush`, `Deep Space One`, `SYNTHWAVE`, `Chillhop`) + Digital Art Frame modes (`Orbital`, `Museum`).
  - Live Open-Meteo weather provider (`12.97, 77.59`) and `/reap` memory reaper endpoint (`com.gathin.tvhome`, `com.lionlev.tv.capacity`, and `com.android.shell` whitelisted).
  - Zero-polling `GlobalKey:V` logcat watcher for the physical **Mi button**.
- **Audit Fixes**:
  - Fixed `AudioTrack` native leak when a background load generation loses a race (`AmbientAudio.java`).
  - Eliminated per-frame `RadialGradient` allocations in `SceneView.drawFire()` and `drawOrbital()`.
  - Fixed `VisualizerView` `Handler` runnable leak in `onDetachedFromWindow()` and audio focus resume behavior after transient ducking/loss.

### 4.3. `com.gathin.btconnect` (`addons/bluetooth-connect`)
- **Features**:
  - One-click reconnect and status card for paired Bluetooth audio devices (A2DP + HEADSET reflection), showing battery level (`BluetoothDevice.getBatteryLevel()`) and connection state.
- **Audit Fixes**:
  - Fixed `ConnectActivity` race condition where launching with a new device action while a previous connection thread was finishing was ignored; added 5-second `BluetoothAdapter.getProfileProxy()` timeout and proper proxy cleanup.
  - Added caller package verification and battery level metadata to `CardProvider`.

### 4.4. `io.github.maxhayim.remoteaccess` (`addons/remote-access`)
- **Features**:
  - LAN-only HTTP remote control & text entry server (`http://192.168.1.50:8765`) with auto-stop timer.
- **Audit Fixes**:
  - Added CSRF/DNS-rebinding protection on `POST /action` (`Origin`/`Host` header verification + RFC1918 private LAN client IP check).
  - Replaced busy-wait socket read loop with `socket.setSoTimeout(5000)` and bounded worker pool (`Executors.newFixedThreadPool(8)`).
  - Fixed UTF-8 multi-byte clipboard paste truncation and shell-escaped `input text` fallback.
  - Added `onTaskRemoved()` cleanup so `ServerSocket` closes cleanly if swiped away.

---

## 5. Issues Encountered & How They Were Solved

| # | Issue Encountered | Root Cause | Resolution |
|---|---|---|---|
| **1** | **Holding Volume Up/Down on the remote only adjusted volume by 1 step instead of repeating** | On Xiaomi `dangalFHD` (Android 9), binding *any* `AccessibilityService` with `canRequestFilterKeyEvents="true"` (`capabilities=8`) causes Android's `AccessibilityInputFilter` to swallow repeated `KEYCODE_VOLUME_UP`/`DOWN` events. | Disabled `HomeKeyService` and `InputKeyService` (`enabled_accessibility_services=""`, `accessibility_enabled=0`), set `"key_filter": false` in config, and added a guard so the launcher never re-enables accessibility services. |
| **2** | **Mi Button (`KEYCODE_BUTTON_2`) stopped opening Home once `HomeKeyService` was disabled** | Without `HomeKeyService` intercepting raw keycodes, `KEYCODE_BUTTON_2` was only seen by Xiaomi's `GlobalKeyReceiver`, which tried to start the disabled stock PatchWall app and logged `V/GlobalKey: receiving KeyEvent.KEYCODE_BUTTON_2 start tvhome`. | Added a blocking `logcat -v brief -T 1 -s GlobalKey:V` reader thread in `work/chill-radio`'s `NowProvider` (granted `READ_LOGS` via ADB) that fires the Home intent to `com.gathin.tvhome/.MainActivity` in ~3 ms. |
| **3** | **HDMI 1/2/3 Switching Failed from Launcher Inputs Menu** | Earlier `ADB AppControl` usage had uninstalled `com.android.tv` for `user 0`, which `com.droidlogic.tvinput` requires to render passthrough HDMI inputs. | Restored `com.android.tv` from `/system` via `cmd package install-existing com.android.tv` and hid it from the app grid. |
| **4** | **Capacity Stats & Remote Access Module Cards Could Not Be Hidden in Module Cards Settings** | `MainActivity.discoverModuleCards()` skipped packages in `hidden_apps`, and `buildGridEntries()` treated packages with both a launcher activity and a module provider inconsistently when `module_card_visibility` was set to `hidden`. | Decoupled `discoverModuleCards()` from `hidden_apps` and ensured `getModuleCardVisibility(pkg) == VISIBILITY_HIDDEN` hides module cards cleanly while allowing them to remain in or out of the app grid independently. |
| **5** | **`adb push` Stalling on Large APKs (`>100 KB`)** | `dangalFHD`'s `adbd` over Wi-Fi drops/stalls large raw `SEND` chunks in `adb_shell`. | Used `tools/venv/bin/python tools/b64push.py <local> /data/local/tmp/app.apk` (chunked base64 over shell with MD5 verification) + `pm install -r`. |
| **6** | **Capacity Stats `Free Up RAM` Button Returned `0 MB` Freed** | `work/chill-radio`'s `NowProvider.callReap()` only whitelisted `com.gathin.tvhome`, rejecting calls from `com.lionlev.tv.capacity`. | Whitelisted `com.lionlev.tv.capacity` and `com.android.shell` in `work/chill-radio/.../NowProvider.java` and deployed both APKs. |
| **7** | **Multiple Keycodes in Single `input keyevent` Call Ignored on Android 9** | On SDK 28, `input keyevent KEY1 KEY2` inside a single command does not reliably dispatch sequential focus changes across views. | Chain separate commands with semicolons (`input keyevent KEY1; input keyevent KEY2`) when scripting UI tests via `tools/tv.py`. |

---

## 6. Quick Rebuild & Deploy Commands for Future Sessions

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export TV_HOST=192.168.1.50:5555

# 1. Build & deploy Launcher (com.gathin.tvhome)
(cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease -q)
tools/venv/bin/python tools/b64push.py launcher/app/build/outputs/apk/release/app-release.apk /data/local/tmp/launcher.apk
tools/tv.py 'pm install -r /data/local/tmp/launcher.apk && rm -f /data/local/tmp/launcher.apk'

# 2. Push Liquid Glass Theme
TV_HOST=192.168.1.50:5555 tools/theme.sh work/themes/liquid-glass

# 3. Build & deploy Capacity Stats (com.lionlev.tv.capacity)
(cd addons/capacity-stats && ./gradlew --no-daemon testReleaseUnitTest assembleRelease -q)
tools/venv/bin/python tools/b64push.py addons/capacity-stats/app/build/outputs/apk/release/app-release.apk /data/local/tmp/capacity.apk
tools/tv.py 'pm install -r /data/local/tmp/capacity.apk && rm -f /data/local/tmp/capacity.apk'

# 4. Build & deploy Chill Radio + Weather + Mi-Key Helper (com.gathin.chillradio in work/chill-radio)
launcher/gradlew --no-daemon -p work/chill-radio assembleRelease -q
tools/venv/bin/python tools/b64push.py work/chill-radio/app/build/outputs/apk/release/app-release.apk /data/local/tmp/chill.apk
tools/tv.py 'pm install -r /data/local/tmp/chill.apk && rm -f /data/local/tmp/chill.apk && pm grant com.gathin.chillradio android.permission.READ_LOGS'

# 5. Build & deploy Bluetooth Connect (com.gathin.btconnect)
(cd addons/bluetooth-connect && ./gradlew --no-daemon testReleaseUnitTest assembleRelease -q)
tools/venv/bin/python tools/b64push.py addons/bluetooth-connect/app/build/outputs/apk/release/app-release.apk /data/local/tmp/bt.apk
tools/tv.py 'pm install -r /data/local/tmp/bt.apk && rm -f /data/local/tmp/bt.apk'

# 6. Build & deploy Remote Access (io.github.maxhayim.remoteaccess)
(cd addons/remote-access && ./gradlew --no-daemon testReleaseUnitTest assembleRelease -q)
tools/venv/bin/python tools/b64push.py addons/remote-access/app/build/outputs/apk/release/app-release.apk /data/local/tmp/remote.apk
tools/tv.py 'pm install -r /data/local/tmp/remote.apk && rm -f /data/local/tmp/remote.apk'

# 7. Verify Accessibility Invariant (MUST output ACC=)
tools/tv.py 'echo ACC=$(settings get secure enabled_accessibility_services)'
```
