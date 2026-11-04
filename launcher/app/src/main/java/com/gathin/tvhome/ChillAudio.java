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
 * Audio controller for Chill Mode across 11 dedicated screens + Zero-File Procedural Soundscapes:
 * - Screens 0..4: Procedural Nature Audio (Ocean Waves, Night Wind/Aurora, Cozy Downpour, Crackling Fire, Kyoto Bamboo Zen).
 * - Screens 5..8: Live Internet Radio FM stations (Groove Salad, Drone Zone, Lush, Deep Space One),
 *   streamed via the companion com.gathin.chillradio app so the launcher keeps zero network permissions.
 * - Screen 9: ISS Orbital View (soft procedural cosmic orbital pad).
 * - Screen 10: The Frame Gallery (silent dark museum painting mode).
 * - Up/Play/Menu cycles Zero-File Stereo Procedural Soundscapes (+ Soft Rain · + Crackling Hearth · + Deep Brown Noise)
 *   which can either play standalone or layer gently underneath Live Radio FM streams ("Rain over Radio").
 */
final class ChillAudio {

    static final int MOOD_OCEAN = 0;
    static final int MOOD_AURORA = 1;
    static final int MOOD_RAIN = 2;
    static final int MOOD_FIREPLACE = 3;
    static final int MOOD_BAMBOO = 4;
    static final int MOOD_RADIO_GROOVE = 5;
    static final int MOOD_RADIO_DRONE = 6;
    static final int MOOD_RADIO_LUSH = 7;
    static final int MOOD_RADIO_SPACE = 8;
    static final int MOOD_FRAME_ORBITAL = 9;
    static final int MOOD_FRAME_MUSEUM = 10;

    static final int SOUNDSCAPE_OFF = 0;
    static final int SOUNDSCAPE_RAIN = 1;
    static final int SOUNDSCAPE_HEARTH = 2;
    static final int SOUNDSCAPE_BROWN = 3;

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
            case SOUNDSCAPE_RAIN: return "+ Soft Rain";
            case SOUNDSCAPE_HEARTH: return "+ Crackling Hearth";
            case SOUNDSCAPE_BROWN: return "+ Deep Brown Noise";
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
        if (isSilentFrameMood(newMood)) {
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
     * Cycles the Zero-File Procedural Soundscape (+ Soft Rain -> + Crackling Hearth -> + Deep Brown Noise -> Off).
     * When a soundscape is active during a Live Radio FM mood, both the radio stream AND the gentle DSP
     * soundscape play simultaneously ("Rain over Radio").
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
                boolean silenceRadio = muted;
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
                                if (running && isRadioMood(mood) && !muted) {
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

    private static final double[] BAMBOO_FREQS = {
            185.00, 220.00, 246.94, 277.18, 329.63, 369.99, 440.00, 554.37
    };

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
        double lp1L = 0.0, lp2L = 0.0, brownL = 0.0, bpL = 0.0;
        double lp1R = 0.0, lp2R = 0.0, brownR = 0.0, bpR = 0.0;
        double snapEnv = 0.0, snapPan = 0.5, snapTone = 0.3;
        double chimeEnv = 0.0, chimeFreq = 277.18, chimePan = 0.5;
        double masterGain = 0.0;
        int lastModeKey = -999;

        try {
            while (running && !Thread.currentThread().isInterrupted() && needsProceduralSynth()) {
                int m = mood;
                int sc = soundscape;
                boolean underRadio = isRadioMood(m) && sc != SOUNDSCAPE_OFF;
                double targetGain = underRadio ? 0.52 : 1.0;

                int synthMode;
                if (sc == SOUNDSCAPE_RAIN) synthMode = MOOD_RAIN;
                else if (sc == SOUNDSCAPE_HEARTH) synthMode = MOOD_FIREPLACE;
                else if (sc == SOUNDSCAPE_BROWN) synthMode = -100;
                else synthMode = m;

                if (synthMode != lastModeKey) {
                    lastModeKey = synthMode;
                    masterGain = Math.min(masterGain, 0.15);
                }

                for (int i = 0; i < CHUNK_FRAMES; i++) {
                    masterGain += (targetGain - masterGain) * 0.00035;

                    double whiteL = rng.nextDouble() * 2.0 - 1.0;
                    double whiteR = rng.nextDouble() * 2.0 - 1.0;
                    brownL = (brownL + 0.022 * whiteL) * 0.978;
                    brownR = (brownR + 0.022 * whiteR) * 0.978;
                    lp1L += 0.085 * (whiteL - lp1L);
                    lp2L += 0.022 * (lp1L - lp2L);
                    lp1R += 0.085 * (whiteR - lp1R);
                    lp2R += 0.022 * (lp1R - lp2R);
                    // Band-passed crisp droplet/crackling texture (high-passed lp1)
                    bpL = lp1L - lp2L;
                    bpR = lp1R - lp2R;

                    double outL;
                    double outR;

                    if (synthMode == -100) {
                        double warmth = 0.84 + 0.16 * Math.sin(phase * 2.0 * Math.PI * 0.06);
                        outL = (brownL * 0.68 + lp2L * 0.15) * warmth;
                        outR = (brownR * 0.68 + lp2R * 0.15) * warmth;
                    } else if (synthMode == MOOD_OCEAN) {
                        // Asymmetric dual-swell ocean surf: slow build, foamy crest, receding wash
                        double w1 = 0.5 + 0.5 * Math.sin(phase * 2.0 * Math.PI * 0.095 + 0.35 * Math.sin(phase * 2.0 * Math.PI * 0.095));
                        double w2 = 0.5 + 0.5 * Math.sin(phase * 2.0 * Math.PI * 0.062 + 1.7);
                        double crest = Math.pow(w1, 2.4);
                        double wash = 0.22 + 0.58 * w2;
                        outL = brownL * 0.36 * wash + lp2L * 0.32 * (0.3 + 0.7 * w1) + bpL * 0.24 * crest;
                        outR = brownR * 0.36 * wash + lp2R * 0.32 * (0.3 + 0.7 * w2) + bpR * 0.24 * crest;
                    } else if (synthMode == MOOD_AURORA || synthMode == MOOD_FRAME_ORBITAL) {
                        // Resonant nocturnal wind + warm slow-drifting fifths pad
                        double windL = 0.55 + 0.45 * Math.sin(phase * 2.0 * Math.PI * 0.055);
                        double windR = 0.55 + 0.45 * Math.cos(phase * 2.0 * Math.PI * 0.048);
                        double padL = 0.38 * Math.sin(phase * 2.0 * Math.PI * 73.42)
                                + 0.26 * Math.sin(phase * 2.0 * Math.PI * 110.00)
                                + 0.14 * Math.sin(phase * 2.0 * Math.PI * 164.81);
                        double padR = 0.38 * Math.sin(phase * 2.0 * Math.PI * 73.60)
                                + 0.26 * Math.sin(phase * 2.0 * Math.PI * 110.22)
                                + 0.14 * Math.sin(phase * 2.0 * Math.PI * 165.05);
                        outL = brownL * 0.38 * windL + lp2L * 0.18 * windR + padL * 0.075 * windL;
                        outR = brownR * 0.38 * windR + lp2R * 0.18 * windL + padR * 0.075 * windR;
                    } else if (synthMode == MOOD_BAMBOO) {
                        // Kyoto Bamboo: soft leaves rustle + sparse resonant singing-bowl chime
                        if (chimeEnv < 0.0008 && rng.nextInt(SAMPLE_RATE * 4) == 0) {
                            chimeEnv = 0.22 + rng.nextDouble() * 0.16;
                            chimeFreq = BAMBOO_FREQS[rng.nextInt(BAMBOO_FREQS.length)];
                            chimePan = 0.25 + rng.nextDouble() * 0.50;
                        }
                        double chime = chimeEnv * (Math.sin(phase * 2.0 * Math.PI * chimeFreq) * 0.75
                                + Math.sin(phase * 2.0 * Math.PI * chimeFreq * 2.756) * 0.25 * chimeEnv);
                        chimeEnv *= 0.999935;
                        double breeze = 0.65 + 0.35 * Math.sin(phase * 2.0 * Math.PI * 0.08);
                        outL = (lp2L * 0.20 + brownL * 0.22) * breeze + chime * (1.0 - chimePan) * 0.34;
                        outR = (lp2R * 0.20 + brownR * 0.22) * breeze + chime * chimePan * 0.34;
                    } else if (synthMode == MOOD_RAIN) {
                        // Organic rain: steady patter + short 3ms band-passed water droplet impacts (no sonar sine tones)
                        if (snapEnv < 0.002 && rng.nextInt(95) == 0) {
                            snapEnv = 0.22 + rng.nextDouble() * 0.32;
                            snapPan = 0.12 + rng.nextDouble() * 0.76;
                            snapTone = 0.35 + rng.nextDouble() * 0.65;
                        }
                        double dropL = snapEnv * (bpL * snapTone + lp1L * (1.0 - snapTone));
                        double dropR = snapEnv * (bpR * snapTone + lp1R * (1.0 - snapTone));
                        snapEnv *= 0.925;
                        outL = brownL * 0.34 + lp2L * 0.24 + bpL * 0.11 + dropL * (1.0 - snapPan) * 0.42;
                        outR = brownR * 0.34 + lp2R * 0.24 + bpR * 0.11 + dropR * snapPan * 0.42;
                    } else {
                        // Cozy Fireplace: deep warm chimney draft + crisp 1.5ms dry wood micro-snaps
                        if (snapEnv < 0.002 && rng.nextInt(140) == 0) {
                            snapEnv = 0.42 + rng.nextDouble() * 0.55;
                            snapPan = 0.20 + rng.nextDouble() * 0.60;
                        }
                        double snap = snapEnv * (whiteL * 0.55 + bpL * 0.45);
                        snapEnv *= 0.885;
                        double draft = 0.82 + 0.18 * Math.sin(phase * 2.0 * Math.PI * 0.19);
                        outL = (brownL * 0.48 + lp2L * 0.14) * draft + snap * (1.0 - snapPan) * 0.44;
                        outR = (brownR * 0.48 + lp2R * 0.14) * draft + snap * snapPan * 0.44;
                    }

                    phase += 1.0 / SAMPLE_RATE;
                    if (phase > 3600.0) phase -= 3600.0;

                    int vL = (int) Math.round(outL * masterGain * 14000.0);
                    int vR = (int) Math.round(outR * masterGain * 14000.0);
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
