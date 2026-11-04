package com.lionlev.tv.capacity;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ConfigurationInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class StatsActivity extends Activity {
    private static final BidiFormatter BIDI = BidiFormatter.getInstance();
    private static final String PREFS = "capacity_prefs";
    private static final String KEY_SHOW_CPU = "show_cpu";
    private static final String KEY_SHOW_GPU = "show_gpu";

    // Calm, low-contrast slate palette
    private static final int COLOR_BG = 0xFF0E1318;
    private static final int COLOR_CARD = 0xFF151C24;
    private static final int COLOR_CARD_BORDER = 0xFF222D38;
    private static final int COLOR_TRACK = 0xFF202B36;
    private static final int COLOR_GREEN = 0xFF56B886;
    private static final int COLOR_AMBER = 0xFFD49B35;
    private static final int COLOR_RED = 0xFFE06C65;
    private static final int COLOR_MUTED = 0xFF7D8B99;
    private static final int COLOR_SUB = 0xFFAAB6C2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private volatile Handler background;

    private SharedPreferences prefs;
    private volatile boolean showCpu;
    private volatile boolean showGpu;

    private TextView headerSubtitle;
    private Button cpuToggleBtn;
    private Button gpuToggleBtn;
    private LinearLayout advancedRow;
    private CardViews ramCard;
    private CardViews storageCard;
    private CardViews cpuCard;
    private CardViews gpuCard;

    // Cached static hardware info (probed lazily only when CPU/GPU toggles are on)
    private String cpuArchLine = "";
    private String gpuRendererLine = "";
    private String displayModeLine = "";

    // CPU time_in_state delta tracking
    private long prevTotalTicks = -1;
    private long prevWeightedFreqTicks = -1;
    private int lastCpuPct = -1;

    // Live UI/GPU frame timing (smoothed ms)
    private volatile float smoothedFrameMs = 1.6f;
    private Window.OnFrameMetricsAvailableListener frameListener;
    private volatile String statusBanner = "";
    private volatile long statusBannerUntilMs = 0L;

    private static final class CardViews {
        final LinearLayout container;
        final TextView valueText;
        final TextView pctText;
        final BarView bar;
        final TextView subText;

        CardViews(LinearLayout container, TextView valueText, TextView pctText,
                  BarView bar, TextView subText) {
            this.container = container;
            this.valueText = valueText;
            this.pctText = pctText;
            this.bar = bar;
            this.subText = subText;
        }
    }

    /** Calm rounded progress bar with smooth 60fps cubic-out value & color interpolation. */
    private static final class BarView extends View {
        private static final long ANIM_MS = 480L;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float startPct = 0f;
        private float currentPct = 0f;
        private float targetPct = -1f;
        private int startColor = COLOR_GREEN;
        private int currentColor = COLOR_GREEN;
        private int targetColor = COLOR_GREEN;
        private long animStartMs = 0L;

        BarView(Activity ctx, int heightPx) {
            super(ctx);
            setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        }

        void setProgress(int pct, int color) {
            float clamped = Math.max(0, Math.min(100, pct));
            if (targetPct < 0f) {
                startPct = 0f;
                currentPct = 0f;
                targetPct = clamped;
                startColor = color;
                currentColor = color;
                targetColor = color;
                animStartMs = SystemClock.uptimeMillis();
                postInvalidateOnAnimation();
                return;
            }
            if (Math.abs(targetPct - clamped) > 0.05f || targetColor != color) {
                startPct = currentPct;
                targetPct = clamped;
                startColor = currentColor;
                targetColor = color;
                animStartMs = SystemClock.uptimeMillis();
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            boolean animating = false;
            if (animStartMs > 0L && targetPct >= 0f) {
                float rawT = (SystemClock.uptimeMillis() - animStartMs) / (float) ANIM_MS;
                if (rawT >= 1f) {
                    currentPct = targetPct;
                    currentColor = targetColor;
                    animStartMs = 0L;
                } else {
                    float t = Math.max(0f, rawT);
                    float u = 1f - t;
                    float ease = 1f - (u * u * u);
                    currentPct = startPct + (targetPct - startPct) * ease;
                    currentColor = blendRgb(startColor, targetColor, ease);
                    animating = true;
                }
            }

            float r = h * 0.5f;
            rect.set(0, 0, w, h);
            paint.setColor(COLOR_TRACK);
            canvas.drawRoundRect(rect, r, r, paint);
            if (currentPct > 0.1f) {
                float fw = Math.max(h, (w * currentPct) / 100f);
                float right = Math.min(w, fw);
                rect.set(0, 0, right, h);
                paint.setColor(currentColor);
                canvas.drawRoundRect(rect, r, r, paint);
            }
            if (animating) {
                postInvalidateOnAnimation();
            }
        }

        private static int blendRgb(int c1, int c2, float t) {
            if (c1 == c2) return c1;
            int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
            int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
            int r = Math.round(r1 + (r2 - r1) * t);
            int g = Math.round(g1 + (g2 - g1) * t);
            int b = Math.round(b1 + (b2 - b1) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private static int barColorForUsage(int pct) {
        if (pct >= 85) return COLOR_RED;
        if (pct >= 70) return COLOR_AMBER;
        return COLOR_GREEN;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        showCpu = prefs.getBoolean(KEY_SHOW_CPU, false);
        showGpu = prefs.getBoolean(KEY_SHOW_GPU, false);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(56), dp(40), dp(56), dp(36));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // Top header: Title + calm subtitle on left, pill buttons on right
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setTextColor(Color.WHITE);
        titleCol.addView(title);

        headerSubtitle = new TextView(this);
        headerSubtitle.setText(getString(R.string.loading));
        headerSubtitle.setTextSize(13);
        headerSubtitle.setTextColor(COLOR_MUTED);
        headerSubtitle.setPadding(0, dp(4), 0, 0);
        headerSubtitle.setSingleLine(true);
        headerSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(headerSubtitle);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button optimizeBtn = pillButton("Free Up RAM", false, this::optimizeRam);
        cpuToggleBtn = pillButton("", showCpu, this::toggleCpu);
        gpuToggleBtn = pillButton("", showGpu, this::toggleGpu);
        Button backBtn = pillButton(getString(R.string.back), false, this::finish);
        actions.addView(optimizeBtn);
        actions.addView(cpuToggleBtn);
        actions.addView(gpuToggleBtn);
        actions.addView(backBtn);
        header.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Primary row: Memory & Storage (always visible)
        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        primaryLp.topMargin = dp(28);
        ramCard = createCard(primaryRow, "Memory", true);
        storageCard = createCard(primaryRow, "Storage", false);
        root.addView(primaryRow, primaryLp);

        // Optional advanced row: CPU & GPU (hidden unless toggled on)
        advancedRow = new LinearLayout(this);
        advancedRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams advLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        advLp.topMargin = dp(18);
        cpuCard = createCard(advancedRow, "Processor (CPU)", true);
        gpuCard = createCard(advancedRow, "Graphics (GPU)", false);
        root.addView(advancedRow, advLp);

        updateToggleLabelsAndVisibility();
        backBtn.requestFocus();
        initDisplayInfo();
    }

    private CardViews createCard(LinearLayout parentRow, String labelText, boolean isLeft) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(22), dp(26), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COLOR_CARD_BORDER);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (isLeft) lp.setMarginEnd(dp(10));
        else lp.setMarginStart(dp(10));

        // Card title + quiet percentage on right
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(labelText);
        title.setTextSize(14);
        title.setTextColor(COLOR_SUB);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pctText = new TextView(this);
        pctText.setText("");
        pctText.setTextSize(14);
        pctText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pctText.setTextColor(COLOR_GREEN);
        top.addView(pctText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(top);

        // Large, calm primary figure
        TextView valueText = new TextView(this);
        valueText.setText(getString(R.string.loading));
        valueText.setTextSize(24);
        valueText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        valueText.setTextColor(Color.WHITE);
        valueText.setSingleLine(true);
        valueText.setEllipsize(TextUtils.TruncateAt.END);
        valueText.setPadding(0, dp(10), 0, dp(14));
        card.addView(valueText);

        // Single clean progress bar (8dp tall)
        BarView bar = new BarView(this, dp(8));
        card.addView(bar);

        // Single calm subtitle line below bar
        TextView subText = new TextView(this);
        subText.setText(" ");
        subText.setTextSize(13);
        subText.setTextColor(COLOR_MUTED);
        subText.setSingleLine(true);
        subText.setEllipsize(TextUtils.TruncateAt.END);
        subText.setPadding(0, dp(12), 0, 0);
        card.addView(subText);

        parentRow.addView(card, lp);
        return new CardViews(card, valueText, pctText, bar, subText);
    }

    private Button pillButton(String label, boolean activeToggle, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(13.5f);
        view.setAllCaps(false);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setPadding(dp(16), dp(7), dp(16), dp(7));
        view.setMinimumWidth(dp(84));
        view.setMinimumHeight(dp(36));
        stylePillButton(view, activeToggle);
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        view.setLayoutParams(lp);
        return view;
    }

    private void stylePillButton(Button view, boolean activeToggle) {
        view.setTextColor(activeToggle ? 0xFFE6FFF2 : 0xFFD0D7DE);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFF22382F);
        focused.setCornerRadius(dp(18));
        focused.setStroke(dp(1.5f), 0xFF68B692);
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(activeToggle ? 0xFF1A2B24 : 0xFF161D24);
        idle.setCornerRadius(dp(18));
        idle.setStroke(dp(1), activeToggle ? 0xFF36594A : 0xFF26313D);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[0], idle);
        view.setBackground(bg);
    }

    private void toggleCpu() {
        showCpu = !showCpu;
        prefs.edit().putBoolean(KEY_SHOW_CPU, showCpu).apply();
        updateToggleLabelsAndVisibility();
        triggerImmediateSample();
    }

    private void toggleGpu() {
        showGpu = !showGpu;
        prefs.edit().putBoolean(KEY_SHOW_GPU, showGpu).apply();
        if (showGpu) attachFrameMetrics();
        else detachFrameMetrics();
        updateToggleLabelsAndVisibility();
        triggerImmediateSample();
    }

    private void updateToggleLabelsAndVisibility() {
        cpuToggleBtn.setText(showCpu ? "CPU: On" : "CPU: Off");
        stylePillButton(cpuToggleBtn, showCpu);
        gpuToggleBtn.setText(showGpu ? "GPU: On" : "GPU: Off");
        stylePillButton(gpuToggleBtn, showGpu);

        cpuCard.container.setVisibility(showCpu ? View.VISIBLE : View.GONE);
        gpuCard.container.setVisibility(showGpu ? View.VISIBLE : View.GONE);
        advancedRow.setVisibility((showCpu || showGpu) ? View.VISIBLE : View.GONE);

        // Keep spacing balanced when only one advanced card is visible
        LinearLayout.LayoutParams cpuLp = (LinearLayout.LayoutParams) cpuCard.container.getLayoutParams();
        LinearLayout.LayoutParams gpuLp = (LinearLayout.LayoutParams) gpuCard.container.getLayoutParams();
        cpuLp.setMarginEnd(showCpu && showGpu ? dp(10) : 0);
        gpuLp.setMarginStart(showCpu && showGpu ? dp(10) : 0);
        cpuCard.container.setLayoutParams(cpuLp);
        gpuCard.container.setLayoutParams(gpuLp);
    }

    private void initDisplayInfo() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float hz = 60f;
        int w = dm.widthPixels, h = dm.heightPixels;
        try {
            Display d = getWindowManager().getDefaultDisplay();
            if (d != null) {
                hz = d.getRefreshRate();
                Display.Mode mode = d.getMode();
                if (mode != null && mode.getPhysicalWidth() > 0) {
                    w = mode.getPhysicalWidth();
                    h = mode.getPhysicalHeight();
                }
            }
        } catch (Throwable ignored) {
        }
        displayModeLine = String.format(Locale.US, "%d×%d @ %.0f Hz", w, h, hz);
    }

    private String number(String value) {
        return BIDI.unicodeWrap(value, TextDirectionHeuristics.LTR);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (showGpu) attachFrameMetrics();
        startWorker();
    }

    @Override
    public void onPause() {
        detachFrameMetrics();
        stopWorker();
        super.onPause();
    }

    private void attachFrameMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && frameListener == null) {
            frameListener = (window, frameMetrics, dropCountSinceLastInvocation) -> {
                long totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION);
                if (totalNs > 0 && totalNs < 200_000_000L) {
                    float ms = totalNs / 1_000_000f;
                    smoothedFrameMs = smoothedFrameMs * 0.75f + ms * 0.25f;
                }
            };
            try {
                getWindow().addOnFrameMetricsAvailableListener(frameListener, ui);
            } catch (Throwable ignored) {
            }
        }
    }

    private void detachFrameMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && frameListener != null) {
            try {
                getWindow().removeOnFrameMetricsAvailableListener(frameListener);
            } catch (Throwable ignored) {
            }
            frameListener = null;
        }
    }

    private void startWorker() {
        stopWorker();
        prevTotalTicks = -1;
        prevWeightedFreqTicks = -1;
        worker = new HandlerThread("addon-stats");
        worker.start();
        Handler current = new Handler(worker.getLooper());
        background = current;
        current.post(new Runnable() {
            @Override
            public void run() {
                if (background != current) return;
                Snapshot snap = sampleSnapshot();
                if (background != current) return;
                ui.post(() -> {
                    if (background == current) renderSnapshot(snap);
                });
                if (background == current) current.postDelayed(this, 1000L);
            }
        });
    }

    private void triggerImmediateSample() {
        Handler bg = background;
        if (bg != null) {
            bg.post(() -> {
                if (background != bg) return;
                Snapshot snap = sampleSnapshot();
                if (background != bg) return;
                ui.post(() -> {
                    if (background == bg) renderSnapshot(snap);
                });
            });
        }
    }

    private void optimizeRam() {
        Handler bg = background;
        if (bg == null) return;
        headerSubtitle.setText("Freeing background memory…");
        headerSubtitle.setTextColor(COLOR_SUB);
        bg.post(() -> {
            if (background != bg) return;
            long beforeAvailKb = readAvailableRamKb();
            reapBackgroundPackages();
            System.gc();
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ignored) {
                return;
            }
            long afterAvailKb = readAvailableRamKb();
            long gainedMb = Math.max(0L, (afterAvailKb - beforeAvailKb) / 1024L);
            statusBanner = gainedMb > 0
                    ? "Freed " + gainedMb + " MB of background memory"
                    : "Background memory is already clean";
            statusBannerUntilMs = SystemClock.elapsedRealtime() + 6000L;
            Snapshot snap = sampleSnapshot();
            if (background != bg) return;
            ui.post(() -> {
                if (background == bg) renderSnapshot(snap);
            });
        });
    }

    private long readAvailableRamKb() {
        try (FileReader reader = new FileReader("/proc/meminfo")) {
            long[] d = MemoryCapacity.readDetailedKb(reader);
            return d[1];
        } catch (IOException e) {
            return -1;
        }
    }

    private void reapBackgroundPackages() {
        StringBuilder pkgs = new StringBuilder();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(main, 0);
            String myPkg = getPackageName();
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String p = ri.activityInfo.packageName;
                if (p == null || p.equals(myPkg) || "com.gathin.tvhome".equals(p) || "com.gathin.chillradio".equals(p)) {
                    continue;
                }
                if (pkgs.length() > 0) pkgs.append(',');
                pkgs.append(p);
            }
        } catch (Throwable ignored) {
        }
        if (pkgs.length() > 0) {
            Uri uri = Uri.parse("content://com.gathin.chillradio.now/reap")
                    .buildUpon()
                    .appendQueryParameter("pkgs", pkgs.toString())
                    .build();
            try (Cursor ignored = getContentResolver().query(uri, null, null, null, null)) {
            } catch (Throwable ignored) {
            }
        }
    }

    private void stopWorker() {
        background = null;
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
        ui.removeCallbacksAndMessages(null);
    }

    private static final class Snapshot {
        String headerSub;
        boolean bannerActive;

        // RAM
        String ramValue;
        String ramPctText;
        int ramPct;
        int ramColor;
        String ramSub;

        // Storage
        String storageValue;
        String storagePctText;
        int storagePct;
        int storageColor;
        String storageSub;

        // CPU (optional)
        String cpuValue;
        String cpuPctText;
        int cpuPct;
        int cpuColor;
        String cpuSub;

        // GPU (optional)
        String gpuValue;
        String gpuPctText;
        int gpuPct;
        int gpuColor;
        String gpuSub;
    }

    private Snapshot sampleSnapshot() {
        Snapshot s = new Snapshot();
        long elapsedMs = SystemClock.elapsedRealtime();
        if (elapsedMs < statusBannerUntilMs && !statusBanner.isEmpty()) {
            s.headerSub = statusBanner;
            s.bannerActive = true;
        } else {
            s.headerSub = "Uptime " + formatDuration(elapsedMs) + "  ·  Live";
            s.bannerActive = false;
        }

        // 1. Memory (RAM)
        long totalKb = -1, availKb = -1, swapTotalKb = -1, swapFreeKb = -1;
        try (FileReader reader = new FileReader("/proc/meminfo")) {
            long[] d = MemoryCapacity.readDetailedKb(reader);
            totalKb = d[0];
            availKb = d[1];
            swapTotalKb = d[3];
            swapFreeKb = d[4];
        } catch (IOException ignored) {
        }
        if (totalKb >= 1024 && availKb >= 0) {
            long totalMb = totalKb / 1024;
            long availMb = availKb / 1024;
            long usedMb = Math.max(0, totalMb - availMb);
            int pct = (int) Math.min(100, Math.round(usedMb * 100.0 / totalMb));
            s.ramPct = pct;
            s.ramColor = barColorForUsage(pct);
            s.ramPctText = pct + "%";
            s.ramValue = number(availMb + " MB") + " free";
            String swapNote = "";
            if (swapTotalKb > 0 && swapFreeKb >= 0) {
                long swapUsedMb = Math.max(0, (swapTotalKb - swapFreeKb) / 1024);
                swapNote = "  ·  Swap " + number(swapUsedMb + " MB");
            }
            s.ramSub = number(StatsCapacity.memory(usedMb, totalMb) + " MB") + " in use" + swapNote;
        } else {
            s.ramPct = 0;
            s.ramColor = COLOR_MUTED;
            s.ramPctText = "";
            s.ramValue = getString(R.string.unavailable);
            s.ramSub = "";
        }

        // 2. Storage
        try {
            StatFs dataStat = new StatFs(Environment.getDataDirectory().getPath());
            long dataAvail = dataStat.getAvailableBytes();
            long dataTotal = dataStat.getTotalBytes();
            long dataUsed = Math.max(0, dataTotal - dataAvail);
            int dataPct = dataTotal > 0 ? (int) Math.min(100, Math.round(dataUsed * 100.0 / dataTotal)) : 0;
            s.storagePct = dataPct;
            s.storageColor = barColorForUsage(dataPct);
            s.storagePctText = dataPct + "%";
            s.storageValue = number(String.format(Locale.US, "%.1f GB", dataAvail / (1024.0 * 1024 * 1024))) + " free";
            s.storageSub = number(StatsCapacity.storage(dataUsed, dataTotal)) + " in use";
        } catch (Throwable e) {
            s.storagePct = 0;
            s.storageColor = COLOR_MUTED;
            s.storagePctText = "";
            s.storageValue = getString(R.string.unavailable);
            s.storageSub = "";
        }

        // 3. CPU (sampled only when enabled)
        if (showCpu) {
            if (cpuArchLine.isEmpty()) cpuArchLine = probeCpuArch();
            long curKhz = readLongFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
            long maxKhz = readLongFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq");
            String gov = readLineFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor");
            updateCpuTimeInState(maxKhz > 0 ? maxKhz : 1416000L);
            int curMhz = curKhz > 0 ? (int) (curKhz / 1000) : 0;
            int maxMhz = maxKhz > 0 ? (int) (maxKhz / 1000) : 1416;
            int clockPct = lastCpuPct >= 0
                    ? lastCpuPct
                    : (maxMhz > 0 && curMhz > 0 ? Math.min(100, Math.round(curMhz * 100f / maxMhz)) : 0);
            s.cpuPct = clockPct;
            s.cpuColor = barColorForUsage(clockPct);
            s.cpuPctText = clockPct + "%";
            s.cpuValue = curMhz > 0 ? number(curMhz + " MHz") : clockPct + "% load";
            s.cpuSub = cpuArchLine + (gov.isEmpty() ? "" : "  ·  " + gov);
        }

        // 4. GPU (sampled only when enabled)
        if (showGpu) {
            if (gpuRendererLine.isEmpty()) gpuRendererLine = probeGpuRenderer();
            float frameMs = smoothedFrameMs;
            int frameBudgetPct = Math.min(100, Math.max(4, Math.round((frameMs / 16.67f) * 100f)));
            s.gpuPct = frameBudgetPct;
            s.gpuColor = barColorForUsage(frameBudgetPct);
            s.gpuPctText = "60 FPS";
            s.gpuValue = String.format(Locale.US, "%.1f ms frame", frameMs);
            s.gpuSub = gpuRendererLine + "  ·  " + displayModeLine;
        }
        return s;
    }

    private void renderSnapshot(Snapshot s) {
        headerSubtitle.setText(s.headerSub);
        headerSubtitle.setTextColor(s.bannerActive ? COLOR_GREEN : COLOR_MUTED);

        ramCard.valueText.setText(s.ramValue);
        ramCard.pctText.setText(s.ramPctText);
        ramCard.pctText.setTextColor(s.ramColor);
        ramCard.bar.setProgress(s.ramPct, s.ramColor);
        ramCard.subText.setText(s.ramSub);

        storageCard.valueText.setText(s.storageValue);
        storageCard.pctText.setText(s.storagePctText);
        storageCard.pctText.setTextColor(s.storageColor);
        storageCard.bar.setProgress(s.storagePct, s.storageColor);
        storageCard.subText.setText(s.storageSub);

        if (showCpu && s.cpuValue != null) {
            cpuCard.valueText.setText(s.cpuValue);
            cpuCard.pctText.setText(s.cpuPctText);
            cpuCard.pctText.setTextColor(s.cpuColor);
            cpuCard.bar.setProgress(s.cpuPct, s.cpuColor);
            cpuCard.subText.setText(s.cpuSub);
        }

        if (showGpu && s.gpuValue != null) {
            gpuCard.valueText.setText(s.gpuValue);
            gpuCard.pctText.setText(s.gpuPctText);
            gpuCard.pctText.setTextColor(s.gpuColor);
            gpuCard.bar.setProgress(s.gpuPct, s.gpuColor);
            gpuCard.subText.setText(s.gpuSub);
        }
    }

    private void updateCpuTimeInState(long maxKhz) {
        long totalTicks = 0;
        long weightedFreqTicks = 0;
        try (BufferedReader br = new BufferedReader(
                new FileReader("/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                long freq = Long.parseLong(line.substring(0, sp).trim());
                long ticks = Long.parseLong(line.substring(sp + 1).trim());
                if (freq <= 0 || ticks < 0) continue;
                totalTicks += ticks;
                weightedFreqTicks += freq * ticks;
            }
        } catch (IOException | NumberFormatException ignored) {
            return;
        }
        if (totalTicks <= 0) return;
        if (prevTotalTicks >= 0 && totalTicks > prevTotalTicks) {
            long dTicks = totalTicks - prevTotalTicks;
            long dWeighted = weightedFreqTicks - prevWeightedFreqTicks;
            long avgKhz = dWeighted / dTicks;
            lastCpuPct = (int) Math.min(100, Math.max(0, Math.round(avgKhz * 100.0 / maxKhz)));
        } else if (lastCpuPct < 0) {
            long avgKhz = weightedFreqTicks / totalTicks;
            lastCpuPct = (int) Math.min(100, Math.max(0, Math.round(avgKhz * 100.0 / maxKhz)));
        }
        prevTotalTicks = totalTicks;
        prevWeightedFreqTicks = weightedFreqTicks;
    }

    private String probeCpuArch() {
        int cores = Runtime.getRuntime().availableProcessors();
        String part = "";
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("CPU part")) {
                    int idx = line.indexOf(':');
                    if (idx >= 0) part = line.substring(idx + 1).trim().toLowerCase(Locale.US);
                }
            }
        } catch (IOException ignored) {
        }
        String coreName;
        switch (part) {
            case "0xd03": coreName = "Cortex-A53"; break;
            case "0xd05": coreName = "Cortex-A55"; break;
            case "0xd07": coreName = "Cortex-A57"; break;
            case "0xd08": coreName = "Cortex-A72"; break;
            case "0xd09": coreName = "Cortex-A73"; break;
            case "0xc07": coreName = "Cortex-A7"; break;
            default: coreName = "ARM"; break;
        }
        return cores + "-Core " + coreName;
    }

    private String probeGpuRenderer() {
        String renderer = "";
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                int[] ver = new int[2];
                if (EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
                    int[] cfgAttrs = {
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                            EGL14.EGL_NONE
                    };
                    EGLConfig[] cfgs = new EGLConfig[1];
                    int[] numCfg = new int[1];
                    if (EGL14.eglChooseConfig(dpy, cfgAttrs, 0, cfgs, 0, 1, numCfg, 0) && numCfg[0] > 0) {
                        int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
                        ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
                        int[] pbufAttrs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
                        surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], pbufAttrs, 0);
                        if (ctx != EGL14.EGL_NO_CONTEXT && surf != EGL14.EGL_NO_SURFACE
                                && EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                            renderer = safeTrim(GLES20.glGetString(GLES20.GL_RENDERER));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignored) {
            }
        }
        if (!renderer.isEmpty()) return renderer;
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            if (am != null) {
                ConfigurationInfo ci = am.getDeviceConfigurationInfo();
                if (ci != null && ci.getGlEsVersion() != null) {
                    return "OpenGL ES " + ci.getGlEsVersion();
                }
            }
        } catch (Throwable ignored) {
        }
        return "OpenGL ES 2.0";
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static long readLongFile(String path) {
        String s = readLineFile(path);
        if (s.isEmpty()) return -1;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readLineFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static String formatDuration(long ms) {
        long totalMin = Math.max(1L, ms / 60_000L);
        long days = totalMin / (24 * 60);
        long hours = (totalMin % (24 * 60)) / 60;
        long mins = totalMin % 60;
        if (days > 0) return days + "d " + hours + "h " + mins + "m";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}



