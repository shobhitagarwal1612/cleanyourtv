package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * Audio controller for Chill Mode across 10 dedicated screens + Zero-File Procedural Soundscapes:
 * - Screens 0..3: Procedural Nature Audio (Ocean Waves, Night Wind/Aurora, Cozy Downpour, Crackling Fire).
 * - Screens 4..7: Live Internet Radio FM stations (Groove Salad, Drone Zone, Lush, Deep Space One),
 *   streamed via the companion com.gathin.chillradio app so the launcher keeps zero network permissions.
 * - Screen 8: ISS Orbital View (soft procedural cosmic orbital pad).
 * - Screen 9: The Frame Gallery (silent dark museum painting mode).
 * - Play/Pause cycles Zero-File Stereo Procedural Soundscapes (Soft Rain · Deep Brown Noise · Soft Hearth)
 *   over any idle screen when no radio stream is playing.
 */
final class ChillAudio {

    static final int MOOD_OCEAN = 0;
    static final int MOOD_AURORA = 1;
    static final int MOOD_RAIN = 2;
    static final int MOOD_FIREPLACE = 3;
    static final int MOOD_RADIO_GROOVE = 4;
    static final int MOOD_RADIO_LUSH = 6;
    static final int MOOD_RADIO_SPACE = 7;
    static final int MOOD_FRAME_ORBITAL = 8;
    static final int MOOD_FRAME_MUSEUM = 9;

    static final int SOUNDSCAPE_OFF = 0;
    static final int SOUNDSCAPE_RAIN = 1;
    static final int SOUNDSCAPE_BROWN = 2;
    static final int SOUNDSCAPE_HEARTH = 3;

    private static final String RADIO_PKG = "com.gathin.chillradio";
    private static final String RADIO_ACTION = "com.gathin.chillradio.CONTROL";

    private static final int SAMPLE_RATE = 22050;
    private static final int CHUNK_FRAMES = 1024;

    private final Context ctx;
    private final boolean radioAvailable;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingPlay;
    private volatile boolean running;
    private volatile int mood;
    private volatile int soundscape = SOUNDSCAPE_OFF;
    private volatile boolean muted = false;
    private boolean radioPlaying = false;
    private int activeRadioStation = -1;
    private Thread worker;

    ChillAudio(Context context) {
        this.ctx = context.getApplicationContext();
        this.radioAvailable = isRadioInstalled(this.ctx);
        this.mood = this.radioAvailable ? MOOD_RADIO_GROOVE : MOOD_OCEAN;
    }

    private static boolean isRadioInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(RADIO_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    boolean isRadioAvailable() {
        return radioAvailable;
    }

    static boolean isRadioMood(int m) {
        return m >= MOOD_RADIO_GROOVE && m <= MOOD_RADIO_SPACE;
    }

    static boolean isSilentFrameMood(int m) {
        return m == MOOD_FRAME_MUSEUM;
    }

    int getSoundscape() {
        return soundscape;
    }

    boolean isMuted() {
        return muted;
    }

    static String soundscapeLabel(int sc) {
        switch (sc) {
            case SOUNDSCAPE_RAIN: return "DSP: Soft Rain";
            case SOUNDSCAPE_BROWN: return "DSP: Deep Brown Noise";
            case SOUNDSCAPE_HEARTH: return "DSP: Soft Hearth";
            default: return "";
        }
    }

    void start(int initialMood) {
        mood = initialMood;
        running = true;
        syncAudioState();
    }

    void setMood(int newMood) {
        mood = newMood;
        if (isRadioMood(newMood) || isSilentFrameMood(newMood)) {
            soundscape = SOUNDSCAPE_OFF;
        }
        syncAudioState();
    }

    boolean toggleMute() {
        muted = !muted;
        syncAudioState();
        return muted;
    }

    /**
     * Cycles the Zero-File Procedural Soundscape (Soft Rain -> Deep Brown Noise -> Soft Hearth -> Off).
     * When a soundscape is chosen while a radio station is playing, mutes the radio stream so the
     * offline 32 KB stereo DSP soundscape plays cleanly.
     */
    int cycleSoundscape() {
        soundscape = (soundscape + 1) % 4;
        if (soundscape != SOUNDSCAPE_OFF) {
            muted = false;
        }
        syncAudioState();
        return soundscape;
    }

    void stop() {
        running = false;
        cancelPendingPlay();
        stopWorker();
        if (radioPlaying) {
            sendRadioCmd("stop", 0, false);
            radioPlaying = false;
            activeRadioStation = -1;
        }
    }

    private void cancelPendingPlay() {
        if (pendingPlay != null) {
            mainHandler.removeCallbacks(pendingPlay);
            pendingPlay = null;
        }
    }

    private boolean needsProceduralSynth() {
        if (!running || muted) return false;
        if (soundscape != SOUNDSCAPE_OFF) return true;
        return !isRadioMood(mood) && !isSilentFrameMood(mood);
    }

    private synchronized void syncAudioState() {
        if (radioAvailable) {
            if (running && isRadioMood(mood)) {
                int station = mood - MOOD_RADIO_GROOVE;
                boolean silenceRadio = muted || soundscape != SOUNDSCAPE_OFF;
                if (radioPlaying && station == activeRadioStation) {
                    cancelPendingPlay();
                    sendRadioCmd("mute", station, silenceRadio);
                } else if (!silenceRadio) {
                    boolean firstStart = !radioPlaying;
                    radioPlaying = true;
                    activeRadioStation = station;
                    cancelPendingPlay();
                    if (firstStart) {
                        sendRadioCmd("play", station, false);
                    } else {
                        pendingPlay = () -> {
                            synchronized (ChillAudio.this) {
                                pendingPlay = null;
                                if (running && isRadioMood(mood) && !muted && soundscape == SOUNDSCAPE_OFF) {
                                    sendRadioCmd("play", mood - MOOD_RADIO_GROOVE, false);
                                }
                            }
                        };
                        mainHandler.postDelayed(pendingPlay, 180L);
                    }
                } else if (radioPlaying) {
                    sendRadioCmd("mute", activeRadioStation, true);
                }
            } else if (radioPlaying) {
                cancelPendingPlay();
                radioPlaying = false;
                activeRadioStation = -1;
                sendRadioCmd("stop", 0, false);
            }
        }
        if (needsProceduralSynth()) {
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(this::loop, "chill-audio");
                worker.setPriority(Thread.MIN_PRIORITY + 1);
                worker.start();
            }
        } else {
            stopWorker();
        }
    }

    private synchronized void stopWorker() {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    private void sendRadioCmd(String cmd, int station, boolean isMuted) {
        if (!radioAvailable) return;
        try {
            Intent i = new Intent(RADIO_ACTION);
            i.setComponent(new ComponentName(RADIO_PKG, RADIO_PKG + ".RadioReceiver"));
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra("cmd", cmd);
            i.putExtra("station", station);
            i.putExtra("muted", isMuted);
            ctx.sendBroadcast(i);
        } catch (RuntimeException ignored) {
        }
    }

    private void loop() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, 32 * 1024);
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (RuntimeException e) {
            return;
        }

        short[] pcm = new short[CHUNK_FRAMES * 2];
        Random rng = new Random(0xC0FFEE);
        double phase = 0.0;
        double lp1L = 0.0, lp2L = 0.0, brownL = 0.0;
        double lp1R = 0.0, lp2R = 0.0, brownR = 0.0;
        double popEnv = 0.0, popFreq = 900.0, popPan = 0.5;

        try {
            while (running && !Thread.currentThread().isInterrupted() && needsProceduralSynth()) {
                int m = mood;
                int sc = soundscape;
                for (int i = 0; i < CHUNK_FRAMES; i++) {
                    double whiteL = rng.nextDouble() * 2.0 - 1.0;
                    double whiteR = rng.nextDouble() * 2.0 - 1.0;
                    brownL = (brownL + 0.024 * whiteL) * 0.976;
                    brownR = (brownR + 0.024 * whiteR) * 0.976;
                    lp1L += 0.08 * (whiteL - lp1L);
                    lp2L += 0.02 * (lp1L - lp2L);
                    lp1R += 0.08 * (whiteR - lp1R);
                    lp2R += 0.02 * (lp1R - lp2R);

                    double outL;
                    double outR;

                    int synthMode;
                    if (sc == SOUNDSCAPE_RAIN) synthMode = MOOD_RAIN;
                    else if (sc == SOUNDSCAPE_HEARTH) synthMode = MOOD_FIREPLACE;
                    else if (sc == SOUNDSCAPE_BROWN) synthMode = -100;
                    else synthMode = m;

                    if (synthMode == -100) {
                        double warmth = 0.82 + 0.18 * Math.sin(phase * 2.0 * Math.PI * 0.08);
                        double sub = 0.06 * Math.sin(phase * 2.0 * Math.PI * 55.0);
                        outL = (brownL * 0.62 + lp2L * 0.14 + sub) * warmth;
                        outR = (brownR * 0.62 + lp2R * 0.14 + sub) * warmth;
                    } else if (synthMode == MOOD_OCEAN) {
                        double wave = 0.5 + 0.5 * Math.sin(phase * 2.0 * Math.PI * 0.11);
                        double swell = 0.18 + 0.82 * Math.pow(wave, 1.8);
                        outL = (lp2L * (1.0 - 0.5 * wave) + lp1L * (0.35 * wave)) * swell * 0.42;
                        outR = (lp2R * (1.0 - 0.5 * wave) + lp1R * (0.35 * wave)) * swell * 0.42;
                    } else if (synthMode == MOOD_AURORA || synthMode == MOOD_FRAME_ORBITAL) {
                        double breath = 0.65 + 0.35 * Math.sin(phase * 2.0 * Math.PI * 0.07);
                        double padL = 0.45 * Math.sin(phase * 2.0 * Math.PI * 87.31)
                                + 0.30 * Math.sin(phase * 2.0 * Math.PI * 130.81)
                                + 0.16 * Math.sin(phase * 2.0 * Math.PI * 196.00);
                        double padR = 0.45 * Math.sin(phase * 2.0 * Math.PI * 87.55)
                                + 0.30 * Math.sin(phase * 2.0 * Math.PI * 131.05)
                                + 0.12 * Math.sin(phase * 2.0 * Math.PI * 220.00);
                        outL = (padL * 0.11 * breath) + (lp2L * 0.14 * breath);
                        outR = (padR * 0.11 * breath) + (lp2R * 0.14 * breath);
                    } else if (synthMode == MOOD_RAIN) {
                        if (popEnv < 0.001 && rng.nextInt(380) == 0) {
                            popEnv = 0.18 + rng.nextDouble() * 0.22;
                            popFreq = 650.0 + rng.nextDouble() * 750.0;
                            popPan = 0.15 + rng.nextDouble() * 0.70;
                        }
                        double drop = popEnv * Math.sin(phase * 2.0 * Math.PI * popFreq);
                        popEnv *= 0.965;
                        outL = (lp1L * 0.22 + brownL * 0.32) + drop * (1.0 - popPan) * 0.26;
                        outR = (lp1R * 0.22 + brownR * 0.32) + drop * popPan * 0.26;
                    } else {
                        if (popEnv < 0.001 && rng.nextInt(260) == 0) {
                            popEnv = 0.35 + rng.nextDouble() * 0.45;
                            popFreq = 450.0 + rng.nextDouble() * 1100.0;
                            popPan = 0.20 + rng.nextDouble() * 0.60;
                        }
                        double crackle = popEnv * (whiteL * 0.6 + Math.sin(phase * 2.0 * Math.PI * popFreq) * 0.4);
                        popEnv *= 0.94;
                        outL = brownL * 0.45 + lp2L * 0.12 + crackle * (1.0 - popPan) * 0.36;
                        outR = brownR * 0.45 + lp2R * 0.12 + crackle * popPan * 0.36;
                    }

                    phase += 1.0 / SAMPLE_RATE;
                    if (phase > 3600.0) phase -= 3600.0;

                    int vL = (int) Math.round(outL * 14000.0);
                    int vR = (int) Math.round(outR * 14000.0);
                    pcm[i * 2] = (short) Math.max(-32768, Math.min(32767, vL));
                    pcm[i * 2 + 1] = (short) Math.max(-32768, Math.min(32767, vR));
                }
                track.write(pcm, 0, pcm.length);
            }
        } catch (RuntimeException ignored) {
        } finally {
            try {
                track.stop();
            } catch (RuntimeException ignored) {
            }
            track.release();
        }
    }
}
