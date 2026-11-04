package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private TextView dailyWhisperChoice;
    private TextView idleActionChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;
    private int idleMinutes;

    private interface IntPickListener {
        void onPick(int index);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        idleMinutes = config.homeIdleClockMinutes;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (!animations) return;
            setAdvanced(!advancedEffects);
        });
        dailyWhisperChoice = findViewById(R.id.dailyWhisperChoice);
        dailyWhisperChoice.setOnClickListener(v -> showDailyWhisperPicker());
        idleActionChoice = findViewById(R.id.idleActionChoice);
        idleActionChoice.setOnClickListener(v -> showIdleActionPicker());
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY)
                    .remove(Config.DAILY_WHISPER_KEY).remove(Config.WHISPER_CAT_WEATHER)
                    .remove(Config.WHISPER_CAT_FESTIVALS).remove(Config.WHISPER_CAT_NEWS)
                    .remove(Config.WHISPER_CAT_QUOTES).remove(Config.IDLE_ACTION_KEY)
                    .remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            idleMinutes = current.homeIdleClockMinutes;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        wallpaperChoice.requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    static String prettyWallpaperName(File dir, String filename) {
        if (filename == null || filename.isEmpty()) return "";
        if (dir != null) {
            Map<String, String> captions = WallpaperCaptions.read(new File(dir, "wallpapers"));
            String caption = captions.get(filename);
            if (caption != null && !caption.isEmpty()) return caption;
        }
        String base = filename;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceFirst("^[0-9]+[-_\\s]*", "");
        String[] parts = base.split("[-_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.substring(0, 1).toUpperCase(Locale.US));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : filename;
    }

    private void showGlassSingleChoiceDialog(String title, String subtitle, String[] labels,
                                             String[] statuses, int checked, IntPickListener listener) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout panel = (LinearLayout) getLayoutInflater().inflate(R.layout.popup_inputs, null, false);
        int pad = Math.round(14 * density);
        panel.setPadding(pad, Math.round(14 * density), pad, Math.round(12 * density));
        LinearLayout rows = panel.findViewById(R.id.rows);

        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        header.setPadding(Math.round(12 * density), Math.round(4 * density), Math.round(12 * density), Math.round(2 * density));
        rows.addView(header);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(0x99FFFFFF);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            sub.setPadding(Math.round(12 * density), 0, Math.round(12 * density), Math.round(10 * density));
            rows.addView(sub);
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout listContainer = rows;
        if (labels.length > 6) {
            ScrollView scroll = new ScrollView(this);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(inner);
            rows.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(320 * density)));
            listContainer = inner;
        }

        View[] itemRows = new View[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            View row = getLayoutInflater().inflate(R.layout.item_input, listContainer, false);
            row.findViewById(R.id.icon).setVisibility(View.GONE);
            row.findViewById(R.id.dot).setVisibility(i == checked ? View.VISIBLE : View.GONE);
            ((TextView) row.findViewById(R.id.title)).setText(labels[i]);
            TextView status = row.findViewById(R.id.status);
            String st = statuses != null && i < statuses.length ? statuses[i] : null;
            if (st != null && !st.isEmpty()) {
                status.setText(st);
                status.setVisibility(View.VISIBLE);
            } else {
                status.setVisibility(View.GONE);
            }
            row.setOnClickListener(v -> {
                dialog.dismiss();
                listener.onPick(index);
            });
            itemRows[i] = row;
            listContainer.addView(row);
        }

        final View prevFocus = getCurrentFocus();
        if (prevFocus != null) prevFocus.clearFocus();
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            if (prevFocus != null) prevFocus.requestFocus();
        });
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.round(400 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        dialog.show();
        int focusIdx = checked >= 0 && checked < itemRows.length ? checked : 0;
        if (itemRows.length > 0) itemRows[focusIdx].requestFocus();
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);

        String[] labels = new String[images.size() + 2];
        String[] statuses = new String[images.size() + 2];
        labels[0] = getString(R.string.wallpaper_theme_default);
        statuses[0] = images.size() > 1 ? "Time-of-day" : "";
        for (int i = 0; i < images.size(); i++) {
            labels[i + 1] = prettyWallpaperName(dir, images.get(i).getName());
            statuses[i + 1] = "";
        }
        labels[images.size() + 1] = getString(R.string.wallpaper_none);
        statuses[images.size() + 1] = "Solid colour";

        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = 0;
        if ("none".equals(current)) {
            checked = images.size() + 1;
        } else if (current != null) {
            int idx = indexOf(images, current);
            if (idx >= 0) checked = idx + 1;
        }

        showGlassSingleChoiceDialog(
                getString(R.string.wallpaper_label),
                "Choose a fixed background or follow time-of-day rotation",
                labels, statuses, checked, which -> {
                    if (which == 0) {
                        choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    } else if (which == images.size() + 1) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which - 1).getName()).apply();
                        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    }
                    changed();
                    refresh();
                });
    }

    private static final String[] WHISPER_CAT_KEYS = {
            Config.WHISPER_CAT_WEATHER,
            Config.WHISPER_CAT_FESTIVALS,
            Config.WHISPER_CAT_NEWS,
            Config.WHISPER_CAT_QUOTES
    };

    private void showDailyWhisperPicker() {
        float density = getResources().getDisplayMetrics().density;
        String[] titles = {
                "Weather & AQI Alerts",
                "Festivals & Sky Events",
                "Top Daily Headline",
                "Daily Thought"
        };
        int[] icons = {
                R.drawable.ic_weather,
                R.drawable.ic_room_mode,
                R.drawable.ic_news,
                R.drawable.ic_quote
        };

        LinearLayout panel = (LinearLayout) getLayoutInflater().inflate(R.layout.popup_inputs, null, false);
        int pad = Math.round(14 * density);
        panel.setPadding(pad, Math.round(14 * density), pad, Math.round(12 * density));
        LinearLayout rows = panel.findViewById(R.id.rows);

        TextView header = new TextView(this);
        header.setText(R.string.daily_whisper_label);
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        header.setPadding(Math.round(12 * density), Math.round(4 * density), Math.round(12 * density), Math.round(2 * density));
        rows.addView(header);

        TextView sub = new TextView(this);
        sub.setText("Press OK to toggle each category on or off");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sub.setPadding(Math.round(12 * density), 0, Math.round(12 * density), Math.round(10 * density));
        rows.addView(sub);

        final View[] itemRows = new View[WHISPER_CAT_KEYS.length];
        Runnable syncStates = () -> {
            for (int i = 0; i < WHISPER_CAT_KEYS.length; i++) {
                boolean on = Config.isWhisperCategoryEnabled(choices, WHISPER_CAT_KEYS[i]);
                View r = itemRows[i];
                r.findViewById(R.id.dot).setVisibility(on ? View.VISIBLE : View.GONE);
                TextView status = r.findViewById(R.id.status);
                status.setVisibility(View.VISIBLE);
                status.setText(on ? R.string.on : R.string.off);
            }
        };

        for (int i = 0; i < WHISPER_CAT_KEYS.length; i++) {
            final String key = WHISPER_CAT_KEYS[i];
            View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(icons[i]);
            ((TextView) row.findViewById(R.id.title)).setText(titles[i]);
            row.setOnClickListener(v -> {
                boolean next = !Config.isWhisperCategoryEnabled(choices, key);
                choices.edit().putBoolean(key, next).apply();
                changed();
                syncStates.run();
                refresh();
            });
            itemRows[i] = row;
            rows.addView(row);
        }
        syncStates.run();

        final View prevFocus = getCurrentFocus();
        if (prevFocus != null) prevFocus.clearFocus();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            if (prevFocus != null) prevFocus.requestFocus();
        });
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.round(380 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        dialog.show();
        itemRows[0].requestFocus();
    }

    private String dailyWhisperLabel() {
        int enabled = 0;
        for (String key : WHISPER_CAT_KEYS) {
            if (Config.isWhisperCategoryEnabled(choices, key)) enabled++;
        }
        if (enabled == 0) return getString(R.string.off);
        if (enabled == WHISPER_CAT_KEYS.length) return getString(R.string.daily_whisper_all_short);
        return enabled + "/" + WHISPER_CAT_KEYS.length + " " + getString(R.string.on);
    }

    private static final String[] IDLE_ACTION_VALUES = {
            Config.IDLE_ACTION_POWER_OFF,
            Config.IDLE_ACTION_STANDBY,
            Config.IDLE_ACTION_CHILL_MODE,
            Config.IDLE_ACTION_BLACKOUT,
            Config.IDLE_ACTION_OFF
    };

    private void showIdleActionPicker() {
        String[] labels = {
                getString(R.string.idle_action_power_off_short),
                getString(R.string.idle_action_standby_short),
                getString(R.string.idle_action_chill_mode_short),
                getString(R.string.idle_action_blackout_short),
                getString(R.string.off)
        };
        String[] statuses = {
                "Cold boot on wake",
                "2s instant wake",
                "Ambient screensaver",
                "Instant any-key wake",
                "Never auto-sleep"
        };
        String current = choices.getString(Config.IDLE_ACTION_KEY, Config.IDLE_ACTION_DEFAULT);
        int checked = 0;
        for (int i = 0; i < IDLE_ACTION_VALUES.length; i++) {
            if (IDLE_ACTION_VALUES[i].equals(current)) {
                checked = i;
                break;
            }
        }
        int mins = Math.max(1, idleMinutes);
        showGlassSingleChoiceDialog(
                "When Home is idle (" + mins + " min)",
                "Automatic power or ambient action when Home is untouched",
                labels, statuses, checked, which -> {
                    choices.edit().putString(Config.IDLE_ACTION_KEY, IDLE_ACTION_VALUES[which]).apply();
                    changed();
                    refresh();
                });
    }

    private String idleActionLabel() {
        String action = choices.getString(Config.IDLE_ACTION_KEY, Config.IDLE_ACTION_DEFAULT);
        switch (action) {
            case Config.IDLE_ACTION_STANDBY:
                return getString(R.string.idle_action_standby_short);
            case Config.IDLE_ACTION_CHILL_MODE:
                return getString(R.string.idle_action_chill_mode_short);
            case Config.IDLE_ACTION_BLACKOUT:
                return getString(R.string.idle_action_blackout_short);
            case Config.IDLE_ACTION_OFF:
                return getString(R.string.off);
            case Config.IDLE_ACTION_POWER_OFF:
            default:
                return getString(R.string.idle_action_power_off_short);
        }
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        showGlassSingleChoiceDialog(
                getString(R.string.open_on_start_label),
                null, labels, null, checked, which -> {
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                });
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? prettyWallpaperName(Config.dir(this), wallpaperCurrent)
                        : getString(R.string.wallpaper_theme_default)));
        dailyWhisperChoice.setText(getString(R.string.daily_whisper_state, dailyWhisperLabel()));
        idleActionChoice.setText(getString(R.string.idle_action_state, idleActionLabel())
                .replace("(10 min)", "(" + Math.max(1, idleMinutes) + " min)"));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        boolean hasChoices = hasOnTvChoices();
        followThemeChoice.setSelected(false);
        followThemeChoice.setAlpha(hasChoices ? 1f : 0.55f);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(Config.DAILY_WHISPER_KEY)
                || choices.contains(Config.WHISPER_CAT_WEATHER) || choices.contains(Config.WHISPER_CAT_FESTIVALS)
                || choices.contains(Config.WHISPER_CAT_NEWS) || choices.contains(Config.WHISPER_CAT_QUOTES)
                || choices.contains(Config.IDLE_ACTION_KEY) || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
