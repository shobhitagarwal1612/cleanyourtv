package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The cinematic shelf's backdrop: an app's artwork reduced to 48×27 pixels, saturated and smoothed,
 * then stretched over the screen by its ImageView. Bilinear filtering of an image that small is a
 * blur that costs one tiny texture; a real blur would mean RenderScript or a shader pass per frame,
 * which a 1 GB TV cannot spare.
 */
final class HeroArt {
    static final int W = 48, H = 27;
    private static final float SATURATION = 1.7f;
    private static final int[] BUF_A = new int[W * H];
    private static final int[] BUF_B = new int[W * H];

    private HeroArt() {
    }

    /** The softened artwork, about 5 KB; null when there is nothing to draw. */
    static Bitmap from(Drawable live) {
        if (live == null) return null;
        // Draw a private copy: changing the bounds of the drawable a tile shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap big = Bitmap.createBitmap(W * 4, H * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        c.drawColor(Color.BLACK);
        d.setBounds(0, 0, big.getWidth(), big.getHeight());
        d.draw(c);
        d.setBounds(saved);
        // Halve twice: each filtered halving averages 2×2 pixels, where one 4× step would skip most of them.
        Bitmap half = Bitmap.createScaledBitmap(big, W * 2, H * 2, true);
        if (half != big) big.recycle();
        Bitmap small = Bitmap.createScaledBitmap(half, W, H, true);
        if (small != half) half.recycle();

        int[] px = BUF_A;
        int[] scratch = BUF_B;
        small.getPixels(px, 0, W, 0, 0, W, H);
        saturate(px);
        // Three box passes approximate a gaussian, so the stretched image shows no seams between pixels.
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, scratch, W, H, 2, true);
            boxBlur(scratch, px, W, H, 2, false);
        }
        dither(px, W, H);
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        out.setPixels(px, 0, W, 0, 0, W, H);
        return out;
    }

    private static final int[] BAYER_4X4 = {
         0,  8,  2, 10,
        12,  4, 14,  6,
         3, 11,  1,  9,
        15,  7, 13,  5
    };

    /** Ordered 4x4 Bayer dither (±2 LSB) to prevent 8-bit Mach banding when stretched 40x across 1080p. */
    private static void dither(int[] px, int w, int h) {
        for (int y = 0; y < h; y++) {
            int rowOff = (y & 3) << 2;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = px[i];
                float delta = (BAYER_4X4[rowOff | (x & 3)] - 7.5f) * 0.25f;
                px[i] = Color.rgb(
                        clamp(Color.red(p) + delta),
                        clamp(Color.green(p) + delta),
                        clamp(Color.blue(p) + delta));
            }
        }
    }

    private static void saturate(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = Color.rgb(clamp(lum + (r - lum) * SATURATION), clamp(lum + (g - lum) * SATURATION),
                    clamp(lum + (b - lum) * SATURATION));
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** One box blur pass along rows (horizontal) or columns, edges clamped. */
    private static void boxBlur(int[] src, int[] dst, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int p = horizontal ? src[line * w + j] : src[j * w + line];
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                }
                dst[horizontal ? line * w + i : i * w + line] = Color.rgb(r / span, g / span, b / span);
            }
        }
    }
}
