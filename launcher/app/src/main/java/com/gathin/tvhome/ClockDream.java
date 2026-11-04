package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * System Daydream / Screensaver service: runs the full interactive Chill Mode (8 dedicated
 * Live Radio FM & Nature screens with random cool artwork and D-pad controls) whenever the
 * TV goes idle.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;
    private ChillView chillView;
    private ChillAudio chillAudio;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        Config cfg = Config.load(this);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);
        ViewGroup root = (ViewGroup) ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        chillAudio = new ChillAudio(this);
        chillView = new ChillView(this, chillAudio.isRadioAvailable());
        root.addView(chillView, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scene = new ClockScene(getWindow().getDecorView());
        scene.setAmbientVisible(false);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
        chillView.start();
        chillAudio.start(chillView.getMood());
    }

    @Override
    public void onDreamingStopped() {
        if (chillAudio != null) chillAudio.stop();
        if (chillView != null) chillView.stop();
        if (scene != null) scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (chillAudio != null) chillAudio.stop();
        if (chillView != null) chillView.stop();
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        return chillView.handleKey(event, chillAudio, scene, this::finish);
    }
}
