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
    private static final long NEWS_REFRESH_MS = 3 * 60 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final String[] COLUMNS = {"text"};
    private static final String[] WHISPER_COLUMNS = {"kind", "kicker", "headline", "subline", "colour"};
    private static final String[] RSS_URLS = {
            "https://www.thehindu.com/news/national/feeder/default.rss",
            "https://feeds.bbci.co.uk/news/world/asia/india/rss.xml"
    };

    private static String openMeteoUrl(Context ctx) {
        String lat = ctx.getString(R.string.loc_lat).trim();
        String lon = ctx.getString(R.string.loc_lon).trim();
        if (lat.isEmpty() || lon.isEmpty()) return "";
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,precipitation_probability,weather_code"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&forecast_days=2&timezone=auto";
    }

    private static String airQualityUrl(Context ctx) {
        String lat = ctx.getString(R.string.loc_lat).trim();
        String lon = ctx.getString(R.string.loc_lon).trim();
        if (lat.isEmpty() || lon.isEmpty()) return "";
        return "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon
                + "&current=us_aqi&timezone=auto";
    }

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
        Context context = getContext();
        String path = uri == null ? null : uri.getPath();
        if ("/whisper".equals(path)) {
            MatrixCursor out = new MatrixCursor(WHISPER_COLUMNS);
            if (context == null || !isAllowedCaller(context)) return out;
            populateWhisper(context, out);
            return out;
        }
        MatrixCursor out = new MatrixCursor(COLUMNS);
        if (context == null || uri == null || !isAllowedCaller(context)) return out;
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

    private boolean isAllowedCaller(Context context) {
        String caller = getCallingPackage();
        return caller == null
                || "com.gathin.tvhome".equals(caller)
                || "com.lionlev.tv.capacity".equals(caller)
                || "com.android.shell".equals(caller)
                || context.getPackageName().equals(caller);
    }

    private static synchronized String fetchOrCached(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("open_meteo_cache", Context.MODE_PRIVATE);
        String cached = prefs.getString("text", "");
        long last = prefs.getLong("time", 0L);
        long now = System.currentTimeMillis();
        long due = cached.isEmpty() ? RETRY_MS : REFRESH_MS;
        if (last > 0L && now - last >= 0 && now - last < due) {
            return cached;
        }
        refreshWeatherAndAlert(context, prefs, now);
        return prefs.getString("text", cached);
    }

    private static void populateWhisper(Context context, MatrixCursor out) {
        SharedPreferences prefs = context.getSharedPreferences("open_meteo_cache", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastWeather = prefs.getLong("time", 0L);
        long weatherDue = prefs.getString("text", "").isEmpty() ? RETRY_MS : REFRESH_MS;
        if (lastWeather <= 0L || now - lastWeather < 0 || now - lastWeather >= weatherDue) {
            refreshWeatherAndAlert(context, prefs, now);
        }
        String alertKicker = prefs.getString("alert_kicker", "");
        String alertHeadline = prefs.getString("alert_headline", "");
        String alertSubline = prefs.getString("alert_subline", "");
        int alertColour = prefs.getInt("alert_colour", 0xFFF59E0B);
        if (!alertHeadline.isEmpty()) {
            out.addRow(new Object[]{"alert", alertKicker, alertHeadline, alertSubline, alertColour});
        }

        long lastNews = prefs.getLong("news_time", 0L);
        String newsHeadline = prefs.getString("news_headline", "");
        long newsDue = newsHeadline.isEmpty() ? RETRY_MS : NEWS_REFRESH_MS;
        if (lastNews <= 0L || now - lastNews < 0 || now - lastNews >= newsDue) {
            refreshTopHeadline(prefs, now);
            newsHeadline = prefs.getString("news_headline", "");
        }
        if (!newsHeadline.isEmpty()) {
            String newsKicker = prefs.getString("news_kicker", "DAILY BRIEF · INDIA & WORLD");
            String newsSubline = prefs.getString("news_subline", "Top story");
            out.addRow(new Object[]{"news", newsKicker, newsHeadline, newsSubline, 0xFF60A5FA});
        }
    }

    private static void refreshWeatherAndAlert(Context context, SharedPreferences prefs, long now) {
        String weatherUrl = openMeteoUrl(context);
        if (weatherUrl.isEmpty()) return;
        String shortArea = context.getString(R.string.loc_short_area).trim();
        String city = context.getString(R.string.loc_city).trim();
        String placeUpper = (shortArea.isEmpty() ? (city.isEmpty() ? "LOCAL" : city) : shortArea)
                .toUpperCase(java.util.Locale.US);
        String placeName = city.isEmpty() ? "today" : ("in " + city);
        try {
            int aqi = fetchAqi(context);
            JSONObject root = new JSONObject(httpGet(weatherUrl));
            JSONObject current = root.getJSONObject("current");
            int tempC = (int) Math.round(current.getDouble("temperature_2m"));
            int code = current.getInt("weather_code");
            boolean day = current.optInt("is_day", 1) == 1;
            String line = tempC + "° · " + skyLabel(code, day);
            if (aqi > 0) {
                line += " · AQI " + aqi;
            }

            // Evaluate severe weather / rain / heat / AQI advisories over the next 12 hours
            String alertKicker = "";
            String alertHeadline = "";
            String alertSubline = "";
            int alertColour = 0xFFF59E0B;

            int maxTemp = tempC;
            int minTemp = tempC;
            JSONObject daily = root.optJSONObject("daily");
            if (daily != null) {
                org.json.JSONArray maxArr = daily.optJSONArray("temperature_2m_max");
                org.json.JSONArray minArr = daily.optJSONArray("temperature_2m_min");
                if (maxArr != null && maxArr.length() > 0) maxTemp = (int) Math.round(maxArr.getDouble(0));
                if (minArr != null && minArr.length() > 0) minTemp = (int) Math.round(minArr.getDouble(0));
            }

            int worstCode = code;
            int maxProb = 0;
            JSONObject hourly = root.optJSONObject("hourly");
            if (hourly != null) {
                org.json.JSONArray probs = hourly.optJSONArray("precipitation_probability");
                org.json.JSONArray codes = hourly.optJSONArray("weather_code");
                int hourNow = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                if (probs != null && codes != null) {
                    int limit = Math.min(probs.length(), hourNow + 12);
                    for (int i = Math.max(0, hourNow); i < limit; i++) {
                        int p = probs.optInt(i, 0);
                        int c = codes.optInt(i, 0);
                        if (p > maxProb) maxProb = p;
                        if (c > worstCode) worstCode = c;
                    }
                }
            }

            if (worstCode >= 95) {
                alertKicker = placeUpper + " · STORM ADVISORY";
                alertHeadline = "Thunderstorms Expected Today";
                alertSubline = Math.max(maxProb, 70) + "% rain chance · High " + maxTemp + "° Low " + minTemp + "°";
                alertColour = 0xFFEF4444;
            } else if (worstCode >= 63 || maxProb >= 65) {
                alertKicker = placeUpper + " · RAIN ADVISORY";
                alertHeadline = worstCode >= 65 ? "Heavy Showers Expected Ahead" : "Rain Showers Expected Today";
                alertSubline = maxProb + "% rain chance " + placeName + " · High " + maxTemp + "° Low " + minTemp + "°";
                alertColour = 0xFFF59E0B;
            } else if (aqi >= 150) {
                alertKicker = placeUpper + " · AIR QUALITY ALERT";
                alertHeadline = "Unhealthy Air Quality · AQI " + aqi;
                alertSubline = "Limit prolonged outdoor exertion " + placeName;
                alertColour = aqi >= 200 ? 0xFFEF4444 : 0xFFF59E0B;
            } else if (maxTemp >= 36) {
                alertKicker = placeUpper + " · HEAT ADVISORY";
                alertHeadline = "Warm Afternoon Ahead · High " + maxTemp + "°C";
                alertSubline = "Peak warmth between 1 PM and 4 PM · Low " + minTemp + "°";
                alertColour = 0xFFF59E0B;
            }

            prefs.edit()
                    .putString("text", line)
                    .putString("alert_kicker", alertKicker)
                    .putString("alert_headline", alertHeadline)
                    .putString("alert_subline", alertSubline)
                    .putInt("alert_colour", alertColour)
                    .putLong("time", now)
                    .apply();
        } catch (Exception e) {
            prefs.edit().putLong("time", now).apply();
        }
    }

    private static int fetchAqi(Context context) {
        String url = airQualityUrl(context);
        if (url.isEmpty()) return 0;
        try {
            JSONObject root = new JSONObject(httpGet(url));
            JSONObject current = root.optJSONObject("current");
            if (current != null && !current.isNull("us_aqi")) {
                return current.optInt("us_aqi", 0);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static void refreshTopHeadline(SharedPreferences prefs, long now) {
        for (int i = 0; i < RSS_URLS.length; i++) {
            try {
                String xml = httpGet(RSS_URLS[i]);
                String title = extractFirstItemTitle(xml);
                if (!title.isEmpty()) {
                    String source = i == 0 ? "The Hindu" : "BBC News";
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
                    String subline = source + " · Updated " + sdf.format(new java.util.Date(now));
                    prefs.edit()
                            .putString("news_kicker", "DAILY BRIEF · INDIA & WORLD")
                            .putString("news_headline", title)
                            .putString("news_subline", subline)
                            .putLong("news_time", now)
                            .apply();
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putLong("news_time", now).apply();
    }

    private static String extractFirstItemTitle(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        int itemStart = xml.indexOf("<item");
        if (itemStart < 0) return "";
        int titleStart = xml.indexOf("<title", itemStart);
        if (titleStart < 0) return "";
        int tagClose = xml.indexOf('>', titleStart);
        if (tagClose < 0) return "";
        int titleEnd = xml.indexOf("</title>", tagClose);
        if (titleEnd < 0) return "";
        String raw = xml.substring(tagClose + 1, titleEnd).trim();
        if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) {
            raw = raw.substring(9, raw.length() - 3).trim();
        }
        raw = raw.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lsquo;", "'")
                .replace("&rsquo;", "'")
                .replace("&ldquo;", "\"")
                .replace("&rdquo;", "\"")
                .replace("&ndash;", "–")
                .replace("&mdash;", "—")
                .replaceAll("\\s+", " ")
                .trim();
        // Strip noisy prefixes like "LIVE | ", "WATCH: ", "Explained | "
        raw = raw.replaceFirst("^(?i)(LIVE|WATCH|VIDEO|OPINION|EXPLAINED|BREAKING)\\s*[:|\\-–—]\\s*", "");
        if (raw.length() > 58) {
            int cut = raw.lastIndexOf(' ', 55);
            raw = (cut > 30 ? raw.substring(0, cut) : raw.substring(0, 55)) + "…";
        }
        return raw;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "CleanYourTV/1.0");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
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

