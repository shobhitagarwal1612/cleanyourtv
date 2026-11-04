# Xiaomi Mi TV (192.168.1.50, MiTV-AXSO0 / dangalFHD) — Device Invariants & Rules

> **Read this file before running any command or modifying code for this TV (`192.168.1.50:5555`).**

## 1. Accessibility Key Helpers MUST Remain Disabled (`services:{}` empty)
- **Invariant**:
  - `settings get secure enabled_accessibility_services` MUST be empty (`""` / `null`).
  - `settings get secure accessibility_enabled` MUST be `0`.
  - `"key_filter": false` and `"consume_volume_keys": false` MUST remain set in [`work/themes/liquid-glass/config.json`](file:///Users/ashobhit/.gemini/jetski/scratch/cleanyourtv/work/themes/liquid-glass/config.json) and [`launcher/app/src/main/res/values/config.xml`](file:///Users/ashobhit/.gemini/jetski/scratch/cleanyourtv/launcher/app/src/main/res/values/config.xml).
- **Root Cause**:
  - On Xiaomi Mi TV (`dangalFHD`, Android 9 / SDK 28, Amlogic T962E), binding *any* `AccessibilityService` that declares `android:canRequestFilterKeyEvents="true"` (`CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS`, `capabilities=8`, such as `com.gathin.tvhome/.HomeKeyService` or `InputKeyService`) causes Android's `AccessibilityInputFilter` / `KeyboardInterceptor` pipeline to swallow repeated `KEYCODE_VOLUME_UP` and `KEYCODE_VOLUME_DOWN` events after a single step, breaking long-hold volume repeat on the Bluetooth remote.

## 2. How Remote Buttons Work Without `AccessibilityService`
- **Google Home Button**: Handled natively by Android as `Intent.CATEGORY_HOME` (`com.gathin.tvhome/.MainActivity`).
- **Mi Button (`KEYCODE_BUTTON_2` = 189 / scancode 192)**:
  - Xiaomi's system receiver `com.xiaomi.android.tvsetup.partnercustomizer/.GlobalKeyReceiver` intercepts `KEYCODE_BUTTON_2` and logs `V/GlobalKey: receiving KeyEvent.KEYCODE_BUTTON_2 start tvhome`.
  - `com.gathin.chillradio` ([`NowProvider.java`](file:///Users/ashobhit/.gemini/jetski/scratch/cleanyourtv/work/chill-radio/app/src/main/java/com/gathin/chillradio/NowProvider.java), granted `android.permission.READ_LOGS` via ADB) runs a lightweight zero-polling `logcat -v brief -T 1 -s GlobalKey:V` reader thread and fires `Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)` to `com.gathin.tvhome/.MainActivity` within ~3 ms.
  - Because `com.gathin.chillradio` is queried by the launcher as a module card provider, Android keeps its process alive while the TV is on, giving instant Mi-button response without any `AccessibilityService`.
- **Netflix & Prime Video Buttons**:
  - Handled natively by `com.xiaomi.android.tvsetup.partnercustomizer` (keep this package enabled).

## 3. Never Trigger Sleep via `Settings.Secure.sleep_timeout` (`GO_TO_SLEEP_REASON_TIMEOUT`)
- **Invariant**:
  - **NEVER** lower `Settings.Secure.sleep_timeout` (e.g., to `10000`) to force the TV to sleep. Keep `sleep_timeout` at `10800000` (3 hours) and `screensaver_enabled` at `1`.
- **Root Cause**:
  - The Xiaomi Mi TV remote is a **Bluetooth LE (BLE) remote** (`Xiaomi RC`). When the user presses the remote's physical Power button (`KEYCODE_POWER`), Xiaomi's `PhoneWindowManager` invokes `mitv.service` (`TvService`) and `com.droidlogic.ble` to program the Bluetooth controller's BLE wake-up filter before entering standby.
  - When AOSP `PowerManagerService` instead expires `Settings.Secure.sleep_timeout` (`GO_TO_SLEEP_REASON_TIMEOUT`), it bypasses Xiaomi's `KEYCODE_POWER` standby hook and powers down the SoC/Bluetooth without arming BLE wake-on-radio—making the remote's Power button completely unresponsive until the physical button on the TV chassis is pressed.
- **Safe Idle Screen-Off (`idle_blackout`)**:
  - After `home_idle_clock_minutes` (4 minutes) on Home, [`MainActivity.sleepOrIdleClock()`](file:///Users/ashobhit/.gemini/jetski/scratch/cleanyourtv/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L1331-L1334) launches [`ClockActivity`](file:///Users/ashobhit/.gemini/jetski/scratch/cleanyourtv/launcher/app/src/main/java/com/gathin/tvhome/ClockActivity.java#L29-L44) with `"idle_blackout": true` (`#000000` pure black view, `screenBrightness = 0.0f`, `FLAG_KEEP_SCREEN_ON`, zero audio/animation). Any remote key wakes Home in `<16 ms`, and pressing the remote's Power button still uses Xiaomi's proper BLE-wakeable standby path.

## 3. Pushing Files & Installing APKs Over ADB on `dangalFHD`
- **Issue**: Direct large binary `adb push` over the Python ADB client (`tools/tv.py --push`) can intermittently stall on `dangalFHD`'s `adbd` TCP socket when payloads exceed ~100 KB.
- **Rule**: Always push APKs and large files using [`tools/b64push.py`](file:///Users/ashobhit/.gemini/jetski/scratch/cleanyourtv/tools/b64push.py) via the repo virtualenv, followed by `pm install -r`:
  ```bash
  export TV_HOST=192.168.1.50:5555
  tools/venv/bin/python tools/b64push.py <local.apk> /data/local/tmp/app.apk
  tools/tv.py 'pm install -r /data/local/tmp/app.apk && rm -f /data/local/tmp/app.apk'
  ```

## 4. Build Environment (macOS)
- Always export JDK 17 and Android SDK before running Gradle in `launcher/`, `addons/*`, or `work/chill-radio`:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  ```
- Note: [`work/chill-radio`](file:///Users/ashobhit/StudioProjects/cleanyourtv/work/chill-radio) is a customized fork of `addons/chill-radio` with `INTERNET`, `READ_LOGS`, `KILL_BACKGROUND_PROCESSES`, Open-Meteo live weather, SomaFM live radio, and the `/reap` RAM optimizer endpoint. Build `work/chill-radio` using the launcher's Gradle wrapper (`launcher/gradlew -p work/chill-radio assembleRelease`).

## 5. Git Branch Separation & Upstream Sync Protocol
- **Never Commit or Push Without Explicit User Confirmation**:
  - **NEVER** run `git commit` or `git push` automatically after making or testing changes. Always build/test locally or on the TV first, show the results/screenshots to the user, and wait for explicit confirmation from the user before committing or uploading to GitHub.
- **Keep `main` Clean**: `main` tracks upstream `origin/main` (`Gathin23/cleanyourtv`) and is mirrored to `my-github/main` (`shobhitagarwal1612/cleanyourtv`). Never commit device-specific or custom changes directly to `main`.
- **Feature Branch (`xiaomi-mitv-customizations`)**: All custom changes to `launcher/`, `addons/`, and `work/` belong on `xiaomi-mitv-customizations`.
- **Cautious Merge Audit & TV Push Assessment**:
  - When merging `main` into `xiaomi-mitv-customizations`, always audit overlapping files (`git diff <before>..<after> -- launcher/ addons/ work/`) line-by-line so upstream changes never silently overwrite custom logic.
  - Always assess and state clearly whether the newly merged/committed changes require rebuilding and deploying an APK to the TV.

## 6. Frosted-Glass Menus, Dialogs & Focus Contrast Standards
- **Never Use Stock `AlertDialog.Builder`**:
  - Always use custom frosted-glass dialogs (`Dialog` inflating `R.layout.popup_inputs` with `@drawable/glass_panel` and `R.layout.item_input` rows, transparent window background, and `0.55f–0.60f` backdrop dim) in `MainActivity` and `LauncherSettingsActivity`.
- **Clear Background Focus Rings**:
  - Before calling `dialog.show()` on any modal dialog over `MainActivity` or `LauncherSettingsActivity`, clear focus on the underlying view (`prevFocus.clearFocus()`) and restore it (`prevFocus.requestFocus()`) in `dialog.setOnDismissListener(...)`.
- **Focus vs. Selection Contrast**:
  - Use solid white (`#F5FFFFFF`) with dark ink (`@drawable/pill_text` `#FF111111` for titles and `@drawable/pill_text_dim` `#D9111111` for right-aligned status labels) for `state_focused`, and emerald-tinted glass (`#334ADE80` fill, `#CC4ADE80` stroke) for `state_selected` segmented pills (`settings_choice_bg.xml`).
- **Unified Top-Bar Settings Gear**:
  - Single-clicking `btnSettings` on Home opens the frosted-glass `showSettingsMenu()` dropdown (`Launcher settings`, `TV system settings`, `Picture & sound`, `Edit dock`, `Reorder apps`, `Module cards`) with vector icons.

