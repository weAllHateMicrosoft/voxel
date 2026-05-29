// --- FILE: src/main/java/com/leaf/game/core/AudioManager.java ---
package com.leaf.game.core;

import com.leaf.game.util.Camera;
import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Zero-latency audio manager.
 *
 * How latency is eliminated:
 *  1. preload(name) decodes the sound file once at startup into raw PCM bytes
 *     in heap memory — no disk/classpath IO at play time.
 *  2. warmup() opens and closes a silent clip during init, forcing the JVM's
 *     audio mixer to initialise.  Without this the very first play() call
 *     pays a one-time ~50–100 ms penalty.
 *  3. A fixed thread-pool of 4 daemon workers is created eagerly so thread-
 *     creation overhead (another ~1 ms) is paid once, not per sound event.
 *  4. Continuous loops (playContinuous) always use preloaded data too.
 */
public class AudioManager {

    // ── Internal ──────────────────────────────────────────────────────────────
    private record AudioData(AudioFormat format, byte[] pcm) {}

    private static final Map<String, AudioData> preloaded       = new HashMap<>();
    private static final Map<String, Clip>      continuousClips = new HashMap<>();

    /** 4 pre-started daemon workers; no thread-creation cost at play time. */
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "audio-worker");
        t.setDaemon(true);
        return t;
    });

    // ── Extension resolution ──────────────────────────────────────────────────
    private static InputStream openStream(String name) {
        InputStream is = AudioManager.class.getResourceAsStream("/audios/" + name + ".wav");
        if (is == null) is = AudioManager.class.getResourceAsStream("/audios/" + name + ".mp3");
        return is;
    }

    // ── Startup helpers ───────────────────────────────────────────────────────

    /**
     * Decode a sound file once and store the raw PCM bytes.
     * Call for every sound that needs zero-latency playback (all action sounds).
     * Safe to call multiple times — subsequent calls for the same name are no-ops.
     */
    public static void preload(String name) {
        if (preloaded.containsKey(name)) return;
        try {
            InputStream raw = openStream(name);
            if (raw == null) {
                System.err.println("[Audio] Missing file: " + name);
                return;
            }
            AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
            AudioFormat fmt = ais.getFormat();

            // Normalise to signed 16-bit PCM so Clip.open() is always happy.
            // This also converts MP3 (if a SPI codec is present) to raw PCM.
            if (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    || fmt.getSampleSizeInBits() != 16) {
                AudioFormat target = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        fmt.getSampleRate(),
                        16,
                        fmt.getChannels(),
                        fmt.getChannels() * 2,
                        fmt.getSampleRate(),
                        false);
                ais = AudioSystem.getAudioInputStream(target, ais);
                fmt = target;
            }

            byte[] pcm = ais.readAllBytes();
            preloaded.put(name, new AudioData(fmt, pcm));
        } catch (Exception e) {
            System.err.println("[Audio] Preload failed for '" + name + "': " + e.getMessage());
        }
    }

    /**
     * Warm up the Java audio mixer by opening and closing a silent clip.
     * Must be called once during game init (after GL context is current is fine).
     * Without this the first real play() invocation pays a ~50-100 ms one-time cost.
     */
    public static void warmup() {
        try {
            Clip c = AudioSystem.getClip();
            AudioFormat af = new AudioFormat(44100f, 16, 1, true, false);
            c.open(af, new byte[af.getFrameSize() * 4], 0, af.getFrameSize() * 4);
            c.close();
        } catch (Exception ignored) {}
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void setGain(Clip clip, float volume) {
        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl g = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log10(Math.max(0.0001, volume)) * 20.0);
            g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), dB)));
        }
    }

    /**
     * Build a Clip from preloaded PCM bytes — no IO, very fast.
     * Returns null if the sound was not preloaded (caller falls back to streaming).
     */
    private static Clip clipFromPreloaded(String name, float volume) throws LineUnavailableException {
        AudioData d = preloaded.get(name);
        if (d == null) return null;
        Clip clip = AudioSystem.getClip();
        clip.open(d.format, d.pcm, 0, d.pcm.length);
        setGain(clip, volume);
        return clip;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** One-shot sound at full volume. */
    public static void play(String name) {
        play(name, 1.0f);
    }

    /** One-shot sound at a custom volume (0.0 – 1.0). */
    public static void play(String name, float volume) {
        EXEC.submit(() -> {
            try {
                Clip clip = clipFromPreloaded(name, volume);
                if (clip == null) {
                    // Fallback: stream from classpath (slower, but recovers gracefully)
                    InputStream is = openStream(name);
                    if (is == null) return;
                    clip = AudioSystem.getClip();
                    clip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(is)));
                    setGain(clip, volume);
                }
                final Clip fc = clip;
                fc.start();
                fc.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) fc.close(); });
            } catch (Exception ignored) {}
        });
    }

    /** 3-D positional one-shot: attenuates and pans based on distance/direction. */
    public static void playAt(String name, org.joml.Vector3f sourcePos,
                               Camera camera, float maxRange) {
        EXEC.submit(() -> {
            try {
                org.joml.Vector3f toSource = new org.joml.Vector3f(sourcePos).sub(camera.position);
                float dist = toSource.length();
                if (dist > maxRange) return;

                Clip clip = clipFromPreloaded(name, 1.0f);
                if (clip == null) {
                    InputStream is = openStream(name);
                    if (is == null) return;
                    clip = AudioSystem.getClip();
                    clip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(is)));
                }

                float volume = 1.0f - (dist / maxRange);
                setGain(clip, volume);

                if (clip.isControlSupported(FloatControl.Type.PAN) && dist > 0.001f) {
                    FloatControl pan = (FloatControl) clip.getControl(FloatControl.Type.PAN);
                    toSource.normalize();
                    pan.setValue(Math.max(-1f, Math.min(1f, toSource.dot(camera.getRight()))));
                }

                final Clip fc = clip;
                fc.start();
                fc.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) fc.close(); });
            } catch (Exception ignored) {}
        });
    }

    // ── Continuous (looping) sounds ───────────────────────────────────────────

    /** Start a looping sound at full volume. No-op if already playing. */
    public static void playContinuous(String name) {
        playContinuous(name, 1.0f);
    }

    /** Start a looping sound at a custom volume. No-op if already playing. */
    public static void playContinuous(String name, float volume) {
        if (continuousClips.containsKey(name)) return;
        try {
            Clip clip = clipFromPreloaded(name, volume);
            if (clip == null) {
                InputStream is = openStream(name);
                if (is == null) return;
                clip = AudioSystem.getClip();
                clip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(is)));
                setGain(clip, volume);
            }
            clip.loop(Clip.LOOP_CONTINUOUSLY);
            continuousClips.put(name, clip);
        } catch (Exception ignored) {}
    }

    /** Stop and release a looping sound. Silent no-op if not playing. */
    public static void stopContinuous(String name) {
        Clip clip = continuousClips.remove(name);
        if (clip != null) { clip.stop(); clip.close(); }
    }

    /**
     * Update the volume of a running loop every frame (e.g. wind that scales
     * with player speed).  Starts the loop at the given volume if not yet playing.
     */
    public static void setContinuousVolume(String name, float volume) {
        if (!continuousClips.containsKey(name)) {
            playContinuous(name, volume);
            return;
        }
        Clip clip = continuousClips.get(name);
        if (clip != null) setGain(clip, volume);
    }
}
