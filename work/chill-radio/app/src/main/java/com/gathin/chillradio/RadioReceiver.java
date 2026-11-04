package com.gathin.chillradio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams 24/7 commercial-free internet chill radio stations for Chill Mode when instructed by the launcher.
 * Keeps the INTERNET permission isolated in this companion APK so the launcher stays 100% offline.
 */
public final class RadioReceiver extends BroadcastReceiver {

    private static final String TAG = "ChillRadio";
    private static final String[] STATIONS = {
            "https://ice1.somafm.com/groovesalad-128-mp3",   // 0: Groove Salad (Ambient/Downtempo Chill Beats)
            "https://ice1.somafm.com/dronezone-128-mp3",     // 1: Drone Zone (Atmospheric Ambient Space)
            "https://ice1.somafm.com/lush-128-mp3",          // 2: Lush (Mellow Lo-Fi & Dreamy Vocals)
            "https://ice1.somafm.com/deepspaceone-128-mp3"   // 3: Deep Space One (Deep Warm Ambient)
    };

    private static final ExecutorService RELEASE_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chill-radio-release");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AudioAttributes AUDIO_ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private static MediaPlayer player;
    private static boolean prepared = false;
    private static int currentStation = -1;
    private static boolean muted = false;
    private static boolean ducked = false;
    private static Runnable retryTask;
    private static AudioManager audioManager;
    private static AudioFocusRequest focusRequest;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String cmd = intent.getStringExtra("cmd");
        Context appCtx = context.getApplicationContext();
        if ("stop".equals(cmd)) {
            stopPlayer();
            try {
                appCtx.stopService(new Intent(appCtx, RadioService.class));
            } catch (Throwable ignored) {
            }
            return;
        }
        if ("play".equals(cmd)) {
            try {
                Intent svc = new Intent(appCtx, RadioService.class).putExtras(intent);
                appCtx.startService(svc);
                return;
            } catch (Throwable ignored) {
                // Fall back to direct in-process playback if background service start is restricted
            }
        }
        handle(appCtx, intent);
    }

    static void handle(Context context, Intent intent) {
        if (intent == null) return;
        String cmd = intent.getStringExtra("cmd");
        if ("stop".equals(cmd)) {
            stopPlayer();
            return;
        }
        if ("mute".equals(cmd)) {
            synchronized (RadioReceiver.class) {
                muted = intent.getBooleanExtra("muted", false);
                applyVolumeLocked();
            }
            return;
        }
        if ("play".equals(cmd)) {
            int station = intent.getIntExtra("station", 0);
            synchronized (RadioReceiver.class) {
                muted = intent.getBooleanExtra("muted", false);
            }
            playStation(context, station, false);
        }
    }

    private static synchronized void applyVolumeLocked() {
        if (player != null && prepared) {
            try {
                float vol = muted ? 0f : (ducked ? 0.2f : 0.85f);
                player.setVolume(vol, vol);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static synchronized void requestFocusLocked(Context context) {
        if (context == null) return;
        if (audioManager == null) {
            audioManager = context.getSystemService(AudioManager.class);
        }
        if (audioManager == null) return;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AUDIO_ATTRS)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        synchronized (RadioReceiver.class) {
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                                ducked = true;
                                applyVolumeLocked();
                            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                                ducked = false;
                                applyVolumeLocked();
                                if (player != null && prepared && !muted) {
                                    try {
                                        if (!player.isPlaying()) player.start();
                                    } catch (RuntimeException ignored) {
                                    }
                                }
                            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                if (player != null && prepared) {
                                    try {
                                        if (player.isPlaying()) player.pause();
                                    } catch (RuntimeException ignored) {
                                    }
                                }
                            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                                stopPlayer();
                            }
                        }
                    }, MAIN)
                    .build();
        }
        try {
            audioManager.requestAudioFocus(focusRequest);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void abandonFocusLocked() {
        ducked = false;
        if (audioManager != null && focusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } catch (Throwable ignored) {
            }
        }
    }

    private static synchronized void cancelRetryLocked() {
        if (retryTask != null) {
            MAIN.removeCallbacks(retryTask);
            retryTask = null;
        }
    }

    private static synchronized void scheduleRetryLocked(Context context, int idx) {
        cancelRetryLocked();
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        retryTask = () -> {
            synchronized (RadioReceiver.class) {
                retryTask = null;
                if (currentStation == idx) {
                    playStation(appCtx, idx, true);
                }
            }
        };
        MAIN.postDelayed(retryTask, 3000L);
    }

    private static synchronized void playStation(Context context, int index, boolean forceRestart) {
        int idx = ((index % STATIONS.length) + STATIONS.length) % STATIONS.length;
        if (!forceRestart && player != null && currentStation == idx) {
            applyVolumeLocked();
            if (prepared) {
                try {
                    if (!player.isPlaying()) player.start();
                } catch (RuntimeException ignored) {
                }
            }
            return;
        }
        releaseCurrentPlayerLocked();
        cancelRetryLocked();
        requestFocusLocked(context);
        currentStation = idx;
        prepared = false;
        final String url = STATIONS[idx];
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(AUDIO_ATTRS);
            mp.setDataSource(url);
            mp.setOnPreparedListener(p -> {
                synchronized (RadioReceiver.class) {
                    if (p != player) {
                        RELEASE_EXEC.execute(() -> {
                            try {
                                p.release();
                            } catch (RuntimeException ignored) {
                            }
                        });
                        return;
                    }
                    prepared = true;
                    applyVolumeLocked();
                    try {
                        p.start();
                        Log.i(TAG, "Streaming station " + idx + ": " + url);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Failed to start prepared stream: " + e);
                    }
                }
            });
            mp.setOnCompletionListener(p -> {
                synchronized (RadioReceiver.class) {
                    if (p == player && currentStation == idx) {
                        Log.i(TAG, "Stream EOF on station " + idx + "; reconnecting in 3s");
                        scheduleRetryLocked(appCtx, idx);
                    }
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                synchronized (RadioReceiver.class) {
                    Log.w(TAG, "Stream error (" + what + ", " + extra + ") on " + url);
                    if (p == player && currentStation == idx) {
                        scheduleRetryLocked(appCtx, idx);
                    }
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to start stream: " + e);
            scheduleRetryLocked(appCtx, idx);
        }
    }

    private static synchronized void releaseCurrentPlayerLocked() {
        prepared = false;
        if (player != null) {
            MediaPlayer old = player;
            player = null;
            RELEASE_EXEC.execute(() -> {
                try {
                    old.reset();
                } catch (RuntimeException ignored) {
                }
                try {
                    old.release();
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    static synchronized void stopPlayer() {
        currentStation = -1;
        cancelRetryLocked();
        abandonFocusLocked();
        releaseCurrentPlayerLocked();
    }
}

