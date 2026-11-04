package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Animated ambient canvas for Chill Mode across 10 dedicated screens:
 * - Screens 0..3: Procedural Nature scenes (Marin Sunset, Arctic Aurora, Midnight Rain, Ember Hearth).
 * - Screens 4..7: Dedicated Live Radio FM screens, each displaying a random cool 1080p background image.
 * - Screen 8: ISS Orbital View (Earth at Night from low Earth orbit with twinkling starlight).
 * - Screen 9: The Frame Gallery (Dark Museum Masterpiece oil painting in silent gallery mode).
 */
final class ChillView extends View {

    private static final String[] SCREEN_NAMES = {
            "Marin Sunset · Ocean Waves",
            "Arctic Aurora · Night Wind",
            "Midnight Rain · Cozy Downpour",
            "Ember Hearth · Crackling Fire",
            "Groove Salad FM · Chill Beats",
            "Drone Zone FM · Ambient Space",
            "Lush Lo-Fi FM · Dreamy Vocals",
            "Deep Space FM · Cosmic Drift",
            "ISS Orbital View · Earth at Night",
            "The Frame Gallery · Nocturne in Gold"
    };

    private static final int N = 64;
    private final float[] px = new float[N];
    private final float[] py = new float[N];
    private final float[] vx = new float[N];
    private final float[] vy = new float[N];
    private final float[] pr = new float[N];
    private final float[] pPhase = new float[N];

    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final RectF dstRect = new RectF();
    private final Path wavePath = new Path();
    private final Random rng = new Random(0x5EEDL);
    private final float density;
    private final int screenW, screenH;

    private final List<File> radioImages = new ArrayList<>();
    private File orbitalFile;
    private File museumFile;
    private final int[] stationImageIdx = new int[4];
    private Bitmap cachedBitmap;
    private String cachedBitmapPath;
    private String loadingBitmapPath;
    private final ExecutorService decodeExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chill-decode");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.setDaemon(true);
        return t;
    });

    private int mood;
    private boolean muted = false;
    private boolean active = false;
    private boolean handledDown = false;
    private long startMs;
    private long hudShownAtMs;
    private String cachedHudTitle = "";
    private String cachedHudSub = "";
    private float cachedPillWidth;
    private int cachedW, cachedH, cachedMood = -1;
    private Shader moodBgShader;
    private Shader horizonGlowShader;
    private Shader hearthGlowShader;
    private Shader radioScrimShader;

    ChillView(Context context, boolean radioAvailable) {
        super(context);
        this.mood = radioAvailable ? ChillAudio.MOOD_RADIO_GROOVE : ChillAudio.MOOD_OCEAN;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = dm.density;
        screenW = Math.max(1280, dm.widthPixels);
        screenH = Math.max(720, dm.heightPixels);
        for (int i = 0; i < N; i++) {
            px[i] = rng.nextFloat();
            py[i] = rng.nextFloat();
            vx[i] = (rng.nextFloat() - 0.5f) * 0.0008f;
            vy[i] = 0.0006f + rng.nextFloat() * 0.0018f;
            pr[i] = (1.2f + rng.nextFloat() * 2.6f) * density;
            pPhase[i] = rng.nextFloat() * 6.28f;
        }
        pillRim.setStyle(Paint.Style.STROKE);
        pillRim.setStrokeWidth(density);
        hudTitle.setColor(0xF2FFFFFF);
        hudTitle.setTextSize(15f * density);
        hudTitle.setTextAlign(Paint.Align.CENTER);
        hudTitle.setFakeBoldText(true);
        hudSub.setColor(0x99FFFFFF);
        hudSub.setTextSize(11.5f * density);
        hudSub.setTextAlign(Paint.Align.CENTER);

        loadAndShuffleRadioImages(context);
    }

    private void loadAndShuffleRadioImages(Context context) {
        File dir = context.getExternalFilesDir("chill");
        if (dir != null && dir.isDirectory()) {
            File orb = new File(dir, "frame_orbital.jpg");
            if (orb.isFile()) orbitalFile = orb;
            File mus = new File(dir, "frame_museum.jpg");
            if (mus.isFile()) museumFile = mus;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (f.isFile() && name.startsWith("radio_") && name.endsWith(".jpg")) {
                        radioImages.add(f);
                    }
                }
            }
        }
        if (!radioImages.isEmpty()) {
            Collections.shuffle(radioImages, new Random(SystemClock.uptimeMillis()));
            for (int i = 0; i < 4; i++) {
                stationImageIdx[i] = i % radioImages.size();
            }
        }
    }

    void start() {
        active = true;
        startMs = SystemClock.uptimeMillis();
        requestBackdropForCurrentMood();
        showHud();
        postInvalidateOnAnimation();
    }

    void stop() {
        active = false;
        loadingBitmapPath = null;
        if (cachedBitmap != null) {
            cachedBitmap.recycle();
            cachedBitmap = null;
            cachedBitmapPath = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        decodeExec.shutdownNow();
        super.onDetachedFromWindow();
    }

    int getMood() {
        return mood;
    }

    boolean isVinylLounge() {
        return vinylLounge;
    }

    int cycleMood(int delta) {
        int len = SCREEN_NAMES.length;
        mood = (mood + delta + len) % len;
        cachedMood = -1;
        if (ChillAudio.isRadioMood(mood) || ChillAudio.isSilentFrameMood(mood)) {
            soundscape = ChillAudio.SOUNDSCAPE_OFF;
        }
        requestBackdropForCurrentMood();
        showHud();
        invalidate();
        return mood;
    }

    void shuffleCurrentRadioImage(int delta) {
        if (ChillAudio.isRadioMood(mood) && !radioImages.isEmpty()) {
            int st = mood - ChillAudio.MOOD_RADIO_GROOVE;
            int n = radioImages.size();
            stationImageIdx[st] = (stationImageIdx[st] + delta + n) % n;
            requestBackdropForCurrentMood();
            showHud();
            invalidate();
        }
    }

    private int soundscape = ChillAudio.SOUNDSCAPE_OFF;

    void setMuted(boolean isMuted) {
        muted = isMuted;
        showHud();
        invalidate();
    }

    void setSoundscape(int sc) {
        soundscape = sc;
        if (sc != ChillAudio.SOUNDSCAPE_OFF) muted = false;
        showHud();
        invalidate();
    }

    void showHud() {
        hudShownAtMs = SystemClock.uptimeMillis();
        rebuildHudText();
    }

    private void rebuildHudText() {
        cachedHudTitle = "◀   " + SCREEN_NAMES[mood] + "   ▶";
        boolean isRadio = ChillAudio.isRadioMood(mood);
        String scLabel = ChillAudio.soundscapeLabel(soundscape);
        String modeLabel;
        if (!scLabel.isEmpty()) {
            modeLabel = muted ? "Muted" : scLabel;
        } else if (mood == ChillAudio.MOOD_FRAME_MUSEUM) {
            modeLabel = "Silent Art Frame";
        } else if (mood == ChillAudio.MOOD_FRAME_ORBITAL) {
            modeLabel = muted ? "Muted" : "Orbital Ambient";
        } else {
            modeLabel = muted ? "Muted" : (isRadio ? "Live Radio FM" : "Nature Audio");
        }
        String weather = HomeWeather.currentLine();
        String weatherPrefix = weather.isEmpty() ? "" : (weather + "  ·  ");
        String extra = isRadio ? "  ·  Up/Down: Art" : "";
        cachedHudSub = weatherPrefix + modeLabel + "  ·  OK: Mute" + extra + "  ·  Play/Menu: Sound";
        float tw = hudTitle.measureText(cachedHudTitle);
        float sw = hudSub.measureText(cachedHudSub);
        cachedPillWidth = Math.max(480f * density, Math.min(screenW - 80f * density, Math.max(tw, sw) + 56f * density));
    }

    /** Shared key handler used by both ClockActivity and ClockDream. */
    boolean handleKey(KeyEvent event, ChillAudio audio, ClockScene scene, Runnable onExit) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int newMood = cycleMood(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                audio.setMood(newMood);
                soundscape = audio.getSoundscape();
                rebuildHudText();
                if (scene != null) scene.setAvoidRightSide(vinylLounge && ChillAudio.isRadioMood(newMood));
                handledDown = true;
                return true;
            }
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MENU) {
                setSoundscape(audio.cycleSoundscape());
                handledDown = true;
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                setMuted(audio.toggleMute());
                handledDown = true;
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (ChillAudio.isRadioMood(mood)) {
                    shuffleCurrentRadioImage(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                } else {
                    setSoundscape(audio.cycleSoundscape());
                }
                handledDown = true;
                return true;
            }
            handledDown = false;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (handledDown) {
                handledDown = false;
                return true;
            }
            onExit.run();
        }
        return true;
    }

    private File targetFileForMood() {
        if (ChillAudio.isRadioMood(mood)) {
            if (radioImages.isEmpty()) return null;
            int idx = stationImageIdx[(mood - ChillAudio.MOOD_RADIO_GROOVE) % 4] % radioImages.size();
            return radioImages.get(idx);
        }
        if (mood == ChillAudio.MOOD_FRAME_ORBITAL) return orbitalFile;
        if (mood == ChillAudio.MOOD_FRAME_MUSEUM) return museumFile;
        return null;
    }

    private void requestBackdropForCurrentMood() {
        File file = targetFileForMood();
        if (file == null || !file.isFile()) return;
        String path = file.getAbsolutePath();
        if (path.equals(cachedBitmapPath) && cachedBitmap != null && !cachedBitmap.isRecycled()) return;
        if (path.equals(loadingBitmapPath)) return;
        loadingBitmapPath = path;
        decodeExec.execute(() -> {
            Bitmap bmp = decodeRgb565(file, screenW, screenH);
            post(() -> {
                if (!active || !path.equals(loadingBitmapPath)) {
                    if (path.equals(loadingBitmapPath)) loadingBitmapPath = null;
                    if (bmp != null) bmp.recycle();
                    return;
                }
                loadingBitmapPath = null;
                if (cachedBitmap != null && cachedBitmap != bmp) {
                    cachedBitmap.recycle();
                }
                cachedBitmap = bmp;
                cachedBitmapPath = path;
                invalidate();
            });
        });
    }

    private static Bitmap decodeRgb565(File file, int targetW, int targetH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            opts.inSampleSize = WallpaperSize.sampleSize(opts.outWidth, opts.outHeight, targetW, targetH);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void ensureShaders(int w, int h) {
        if (w == cachedW && h == cachedH && mood == cachedMood) return;
        cachedW = w;
        cachedH = h;
        cachedMood = mood;
        hearthGlowShader = new RadialGradient(w * 0.5f, h * 0.96f, w * 0.58f,
                new int[]{0x88FF6D24, 0x44C82814, 0x00180505},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
        if (mood == ChillAudio.MOOD_OCEAN) {
            moodBgShader = new LinearGradient(0, 0, 0, h,
                    new int[]{0xFF08091A, 0xFF261028, 0xFFE06B42, 0xFF08070C},
                    new float[]{0f, 0.42f, 0.58f, 1f}, Shader.TileMode.CLAMP);
            horizonGlowShader = new RadialGradient(w * 0.62f, h * 0.52f, w * 0.52f,
                    new int[]{0x44FF9452, 0x1EFF6838, 0x00000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        } else if (mood == ChillAudio.MOOD_AURORA) {
            moodBgShader = new LinearGradient(0, 0, 0, h,
                    new int[]{0xFF040814, 0xFF081B2C, 0xFF0A2630, 0xFF05070E},
                    new float[]{0f, 0.40f, 0.68f, 1f}, Shader.TileMode.CLAMP);
        } else if (mood == ChillAudio.MOOD_RAIN) {
            moodBgShader = new LinearGradient(0, 0, 0, h,
                    new int[]{0xFF080D1A, 0xFF101B2E, 0xFF152238, 0xFF070B14},
                    new float[]{0f, 0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP);
        } else if (mood == ChillAudio.MOOD_FIREPLACE) {
            moodBgShader = new LinearGradient(0, 0, 0, h,
                    new int[]{0xFF090507, 0xFF180A0C, 0xFF38120E, 0xFF1A0706},
                    new float[]{0f, 0.50f, 0.82f, 1f}, Shader.TileMode.CLAMP);
        } else {
            radioScrimShader = new LinearGradient(0, 0, 0, h,
                    new int[]{0x4005070E, 0x1405070E, 0x1E05070E, 0x6A05070E},
                    new float[]{0f, 0.35f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    private boolean vinylLounge = false;

    void enableVinylLounge() {
        vinylLounge = true;
        mood = ChillAudio.MOOD_RADIO_LUSH;
        cachedMood = -1;
        requestBackdropForCurrentMood();
        showHud();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureShaders(w, h);
        long now = SystemClock.uptimeMillis();
        float t = (now - startMs) * 0.001f;

        if (ChillAudio.isRadioMood(mood)) {
            drawRadioScreen(canvas, w, h, t, mood - ChillAudio.MOOD_RADIO_GROOVE);
            if (vinylLounge) drawVinylTurntable(canvas, w, h, t);
        } else if (mood == ChillAudio.MOOD_FRAME_ORBITAL) {
            drawOrbitalFrame(canvas, w, h, t);
        } else if (mood == ChillAudio.MOOD_FRAME_MUSEUM) {
            drawMuseumFrame(canvas, w, h);
        } else {
            bgPaint.setShader(moodBgShader);
            canvas.drawRect(0, 0, w, h, bgPaint);
            bgPaint.setShader(null);
            if (mood == ChillAudio.MOOD_OCEAN) {
                drawOceanSunset(canvas, w, h, t);
            } else if (mood == ChillAudio.MOOD_AURORA) {
                drawAuroraNight(canvas, w, h, t);
            } else if (mood == ChillAudio.MOOD_RAIN) {
                drawMidnightRain(canvas, w, h, t);
            } else {
                drawEmberHearth(canvas, w, h, t);
            }
        }

        // Zero-File Procedural Soundscape visual layer (Soft Rain · Deep Brown Noise · Soft Hearth)
        if (soundscape == ChillAudio.SOUNDSCAPE_RAIN && mood != ChillAudio.MOOD_RAIN) {
            drawMidnightRain(canvas, w, h, t);
        } else if (soundscape == ChillAudio.SOUNDSCAPE_BROWN && mood != ChillAudio.MOOD_AURORA) {
            drawOutdoorSnow(canvas, w, h, t);
        } else if (soundscape == ChillAudio.SOUNDSCAPE_HEARTH && mood != ChillAudio.MOOD_FIREPLACE) {
            drawEmberHearth(canvas, w, h, t);
        } else if (soundscape == ChillAudio.SOUNDSCAPE_OFF && mood <= ChillAudio.MOOD_RAIN) {
            // Only overlay live outdoor rain/snow on outdoor nature moods (never inside Museum, Space, or Fireplace)
            int outdoorPrecip = HomeWeather.outdoorPrecipitation();
            if (outdoorPrecip == HomeWeather.PRECIP_RAIN && mood != ChillAudio.MOOD_RAIN) {
                drawMidnightRain(canvas, w, h, t);
            } else if (outdoorPrecip == HomeWeather.PRECIP_SNOW) {
                drawOutdoorSnow(canvas, w, h, t);
            }
        }

        drawHudPill(canvas, w, h, now);

        if (active) {
            boolean staticMuseum = mood == ChillAudio.MOOD_FRAME_MUSEUM
                    && soundscape == ChillAudio.SOUNDSCAPE_OFF
                    && (now - hudShownAtMs >= 4500L);
            if (!staticMuseum) {
                postInvalidateDelayed(33L);
            }
        }
    }

    private void drawOrbitalFrame(Canvas canvas, int w, int h, float t) {
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            dstRect.set(0, 0, w, h);
            bgPaint.setShader(null);
            bgPaint.setAlpha(255);
            canvas.drawBitmap(cachedBitmap, null, dstRect, bgPaint);
        } else {
            canvas.drawColor(0xFF040710);
        }
        bgPaint.setShader(radioScrimShader);
        canvas.drawRect(0, 0, w, h, bgPaint);
        bgPaint.setShader(null);

        particlePaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 40; i++) {
            float x = px[i] * w;
            float y = py[i] * h * 0.42f;
            float tw = 0.2f + 0.8f * (0.5f + 0.5f * (float) Math.sin(t * (1.1f + (i % 4) * 0.25f) + pPhase[i]));
            int alpha = Math.round(170 * tw);
            particlePaint.setColor((alpha << 24) | (i % 3 == 0 ? 0x88EEFF : 0xFFFFFF));
            canvas.drawCircle(x, y, pr[i] * 0.5f, particlePaint);
        }
    }

    private void drawMuseumFrame(Canvas canvas, int w, int h) {
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            dstRect.set(0, 0, w, h);
            bgPaint.setShader(null);
            bgPaint.setAlpha(255);
            canvas.drawBitmap(cachedBitmap, null, dstRect, bgPaint);
        } else {
            canvas.drawColor(0xFF0B0A08);
        }
        bgPaint.setShader(radioScrimShader);
        canvas.drawRect(0, 0, w, h, bgPaint);
        bgPaint.setShader(null);
    }

    private void drawRadioScreen(Canvas canvas, int w, int h, float t, int station) {
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            dstRect.set(0, 0, w, h);
            bgPaint.setShader(null);
            bgPaint.setAlpha(255);
            canvas.drawBitmap(cachedBitmap, null, dstRect, bgPaint);
        } else {
            canvas.drawColor(0xFF0A0D18);
        }

        bgPaint.setShader(radioScrimShader);
        canvas.drawRect(0, 0, w, h, bgPaint);
        bgPaint.setShader(null);

        particlePaint.setStyle(Paint.Style.FILL);
        int c1, c2;
        if (station == 0) {
            c1 = 0xFFB86C; c2 = 0xE066FF;
        } else if (station == 1) {
            c1 = 0x5CE8E8; c2 = 0xB888FF;
        } else if (station == 2) {
            c1 = 0xFFB4C8; c2 = 0xFFD28E;
        } else {
            c1 = 0x48B8FF; c2 = 0xC8E8FF;
        }
        for (int i = 0; i < 32; i++) {
            float x = ((px[i] + vx[i] * t * 14f) % 1f + 1f) % 1f * w;
            float y = ((py[i] - vy[i] * t * 6f) % 1f + 1f) % 1f * h;
            float tw = 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(t * 1.4f + pPhase[i]));
            int alpha = Math.round(130 * tw);
            particlePaint.setColor((alpha << 24) | (i % 2 == 0 ? c1 : c2));
            canvas.drawCircle(x, y, pr[i] * (0.75f + 0.45f * tw), particlePaint);
        }
    }

    private void drawVinylTurntable(Canvas canvas, int w, int h, float t) {
        float cx = w * 0.72f;
        float cy = h * 0.44f;
        float rad = h * 0.23f;

        particlePaint.setStyle(Paint.Style.FILL);
        particlePaint.setColor(0x38000000);
        canvas.drawCircle(cx, cy + 8f * density, rad * 1.08f, particlePaint);

        particlePaint.setColor(0xF2101218);
        canvas.drawCircle(cx, cy, rad, particlePaint);
        pillRim.setColor(0x40FFFFFF);
        pillRim.setStrokeWidth(1.5f * density);
        canvas.drawCircle(cx, cy, rad, pillRim);

        pillRim.setStrokeWidth(0.9f * density);
        for (int g = 1; g <= 9; g++) {
            float gr = rad * (0.38f + g * 0.062f);
            pillRim.setColor((g % 2 == 0) ? 0x22FFFFFF : 0x12FFFFFF);
            canvas.drawCircle(cx, cy, gr, pillRim);
        }

        float deg = (t * 200f) % 360f;
        canvas.save();
        canvas.rotate(deg, cx, cy);

        pillRim.setColor(0x28CBE4FF);
        pillRim.setStrokeWidth(rad * 0.42f);
        dstRect.set(cx - rad * 0.68f, cy - rad * 0.68f, cx + rad * 0.68f, cy + rad * 0.68f);
        canvas.drawArc(dstRect, -22f, 44f, false, pillRim);
        canvas.drawArc(dstRect, 158f, 44f, false, pillRim);

        float labelR = rad * 0.32f;
        particlePaint.setColor(0xFFE89B48);
        canvas.drawCircle(cx, cy, labelR, particlePaint);
        particlePaint.setColor(0xFF28180C);
        canvas.drawCircle(cx, cy, labelR * 0.72f, particlePaint);
        pillRim.setStrokeWidth(1.2f * density);
        pillRim.setColor(0x88FFD28E);
        canvas.drawCircle(cx, cy, labelR * 0.86f, pillRim);

        canvas.drawLine(cx - labelR * 0.55f, cy, cx + labelR * 0.55f, cy, pillRim);
        particlePaint.setColor(0xFF0A0A0E);
        canvas.drawCircle(cx, cy, rad * 0.045f, particlePaint);
        canvas.restore();

        float pivotX = cx + rad * 1.12f;
        float pivotY = cy - rad * 0.68f;
        particlePaint.setColor(0x44FFFFFF);
        canvas.drawCircle(pivotX, pivotY, 14f * density, particlePaint);
        particlePaint.setColor(0xDDCCD4E0);
        canvas.drawCircle(pivotX, pivotY, 7f * density, particlePaint);
        pillRim.setColor(0xEECCD6E6);
        pillRim.setStrokeWidth(3f * density);
        float stylusX = cx + rad * 0.66f;
        float stylusY = cy + rad * 0.32f;
        canvas.drawLine(pivotX, pivotY, stylusX, stylusY, pillRim);
        particlePaint.setColor(0xFFFFB347);
        canvas.drawCircle(stylusX, stylusY, 4.5f * density, particlePaint);
        pillRim.setStrokeWidth(density);
    }

    private void drawOceanSunset(Canvas canvas, int w, int h, float t) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 0.7f);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setShader(horizonGlowShader);
        glowPaint.setAlpha(Math.round(160 + 95 * pulse));
        canvas.drawRect(0, 0, w, h, glowPaint);
        glowPaint.setShader(null);

        particlePaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 36; i++) {
            float x = ((px[i] + vx[i] * t * 18f) % 1f + 1f) % 1f * w;
            float y = ((py[i] - vy[i] * t * 8f) % 1f + 1f) % 1f * h;
            float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 1.6f + pPhase[i]));
            int alpha = Math.round(145 * tw);
            particlePaint.setColor((alpha << 24) | (i % 3 == 0 ? 0xFFD28E : 0xFF9E6D));
            canvas.drawCircle(x, y, pr[i] * (0.8f + 0.4f * tw), particlePaint);
        }
    }

    private void drawAuroraNight(Canvas canvas, int w, int h, float t) {
        particlePaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < N; i++) {
            float x = px[i] * w;
            float y = py[i] * h * 0.72f;
            float tw = 0.25f + 0.75f * (0.5f + 0.5f * (float) Math.sin(t * (1.2f + (i % 5) * 0.3f) + pPhase[i]));
            int alpha = Math.round(190 * tw);
            particlePaint.setColor((alpha << 24) | 0xE8F4FF);
            canvas.drawCircle(x, y, pr[i] * 0.55f, particlePaint);
        }

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        for (int band = 0; band < 3; band++) {
            wavePath.reset();
            float baseY = h * (0.28f + band * 0.08f);
            for (int step = 0; step <= 24; step++) {
                float u = step / 24f;
                float x = u * w;
                float y = baseY + (float) Math.sin(u * 4.5f + t * 0.45f + band * 1.3f) * (28f * density)
                        + (float) Math.cos(u * 2.2f - t * 0.3f) * (16f * density);
                if (step == 0) wavePath.moveTo(x, y);
                else wavePath.lineTo(x, y);
            }
            glowPaint.setStrokeWidth((34f - band * 7f) * density);
            int color = band == 0 ? 0x262CE89A : band == 1 ? 0x2220B8E8 : 0x1E8A4FFF;
            glowPaint.setColor(color);
            canvas.drawPath(wavePath, glowPaint);
        }
    }

    private void drawOutdoorSnow(Canvas canvas, int w, int h, float t) {
        particlePaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < N; i++) {
            float speed = 0.045f + (i % 6) * 0.014f;
            float u = (py[i] + t * speed) % 1f;
            float swayFreq = 0.35f + (i % 5) * 0.12f;
            float swayAmp = (12f + (i % 4) * 7f) * density;
            double sway = 6.2831853 * swayFreq * t + pPhase[i];
            float x = ((px[i] * w + swayAmp * (float) Math.sin(sway)) % w + w) % w;
            float y = u * h;
            int alpha = u > 0.85f ? Math.round(165f * (1f - (u - 0.85f) / 0.15f)) : (75 + (i % 5) * 20);
            particlePaint.setColor((alpha << 24) | 0xEDF6FF);
            canvas.drawCircle(x, y, pr[i] * (0.55f + (i % 3) * 0.25f), particlePaint);
        }
    }

    private void drawMidnightRain(Canvas canvas, int w, int h, float t) {
        particlePaint.setStyle(Paint.Style.STROKE);
        particlePaint.setStrokeWidth(1.2f * density);
        float len = 26f * density;
        float slant = -6f * density;
        for (int i = 0; i < N; i++) {
            float speed = 0.38f + (i % 7) * 0.07f;
            float u = (py[i] + t * speed) % 1f;
            float x = ((px[i] - u * 0.08f) % 1f + 1f) % 1f * w;
            float y = u * h;
            int alpha = 55 + (i % 5) * 22;
            particlePaint.setColor((alpha << 24) | 0xA8C8FF);
            canvas.drawLine(x, y, x + slant, y + len, particlePaint);

            if (i < 14) {
                float rp = (t * 0.65f + pPhase[i]) % 1f;
                int rAlpha = Math.round((1f - rp) * 70f);
                particlePaint.setColor((rAlpha << 24) | 0x9CC0FF);
                float rx = px[i] * w;
                float ry = h * (0.74f + (py[i] * 0.22f));
                float rad = (6f + rp * 34f) * density;
                canvas.drawOval(rx - rad, ry - rad * 0.28f, rx + rad, ry + rad * 0.28f, particlePaint);
            }
        }
    }

    private void drawEmberHearth(Canvas canvas, int w, int h, float t) {
        float flicker = 0.82f + 0.18f * ((float) Math.sin(t * 3.1f) * 0.6f + (float) Math.cos(t * 7.3f) * 0.4f);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setShader(hearthGlowShader);
        glowPaint.setAlpha(Math.round(225 * flicker));
        canvas.drawRect(0, 0, w, h, glowPaint);
        glowPaint.setShader(null);

        particlePaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 48; i++) {
            float speed = 0.09f + (i % 6) * 0.025f;
            float life = (py[i] + t * speed) % 1f;
            float y = h - life * h * 0.85f;
            float sway = (float) Math.sin(life * 6.0f + pPhase[i] + t) * (24f * density);
            float x = px[i] * w + sway;
            int alpha = Math.round((1f - life) * 220f);
            int rgb = (i % 3 == 0) ? 0xFFAA44 : (i % 3 == 1) ? 0xFF6428 : 0xFFD878;
            particlePaint.setColor((alpha << 24) | rgb);
            canvas.drawCircle(x, y, pr[i] * (1.1f - 0.6f * life), particlePaint);
        }
    }

    private void drawHudPill(Canvas canvas, int w, int h, long now) {
        long age = now - hudShownAtMs;
        if (age >= 4500L) return;
        float alpha = age < 3500L ? 1f : (4500L - age) / 1000f;
        int a255 = Math.round(alpha * 255f);

        float pw = cachedPillWidth > 0f ? cachedPillWidth : 480f * density;
        float ph = 54f * density;
        float left = (w - pw) * 0.5f;
        float top = h - ph - 34f * density;
        pillRect.set(left, top, left + pw, top + ph);
        float rad = ph * 0.5f;

        pillBg.setColor((Math.round(alpha * 0xB8) << 24) | 0x12131A);
        pillRim.setStrokeWidth(density);
        pillRim.setColor((Math.round(alpha * 0x55) << 24) | 0xFFFFFF);
        canvas.drawRoundRect(pillRect, rad, rad, pillBg);
        canvas.drawRoundRect(pillRect, rad, rad, pillRim);

        hudTitle.setAlpha(a255);
        hudSub.setAlpha(Math.round(alpha * 185f));
        canvas.drawText(cachedHudTitle, w * 0.5f, top + 24f * density, hudTitle);
        canvas.drawText(cachedHudSub, w * 0.5f, top + 42f * density, hudSub);
    }
}
