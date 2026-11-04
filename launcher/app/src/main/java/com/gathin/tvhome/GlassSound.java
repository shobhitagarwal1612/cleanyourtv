package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * tvOS Tactile Glass Sound Design: synthesizes 3 tiny 44.1 kHz PCM WAV buffers (~30 KB total)
 * into SoundPool at startup so every remote key press has zero-latency tactile audio feedback:
 * - Soft wooden/glass tick on D-pad focus moves
 * - Damped low-frequency double-bump when hitting the edge of the dock or grid
 * - Warm crystalline glass chime + airy whoosh on app launch
 */
final class GlassSound {

    private static final int SAMPLE_RATE = 44100;

    private final SoundPool pool;
    private volatile int tickId;
    private volatile int bumpId;
    private volatile int launchId;
    private volatile boolean ready;

    GlassSound(Context context) {
        pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status == 0 && sampleId == launchId) {
                ready = true;
            }
        });
        final File cacheDir = context.getCacheDir();
        new Thread(() -> initSounds(cacheDir), "glass-sound-init").start();
    }

    private void initSounds(File dir) {
        try {
            File tickFile = new File(dir, "tvos_tick_v1.wav");
            File bumpFile = new File(dir, "tvos_bump_v1.wav");
            File launchFile = new File(dir, "tvos_launch_v1.wav");
            if (!tickFile.exists()) writeWav(tickFile, synthTick());
            if (!bumpFile.exists()) writeWav(bumpFile, synthBump());
            if (!launchFile.exists()) writeWav(launchFile, synthLaunch());
            tickId = pool.load(tickFile.getAbsolutePath(), 1);
            bumpId = pool.load(bumpFile.getAbsolutePath(), 1);
            launchId = pool.load(launchFile.getAbsolutePath(), 1);
        } catch (Throwable ignored) {
        }
    }

    void playTick() {
        if (ready && tickId != 0) {
            pool.play(tickId, 0.34f, 0.34f, 1, 0, 1.0f);
        }
    }

    void playBump() {
        if (ready && bumpId != 0) {
            pool.play(bumpId, 0.45f, 0.45f, 1, 0, 1.0f);
        }
    }

    void playLaunch() {
        if (ready && launchId != 0) {
            pool.play(launchId, 0.48f, 0.48f, 2, 0, 1.0f);
        }
    }

    void release() {
        ready = false;
        pool.release();
    }

    /**
     * Soft wooden/glass marimba tick (~32 ms): fast pitch-dropping woodblock body (680 -> 240 Hz)
     * plus a delicate crystalline glass tap transient (2280 Hz).
     */
    private static short[] synthTick() {
        int n = (int) (SAMPLE_RATE * 0.032);
        short[] pcm = new short[n];
        double phaseBody = 0.0;
        double phaseGlass = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double envBody = Math.exp(-t * 155.0) * Math.min(1.0, i / 28.0);
            double freqBody = 240.0 + 440.0 * Math.exp(-t * 240.0);
            phaseBody += 2.0 * Math.PI * freqBody / SAMPLE_RATE;

            double envGlass = Math.exp(-t * 340.0) * Math.min(1.0, i / 12.0);
            phaseGlass += 2.0 * Math.PI * 2280.0 / SAMPLE_RATE;

            double s = Math.sin(phaseBody) * envBody * 0.78 + Math.sin(phaseGlass) * envGlass * 0.22;
            pcm[i] = clamp16(s * 24000.0);
        }
        return pcm;
    }

    /**
     * Damped hollow boundary bump (~82 ms): two warm, low-frequency cushioned thuds (at 0 ms and 34 ms)
     * when pressing against the outer edge of the dock or grid.
     */
    private static short[] synthBump() {
        int n = (int) (SAMPLE_RATE * 0.082);
        short[] pcm = new short[n];
        double p1 = 0.0, p2 = 0.0;
        int delay = (int) (SAMPLE_RATE * 0.034);
        for (int i = 0; i < n; i++) {
            double t1 = (double) i / SAMPLE_RATE;
            double env1 = Math.exp(-t1 * 85.0) * Math.min(1.0, i / 45.0);
            double f1 = 88.0 + 62.0 * Math.exp(-t1 * 130.0);
            p1 += 2.0 * Math.PI * f1 / SAMPLE_RATE;
            double s = Math.sin(p1) * env1 * 0.80;

            if (i >= delay) {
                double t2 = (double) (i - delay) / SAMPLE_RATE;
                double env2 = Math.exp(-t2 * 95.0) * Math.min(1.0, (i - delay) / 45.0);
                double f2 = 74.0 + 48.0 * Math.exp(-t2 * 140.0);
                p2 += 2.0 * Math.PI * f2 / SAMPLE_RATE;
                s += Math.sin(p2) * env2 * 0.52;
            }
            pcm[i] = clamp16(Math.tanh(s * 1.3) * 25000.0);
        }
        return pcm;
    }

    /**
     * Warm whoosh/chime on app launch (~230 ms): airy filtered breath sweep layered with a warm
     * crystalline fifth + ninth glass bell chord (C5 + G5 + D6).
     */
    private static short[] synthLaunch() {
        int n = (int) (SAMPLE_RATE * 0.230);
        short[] pcm = new short[n];
        Random rng = new Random(0x7705L);
        double lp1 = 0.0, lp2 = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double attack = Math.min(1.0, t / 0.014);
            double chimeEnv = attack * Math.exp(-t * 14.5);
            double shimmerEnv = attack * Math.exp(-t * 22.0);

            double c5 = Math.sin(2.0 * Math.PI * 523.25 * t);
            double g5 = Math.sin(2.0 * Math.PI * 783.99 * t);
            double d6 = Math.sin(2.0 * Math.PI * 1174.66 * t);
            double bell = (c5 * 0.52 + g5 * 0.32) * chimeEnv + (d6 * 0.16) * shimmerEnv;

            // Warm airy whoosh envelope peaking around 45 ms
            double whooshEnv = Math.sin(Math.min(1.0, t / 0.18) * Math.PI) * Math.exp(-t * 9.0);
            double white = rng.nextDouble() * 2.0 - 1.0;
            double cutoff = 0.04 + 0.14 * Math.min(1.0, t / 0.12);
            lp1 += cutoff * (white - lp1);
            lp2 += cutoff * (lp1 - lp2);
            double whoosh = lp2 * whooshEnv * 0.28;

            pcm[i] = clamp16((bell + whoosh) * 23000.0);
        }
        return pcm;
    }

    private static short clamp16(double v) {
        int i = (int) Math.round(v);
        return (short) (i > 32767 ? 32767 : (i < -32768 ? -32768 : i));
    }

    private static void writeWav(File file, short[] pcm) throws IOException {
        int dataBytes = pcm.length * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt(36 + dataBytes);
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(16);                 // PCM chunk size
        buf.putShort((short) 1);        // AudioFormat = 1 (PCM)
        buf.putShort((short) 1);        // NumChannels = 1 (Mono)
        buf.putInt(SAMPLE_RATE);
        buf.putInt(SAMPLE_RATE * 2);    // ByteRate
        buf.putShort((short) 2);        // BlockAlign
        buf.putShort((short) 16);       // BitsPerSample
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt(dataBytes);
        for (short s : pcm) {
            buf.putShort(s);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(buf.array());
        }
    }
}
