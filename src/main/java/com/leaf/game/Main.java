package com.leaf.game;

import com.leaf.game.core.Window;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        // ── AUTOMATIC MAC RELAUNCH HOOK ──
        // If the player double-clicked game.jar on Mac without the JVM flag,
        // this will silently relaunch the game on the main thread and exit.
        if (relaunchOnMacIfNeeded()) {
            return; // Exit the parent JVM; the child JVM is now running
        }

        // CRITICAL MAC OS FIX: Prevent AWT from spinning up a competing UI thread
        // which causes the "objc Method cache corrupted" / SIGABRT on startup.
        System.setProperty("java.awt.headless", "true");

        Window gameWindow = new Window();
        gameWindow.run();
    }

    /**
     * Detects if the current OS is macOS and if the JVM was started without the
     * mandatory '-XstartOnFirstThread' argument. If so, it spawns a new JVM process
     * with the argument appended and exits the current process.
     *
     * @return true if the JVM has been successfully relaunched (and this parent process should terminate).
     */
    private static boolean relaunchOnMacIfNeeded() {
        String osName = System.getProperty("os.name").toLowerCase();

        // Only run this logic on macOS / OS X
        if (!osName.contains("mac") && !osName.contains("darwin")) {
            return false;
        }

        // 1. Check if the start-on-first-thread flag is already active
        boolean alreadyStarted = false;
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.contains("-XstartOnFirstThread")) {
                alreadyStarted = true;
                break;
            }
        }

        // 2. Oracle JVM also sets an environment variable under the hood
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        String env = System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + pid);
        if (env != null && env.equals("1")) {
            alreadyStarted = true;
        }

        // If we are already running on the correct thread, proceed normally
        if (alreadyStarted) {
            return false;
        }

        // 3. Spawning a new JVM with the correct arguments
        System.out.println("[MacLauncher] macOS detected. Relaunching JVM on thread 0...");
        try {
            List<String> command = new ArrayList<>();

            // Locate the executable java path
            String separator = System.getProperty("file.separator");
            String javaBin = System.getProperty("java.home") + separator + "bin" + separator + "java";
            if (!new File(javaBin).exists()) {
                javaBin = "java"; // Fallback to system path
            }

            command.add(javaBin);
            command.add("-XstartOnFirstThread"); // FORCE thread 0 allocation

            // Forward existing JVM configurations (e.g. system properties)
            command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

            // Forward the classpath pointing to our game.jar
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));

            // Forward our Main class as the entry point
            command.add(Main.class.getName());

            // Build and execute the new child JVM process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO(); // Pass through System.out/System.err to show up in IDE consoles
            pb.start();

            // Successfully kicked off child JVM — close parent immediately
            System.exit(0);
            return true;
        } catch (Exception e) {
            System.err.println("[MacLauncher] Failed to self-relaunch the JVM: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}