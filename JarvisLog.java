package com.jarvis.assistant;

/** Tiny in-memory log shared by the service and the UI. */
final class JarvisLog {
    private static final StringBuilder SB = new StringBuilder();
    static volatile Runnable listener;

    private JarvisLog() {}

    static synchronized void add(String line) {
        SB.append(line).append('\n');
        if (SB.length() > 8000) {
            SB.delete(0, SB.length() - 6000);
        }
        Runnable r = listener;
        if (r != null) r.run();
    }

    static synchronized String text() {
        return SB.toString();
    }
}
