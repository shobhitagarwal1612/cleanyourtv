package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * config.xml home_weather_app: one line under the date with the weather now ("24° · Partly cloudy"), read from that
 * app's content://<package>.now/now (the Weather add-on's NowProvider). The launcher never goes online: the app does
 * the fetching and caching, and answers only while Home asks, on Home's return and every few minutes while it is on
 * screen. The provider must belong to the named package; an empty answer hides the line.
 */
final class HomeWeather {
    static final int PRECIP_NONE = 0;
    static final int PRECIP_RAIN = 1;
    static final int PRECIP_SNOW = 2;

    private static final long TICK_GAP_MS = 5 * 60_000L;
    private static final long RESUME_GAP_MS = 60_000L;
    private static final int MAX_CHARS = 40;

    private static volatile String cachedLine = "";
    private static volatile int cachedPrecip = PRECIP_NONE;

    private final Context app;
    private final TextView view;
    private final String pkg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long asked = -TICK_GAP_MS;
    private boolean busy;

    private HomeWeather(Context context, TextView view, String pkg) {
        this.app = context.getApplicationContext();
        this.view = view;
        this.pkg = pkg;
    }

    /** Null while home_weather_app is empty. */
    static HomeWeather create(Context context, TextView view) {
        String pkg = context.getString(R.string.home_weather_app).trim();
        return pkg.isEmpty() || view == null ? null : new HomeWeather(context, view, pkg);
    }

    static String currentLine() {
        return cachedLine;
    }

    static int outdoorPrecipitation() {
        return cachedPrecip;
    }

    /** Refreshes cached outdoor weather in the background for ClockActivity / ClockDream. */
    static void refreshForClock(Context context) {
        final Context appCtx = context.getApplicationContext();
        final String pkg = appCtx.getString(R.string.home_weather_app).trim();
        if (pkg.isEmpty()) return;
        new Thread(() -> {
            String line = readFromProvider(appCtx, pkg);
            if (!line.isEmpty()) {
                cachedLine = line;
                cachedPrecip = parsePrecip(line);
            }
        }).start();
    }

    static int parsePrecip(String line) {
        if (line == null || line.isEmpty()) return PRECIP_NONE;
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("snow") || lower.contains("sleet") || lower.contains("flurr") || lower.contains("blizzard")) {
            return PRECIP_SNOW;
        }
        if (lower.contains("rain") || lower.contains("drizzle") || lower.contains("shower") || lower.contains("thunder")
                || lower.contains("storm")) {
            return PRECIP_RAIN;
        }
        return PRECIP_NONE;
    }

    /** Home came back: ask again unless the last answer is under a minute old. */
    void onResume() {
        ask(RESUME_GAP_MS);
    }

    /** Home's minute tick: ask every few minutes while Home stays on screen. */
    void onTick() {
        ask(TICK_GAP_MS);
    }

    void close() {
        io.shutdownNow();
    }

    private void ask(long gap) {
        long now = SystemClock.elapsedRealtime();
        if (busy || now - asked < gap) return;
        asked = now;
        busy = true;
        try {
            io.execute(() -> {
                String line = read();
                if (!line.isEmpty()) {
                    cachedLine = line;
                    cachedPrecip = parsePrecip(line);
                }
                main.post(() -> {
                    busy = false;
                    view.setText(line);
                    view.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
                });
            });
        } catch (RuntimeException closed) {
            busy = false;
        }
    }

    private String read() {
        return readFromProvider(app, pkg);
    }

    private static String readFromProvider(Context app, String pkg) {
        String authority = pkg + ".now";
        try {
            ProviderInfo info = app.getPackageManager().resolveContentProvider(authority, 0);
            if (info == null || !pkg.equals(info.packageName)) return "";
            try (Cursor c = app.getContentResolver().query(Uri.parse("content://" + authority + "/now"),
                    new String[]{"text"}, null, null, null)) {
                if (c == null || !c.moveToFirst()) return "";
                return clean(c.getString(0));
            }
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** One short line of printable text; anything else is dropped. */
    static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < MAX_CHARS; i++) {
            char ch = text.charAt(i);
            if (!Character.isISOControl(ch)) out.append(ch);
        }
        return out.toString().trim();
    }
}
