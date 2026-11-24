package com.jarvis.assistant;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Runs commands through `su`. Never call on the main thread: the root prompt can block. */
final class RootShell {
    private static Boolean cached;

    private RootShell() {}

    static synchronized boolean hasRoot() {
        if (cached == null) {
            cached = run("id").contains("uid=0");
        }
        return cached;
    }

    static synchronized void reset() {
        cached = null;
    }

    static String run(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERR " + e.getMessage();
        } finally {
            if (p != null) p.destroy();
        }
    }
}
