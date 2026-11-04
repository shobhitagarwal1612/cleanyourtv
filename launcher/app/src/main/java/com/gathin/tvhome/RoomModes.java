package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * One-Tap "Room Modes" preset manager:
 * - MODE_EVERYDAY (0): normal wallpaper brightness, full dock & grid, standard color temperature.
 * - MODE_MOVIE_NIGHT (1): dims wallpaper, activates 2700K Night Warmth + Extra Dim overlay,
 *   auto-connects paired Bluetooth headphones if powered on, and hides noisy video-feed apps
 *   (YouTube / SmartTube) from the dock.
 * - MODE_MUSIC_LOUNGE (2): launches background radio/music stream and opens ClockActivity's
 *   33 1/3 RPM Vinyl Record turntable display.
 */
final class RoomModes {
    static final int MODE_EVERYDAY = 0;
    static final int MODE_MOVIE_NIGHT = 1;
    static final int MODE_MUSIC_LOUNGE = 2;

    /** Noisy video-feed apps hidden during Movie Night so only cinema streaming apps remain in the dock. */
    private static final Set<String> NOISY_FEED_APPS = new HashSet<>(Arrays.asList(
            "com.google.android.youtube.tv",
            "org.smarttube.stable"
    ));

    private static int currentMode = -1;
    private static View activeHomeOverlay;

    private RoomModes() {
    }

    static int getMode(Context context) {
        if (currentMode < 0 && context != null) {
            currentMode = context.getSharedPreferences("room_modes", Context.MODE_PRIVATE)
                    .getInt("mode", MODE_EVERYDAY);
        }
        return currentMode < 0 ? MODE_EVERYDAY : currentMode;
    }

    static int getMode() {
        return currentMode < 0 ? MODE_EVERYDAY : currentMode;
    }

    static boolean isMovieNight() {
        return getMode() == MODE_MOVIE_NIGHT;
    }

    static Set<String> effectiveHiddenApps(java.util.Collection<String> baseHidden) {
        if (getMode() != MODE_MOVIE_NIGHT) {
            return (baseHidden instanceof Set) ? (Set<String>) baseHidden : new HashSet<>(baseHidden);
        }
        Set<String> out = new HashSet<>(baseHidden);
        out.addAll(NOISY_FEED_APPS);
        return out;
    }

    /** Applies or removes the 2700K Night Warmth + Extra Dim cinema filter. */
    static void applyWarmthOverlay(Activity homeActivity, ViewGroup homeRoot) {
        boolean enable = (getMode(homeActivity) == MODE_MOVIE_NIGHT);
        if (!enable) {
            removeOverlays();
            return;
        }
        if (homeRoot != null && (activeHomeOverlay == null || activeHomeOverlay.getParent() != homeRoot)) {
            removeOverlays();
            WarmthFilterView filter = new WarmthFilterView(homeActivity);
            filter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            homeRoot.addView(filter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            filter.setAlpha(0f);
            filter.animate().alpha(1f).setDuration(350).start();
            activeHomeOverlay = filter;
        }
    }

    static void setMode(Context context, int mode) {
        currentMode = mode;
        if (context != null) {
            context.getSharedPreferences("room_modes", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("mode", mode)
                    .apply();
        }
    }

    static void setMode(int mode) {
        currentMode = mode;
    }

    /** Triggers one-tap Bluetooth headphone auto-connect in com.gathin.btconnect when entering Movie Night. */
    static void autoConnectHeadphones(Context context, Runnable onDone) {
        new Thread(() -> {
            try (Cursor ignored = context.getContentResolver().query(
                    Uri.parse("content://com.gathin.btconnect.status/connect"), null, null, null, null)) {
            } catch (Exception ignored) {
            }
            if (onDone != null) onDone.run();
        }).start();
    }

    static void removeOverlays() {
        if (activeHomeOverlay != null && activeHomeOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) activeHomeOverlay.getParent()).removeView(activeHomeOverlay);
        }
        activeHomeOverlay = null;
    }

    /**
     * Non-interactive 2700K Night Warmth + Extra Dim cinema layer:
     * - #4C050301: cinema Extra Dim (reduces harsh backlight glare)
     * - #2CFF881C: 2700K warm amber shift
     */
    private static final class WarmthFilterView extends View {
        WarmthFilterView(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(0x4C050301);
            canvas.drawColor(0x2CFF881C);
        }
    }
}
