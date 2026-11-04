package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The weather now as one line for the Clean Your TV home screen (its home_weather_app key): content://<package>.now/now
 * answers one row, column "text", e.g. "24° · Partly cloudy". It reads Open-Meteo only when Home asks and the
 * last reading is older than {@link Forecast#REFRESH_MS} (or {@link Forecast#RETRY_MS} after a failure), so nothing
 * runs in the background. Read-only and holds only what Home shows: no city name, no coordinates. An empty cursor
 * means no city is chosen yet, or no reading could be had.
 */
public final class NowProvider extends ContentProvider {
    static final String CACHE = "now";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(new String[]{"text"});
        Context context = getContext();
        if (context == null) return out;
        String text = line(context);
        if (!text.isEmpty()) out.addRow(new Object[]{text});
        return out;
    }

    /** Runs on a binder thread, so the network read may block (Home asks off its main thread). */
    private static synchronized String line(Context context) {
        SharedPreferences place = context.getSharedPreferences("place", Context.MODE_PRIVATE);
        if (!place.contains("name")) return "";
        SharedPreferences cache = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String key = place.getLong("lat", 0) + ":" + place.getLong("lon", 0);
        boolean samePlace = key.equals(cache.getString("place", ""));
        String text = samePlace ? cache.getString("text", "") : "";
        long age = now - cache.getLong("time", 0);
        long due = text.isEmpty() ? Forecast.RETRY_MS : Forecast.REFRESH_MS;
        if (samePlace && age >= 0 && age < due) return text;
        try {
            Forecast.Report r = Forecast.fetch(Double.longBitsToDouble(place.getLong("lat", 0)),
                    Double.longBitsToDouble(place.getLong("lon", 0)), Forecast.MIN_DAYS);
            store(context, key, format(context, r));
        } catch (Exception e) {
            // Offline: keep the last line for its place, and try again after RETRY_MS.
            cache.edit().putString("place", key).putString("text", text).putLong("time", now).apply();
        }
        return cache.getString("text", "");
    }

    /** WeatherActivity hands over each reading it shows, so Home matches the page without another request. */
    static void store(Context context, SharedPreferences place, Forecast.Report r) {
        store(context, place.getLong("lat", 0) + ":" + place.getLong("lon", 0), format(context, r));
    }

    private static void store(Context context, String key, String text) {
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().putString("place", key)
                .putString("text", text).putLong("time", System.currentTimeMillis()).commit();
        context.getContentResolver().notifyChange(Uri.parse("content://" + context.getPackageName() + ".now/now"), null);
    }

    private static String format(Context context, Forecast.Report r) {
        SharedPreferences options = context.getSharedPreferences("options", Context.MODE_PRIVATE);
        boolean fahrenheit = options.getBoolean("fahrenheit",
                "f".equalsIgnoreCase(context.getString(R.string.temperature_unit).trim()));
        return Forecast.degrees(r.tempC, fahrenheit) + "° · " + context.getString(Forecast.sky(r.code, r.day));
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
