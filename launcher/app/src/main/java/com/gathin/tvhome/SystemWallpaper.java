package com.gathin.tvhome;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.Log;

import java.io.File;
import java.util.concurrent.Executor;

/**
 * config.xml system_wallpaper: copies Home's picture (or its plain background colour) to the system wallpaper, as
 * the stock Google TV launcher does. The AOSP TV Settings panel asks for the wallpaper (windowShowWallpaper), so the
 * system hides Home behind it and shows the system wallpaper instead; without this that is black on a TV whose stock
 * launcher has been disabled. Written only when the picture changes, off the main thread.
 */
final class SystemWallpaper {
    private static final String TAG = "TvHome.SysWallpaper";
    private static final String PREFS = "system_wallpaper";
    private static final String APPLIED = "applied";

    private SystemWallpaper() {}

    static boolean enabled(Context context) {
        return context.getResources().getBoolean(R.bool.system_wallpaper);
    }

    /** picture null means Home shows only backgroundColor. dimColor is wallpaper_dim as an ARGB black, or 0. */
    static void sync(Context context, File picture, int backgroundColor, int dimColor, int width, int height,
                     Executor executor) {
        Context app = context.getApplicationContext();
        String key = picture == null ? "color:" + Integer.toHexString(backgroundColor)
                : picture.getPath() + ':' + picture.length() + ':' + picture.lastModified() + ':'
                        + Integer.toHexString(dimColor);
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (key.equals(prefs.getString(APPLIED, null))) return;
        try {
            executor.execute(() -> apply(app, prefs, key, picture, backgroundColor, dimColor, width, height));
        } catch (RuntimeException rejected) {
            // Home is closing; the next start tries again.
        }
    }

    private static void apply(Context app, SharedPreferences prefs, String key, File picture, int backgroundColor,
                              int dimColor, int width, int height) {
        Bitmap bitmap = null;
        try {
            bitmap = picture == null ? solid(backgroundColor) : decode(picture, dimColor, width, height);
            if (bitmap == null) return;
            WallpaperManager manager = WallpaperManager.getInstance(app);
            try {
                manager.suggestDesiredDimensions(width, height);
            } catch (RuntimeException hintsRefused) {
                // SET_WALLPAPER_HINTS missing on this firmware: the wallpaper is still set, cropped by the system.
            }
            manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM);
            prefs.edit().putString(APPLIED, key).apply();
        } catch (Exception | OutOfMemoryError failure) {
            Log.w(TAG, "Could not set the system wallpaper", failure);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static Bitmap solid(int color) {
        Bitmap bitmap = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color | 0xFF000000);
        return bitmap;
    }

    private static Bitmap decode(File file, int dimColor, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = WallpaperSize.sampleSize(options.outWidth, options.outHeight, width, height);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inMutable = dimColor != 0;
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
        if (decoded != null && dimColor != 0) new Canvas(decoded).drawColor(dimColor);
        return decoded;
    }
}
