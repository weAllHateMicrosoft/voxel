// --- FILE: src/main/java/com/leaf/game/core/AudioManager.java ---
package com.leaf.game.core;

import com.leaf.game.util.Camera;
import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class AudioManager {
    private static final Map<String, Clip> continuousClips = new HashMap<>();

    // Plays a one-shot sound at its default volume
    public static void play(String name) {
        play(name, 1.0f);
    }

    // Plays a one-shot sound with a custom volume (0.0f to 1.0f)
    public static void play(String name, float volume) {
        new Thread(() -> {
            try {
                Clip clip = AudioSystem.getClip();
                InputStream is = AudioManager.class.getResourceAsStream("/audios/" + name + ".wav");
                if (is == null) return;
                AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(is));
                clip.open(ais);

                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    float dB = (float) (Math.log10(Math.max(0.0001, volume)) * 20.0);
                    gainControl.setValue(Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB)));
                }

                clip.start();
                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) clip.close();
                });
            } catch (Exception ignored) {}
        }).start();
    }

    // Plays a 3D sound positioned in the 360-degree environment
    public static void playAt(String name, org.joml.Vector3f sourcePos, Camera camera, float maxRange) {
        new Thread(() -> {
            try {
                Clip clip = AudioSystem.getClip();
                InputStream is = AudioManager.class.getResourceAsStream("/audios/" + name + ".wav");
                if (is == null) return;
                AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(is));
                clip.open(ais);

                // Compute distance and direction
                org.joml.Vector3f toSource = new org.joml.Vector3f(sourcePos).sub(camera.position);
                float dist = toSource.length();
                if (dist > maxRange) {
                    clip.close();
                    return;
                }

                // Volume attenuation based on distance
                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    float volume = 1.0f - (dist / maxRange); // 1.0 (close) -> 0.0 (far)
                    float dB = (float) (Math.log10(Math.max(0.0001, volume)) * 20.0);
                    gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
                }

                // Stereo Panning (calculating 360 degree direction)
                if (clip.isControlSupported(FloatControl.Type.PAN)) {
                    FloatControl panControl = (FloatControl) clip.getControl(FloatControl.Type.PAN);
                    if (dist > 0.001f) {
                        toSource.normalize();
                        float pan = toSource.dot(camera.getRight()); // -1.0 (Left) to 1.0 (Right)
                        panControl.setValue(Math.max(-1.0f, Math.min(1.0f, pan)));
                    }
                }

                clip.start();
                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) clip.close();
                });
            } catch (Exception ignored) {}
        }).start();
    }

    // Starts a continuous looping sound (volume adjustable)
    public static void playContinuous(String name, float volume) {
        if (continuousClips.containsKey(name)) return;
        try {
            Clip clip = AudioSystem.getClip();
            InputStream is = AudioManager.class.getResourceAsStream("/audios/" + name + ".wav");
            if (is == null) return;
            AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(is));
            clip.open(ais);

            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float dB = (float) (Math.log10(Math.max(0.0001, volume)) * 20.0);
                gainControl.setValue(Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB)));
            }

            clip.open(ais);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
            continuousClips.put(name, clip);
        } catch (Exception ignored) {}
    }

    public static void playContinuous(String name) {
        playContinuous(name, 1.0f);
    }

    // Stops a continuous sound
    public static void stopContinuous(String name) {
        Clip clip = continuousClips.remove(name);
        if (clip != null) {
            clip.stop();
            clip.close();
        }
    }
}