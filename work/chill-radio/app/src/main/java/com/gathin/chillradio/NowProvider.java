package com.gathin.chillradio;

import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fetches a 1 KB keyless forecast from open-meteo.com once an hour when Home wakes,
 * serving "25° · Mostly Clear" (or "68° · Light Rain") to HomeWeather via content://com.gathin.chillradio.now/now.
 */
public final class NowProvider extends ContentProvider {
    private static final long REFRESH_MS = 60 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final String[] COLUMNS = {"text"};
    private static final String URL_OPEN_METEO =
            "https://api.open-meteo.com/v1/forecast?latitude=12.97&longitude=77.59&current=temperature_2m,weather_code,is_day&timezone=auto";

    private static volatile boolean watcherStarted;

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null && !watcherStarted) {
            watcherStarted = true;
            startMiKeyWatcher(ctx.getApplicationContext());
        }
        return true;
    }

    private static void startMiKeyWatcher(Context appCtx) {
        Thread t = new Thread(() -> {
            long lastLaunchMs = 0L;
            while (true) {
                Process proc = null;
                try {
                    try {
                        new ProcessBuilder("pkill", "-f", "logcat.*GlobalKey").start().waitFor();
                    } catch (Throwable ignored) {
                    }
                    long procStartMs = SystemClock.elapsedRealtime();
                    proc = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "-s", "GlobalKey:V")
                            .redirectErrorStream(true)
                            .start();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.contains("start tvhome")) {
                                long nowMs = SystemClock.elapsedRealtime();
                                // Ignore historical line flushed by -T 1 on startup and debounce rapid repeats
                                if (nowMs - procStartMs >= 400L && nowMs - lastLaunchMs >= 300L) {
                                    lastLaunchMs = nowMs;
                                    try {
                                        Intent home = new Intent(Intent.ACTION_MAIN)
                                                .addCategory(Intent.CATEGORY_HOME)
                                                .setPackage("com.gathin.tvhome")
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                        appCtx.startActivity(home);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (proc != null) {
                        try {
                            proc.destroyForcibly();
                            proc.waitFor();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "mi-key-watcher");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        Context context = getContext();
        if (context == null || uri == null) return out;
        String caller = getCallingPackage();
        if (caller != null
                && !"com.gathin.tvhome".equals(caller)
                && !"com.lionlev.tv.capacity".equals(caller)
                && !"com.android.shell".equals(caller)
                && !context.getPackageName().equals(caller)) {
            return out;
        }
        String path = uri.getPath();
        if ("/reap".equals(path)) {
            String pkgs = uri.getQueryParameter("pkgs");
            if (pkgs != null && !pkgs.isEmpty()) {
                ActivityManager am = context.getSystemService(ActivityManager.class);
                if (am != null) {
                    for (String pkg : pkgs.split(",")) {
                        String p = pkg.trim();
                        if (!p.isEmpty()
                                && !"com.gathin.tvhome".equals(p)
                                && !"com.gathin.chillradio".equals(p)) {
                            try {
                                am.killBackgroundProcesses(p);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
            return out;
        }
        if (path == null || path.isEmpty() || "/now".equals(path)) {
            String text = fetchOrCached(context);
            if (!text.isEmpty()) out.addRow(new Object[]{text});
        }
        return out;
    }

    private static synchronized String fetchOrCached(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("open_meteo_blr", Context.MODE_PRIVATE);
        String cached = prefs.getString("text", "");
        long last = prefs.getLong("time", 0L);
        long now = System.currentTimeMillis();
        long due = cached.isEmpty() ? RETRY_MS : REFRESH_MS;
        if (last > 0L && now - last >= 0 && now - last < due) {
            return cached;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(URL_OPEN_METEO).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                JSONObject current = root.getJSONObject("current");
                int tempC = (int) Math.round(current.getDouble("temperature_2m"));
                int code = current.getInt("weather_code");
                boolean day = current.optInt("is_day", 1) == 1;
                String line = tempC + "° · " + skyLabel(code, day);
                prefs.edit().putString("text", line).putLong("time", now).apply();
                return line;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            prefs.edit().putLong("time", now).apply();
            return cached;
        }
    }

    private static String skyLabel(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly Sunny" : "Mostly Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing Drizzle";
            case 61: return "Light Rain";
            case 63: return "Rain";
            case 65: return "Heavy Rain";
            case 66: case 67: return "Freezing Rain";
            case 71: return "Light Snow";
            case 73: return "Snow";
            case 75: return "Heavy Snow";
            case 77: return "Snow Grains";
            case 80: case 81: case 82: return "Rain Showers";
            case 85: case 86: return "Snow Showers";
            default: return code >= 95 ? "Thunderstorm" : "Cloudy";
        }
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.weather.now";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}

