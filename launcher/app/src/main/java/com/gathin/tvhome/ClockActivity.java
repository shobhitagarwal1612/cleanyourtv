package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * The clock screensaver shown by Home itself after home_idle_clock_minutes without input. Some TVs' own
 * screens (CVTE's live TV) do not keep the screen on, so the system screensaver would cut into programmes;
 * this one only ever starts from Home. Any key returns to Home.
 */
public class ClockActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    private ClockScene scene;
    private ChillView chillView;
    private ChillAudio chillAudio;
    private boolean consumeVolumeKeys;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_clock);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Config cfg = Config.load(this);
        consumeVolumeKeys = cfg.consumeVolumeKeys;
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        android.view.ViewGroup root = (android.view.ViewGroup) ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        chillAudio = new ChillAudio(this);
        chillView = new ChillView(this, chillAudio.isRadioAvailable());
        boolean vinyl = getIntent() != null && getIntent().getBooleanExtra("vinyl_lounge", false);
        if (vinyl) {
            chillView.enableVinylLounge();
        }
        root.addView(chillView, 1, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        scene = new ClockScene(getWindow().getDecorView());
        scene.setAmbientVisible(false);
        scene.setAvoidRightSide(vinyl);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
        chillView.start();
        chillAudio.start(chillView.getMood());
    }

    @Override
    protected void onPause() {
        chillAudio.stop();
        chillView.stop();
        scene.stop();
        super.onPause();
        finish();   // never left behind in the back stack
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
            return super.dispatchKeyEvent(event);
        }
        return chillView.handleKey(event, chillAudio, scene, this::finish);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
