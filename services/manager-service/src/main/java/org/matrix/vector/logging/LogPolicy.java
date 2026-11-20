package org.matrix.vector.logging;

/** Capture policy shared by the daemon, manager and injected processes. */
public final class LogPolicy {
    public static final int OFF = 0;
    public static final int BASIC = 1;
    public static final int DETAILED = 2;
    public static final String PROPERTY = "log.tag.HraELL";

    private LogPolicy() {}

    // The daemon publishes S / D / V. With no property Android defaults to INFO,
    // so neither DEBUG nor VERBOSE is enabled and a fresh installation stays silent.
    // A silent control tag also fails closed if global/process liblog verbosity overrides us.
    public static int getLevel() {
        if (android.util.Log.isLoggable("HraELL.Silent", android.util.Log.DEBUG)) return OFF;
        if (android.util.Log.isLoggable("HraELL", android.util.Log.VERBOSE)) return DETAILED;
        if (android.util.Log.isLoggable("HraELL", android.util.Log.DEBUG)) return BASIC;
        return OFF;
    }

    public static boolean allows(int priority, String tag) {
        int level = getLevel();
        if (level == OFF) return false;
        // Basic retains module failures, but arbitrary module chatter needs Detailed.
        boolean module = tag.equals("HraELLContext") || tag.equals("HraELLLegacyBridge");
        return level == DETAILED || priority >= (module ? android.util.Log.WARN : android.util.Log.INFO);
    }
}
