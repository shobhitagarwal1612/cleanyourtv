package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

/**
 * consume_volume_keys: intercepts Volume Up/Down/Mute before the firmware OSD and adjusts
 * AudioManager.STREAM_MUSIC without FLAG_SHOW_UI, plus remembers per-app volume across streaming apps.
 *
 * The state is per process, not per service: with HomeKeyService and InputKeyService both bound, each sees the
 * same press, and the second call restarts the same repeat instead of adding a second one.
 */
final class VolumeKeys {
    static final long REPEAT_DELAY_MS = 450;
    static final long REPEAT_INTERVAL_MS = 55;

    /** Created on first use, so the class loads in JVM tests that only call the pure methods. */
    private static Handler handler;
    private static Runnable step;
    private static Runnable rampRunnable;
    private static boolean ramping;
    private static String currentMediaPkg = "";

    private VolumeKeys() {
    }

    /** AudioManager direction for a volume key, or 0 for any other key. */
    static int direction(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return AudioManager.ADJUST_RAISE;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return AudioManager.ADJUST_LOWER;
        return 0;
    }

    /** Whether this event starts or ends a press; a repeated down event changes nothing. */
    static boolean changesPress(int action, int repeatCount) {
        return action != KeyEvent.ACTION_DOWN || repeatCount == 0;
    }

    /** Swallows a volume key (true) and steps STREAM_MUSIC silently. */
    static boolean handle(Context context, KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (!changesPress(event.getAction(), event.getRepeatCount())) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                cancelRamp();
                AudioManager audio = context.getSystemService(AudioManager.class);
                if (audio != null) {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                }
            }
            return true;
        }
        int direction = direction(code);
        if (direction == 0) return false;
        if (!changesPress(event.getAction(), event.getRepeatCount())) return true;
        stop();
        cancelRamp();
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return true;
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        stepOnce(context, audio, direction);
        step = new Runnable() {
            @Override
            public void run() {
                stepOnce(context, audio, direction);
                handler.postDelayed(this, REPEAT_INTERVAL_MS);
            }
        };
        handler.postDelayed(step, REPEAT_DELAY_MS);
        return true;
    }

    private static void stepOnce(Context context, AudioManager audio, int direction) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        } catch (SecurityException ignored) {
            audio.adjustVolume(direction, 0);
        }
        saveVolumeForApp(context, currentMediaPkg, audio);
    }

    static boolean isMediaApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return !"com.gathin.tvhome".equals(pkg)
                && !"com.gathin.btconnect".equals(pkg)
                && !"com.android.tv.settings".equals(pkg)
                && !"com.android.settings".equals(pkg)
                && !"com.android.systemui".equals(pkg)
                && !"com.xiaomi.mitv.settings".equals(pkg)
                && !"android".equals(pkg);
    }

    private static void saveVolumeForApp(Context context, String pkg, AudioManager audio) {
        if (ramping || !isMediaApp(pkg) || audio == null) return;
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (cur >= 0) {
            context.getSharedPreferences("app_volumes", Context.MODE_PRIVATE)
                    .edit()
                    .putInt(pkg, cur)
                    .apply();
        }
    }

    /**
     * Called when the foreground activity package changes or when an app is launched from Home.
     * Saves the outgoing streaming app's STREAM_MUSIC volume and smoothly ramps over 300 ms to the incoming
     * streaming app's remembered volume.
     */
    static void onAppChanged(Context context, String oldPkg, String newPkg) {
        if (newPkg == null || newPkg.isEmpty() || newPkg.equals(oldPkg)) return;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null || audio.isVolumeFixed()) return;
        if (handler == null) handler = new Handler(Looper.getMainLooper());

        String outgoing = (oldPkg != null && !oldPkg.isEmpty()) ? oldPkg : currentMediaPkg;
        if (isMediaApp(outgoing) && !outgoing.equals(newPkg) && !ramping) {
            saveVolumeForApp(context, outgoing, audio);
        }

        if (!isMediaApp(newPkg)) return;
        currentMediaPkg = newPkg;

        SharedPreferences prefs = context.getSharedPreferences("app_volumes", Context.MODE_PRIVATE);
        int target = prefs.getInt(newPkg, -1);
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (target < 0) {
            prefs.edit().putInt(newPkg, cur).apply();
            return;
        }
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        final int clampedTarget = Math.max(0, Math.min(max, target));
        if (clampedTarget == cur) return;

        cancelRamp();
        ramping = true;
        int diff = Math.abs(clampedTarget - cur);
        final long stepIntervalMs = Math.max(20L, 300L / diff);
        rampRunnable = new Runnable() {
            private int lastVol = -1;

            @Override
            public void run() {
                int nowVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (nowVol == clampedTarget || nowVol == lastVol) {
                    ramping = false;
                    rampRunnable = null;
                    return;
                }
                lastVol = nowVol;
                int nextVol = nowVol + (clampedTarget > nowVol ? 1 : -1);
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0);
                } catch (SecurityException ignored) {
                    ramping = false;
                    rampRunnable = null;
                    return;
                }
                if (nextVol != clampedTarget) {
                    handler.postDelayed(this, stepIntervalMs);
                } else {
                    ramping = false;
                    rampRunnable = null;
                }
            }
        };
        handler.post(rampRunnable);
    }

    /** Saves the last active streaming app's volume once when returning to the Home screen. */
    static void onHomeResumed(Context context) {
        String pkg = currentMediaPkg;
        currentMediaPkg = "";
        if (ramping || !isMediaApp(pkg)) return;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) {
            saveVolumeForApp(context, pkg, audio);
        }
    }

    private static void cancelRamp() {
        if (handler != null && rampRunnable != null) handler.removeCallbacks(rampRunnable);
        rampRunnable = null;
        ramping = false;
    }

    /** Ends a repeat in progress, e.g. when the service goes away while a key is held. */
    static void stop() {
        if (handler != null && step != null) handler.removeCallbacks(step);
        step = null;
    }
}
