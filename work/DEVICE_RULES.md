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
- Note: [`work/chill-radio`](file:///Users/ashobhit/.gemini/jetski/scratch/cleanyourtv/work/chill-radio) is a customized fork of `addons/chill-radio` with `INTERNET`, `READ_LOGS`, `KILL_BACKGROUND_PROCESSES`, Open-Meteo live weather, SomaFM live radio, and the `/reap` RAM optimizer endpoint. Build `work/chill-radio` using the launcher's Gradle wrapper (`launcher/gradlew -p work/chill-radio assembleRelease`).
