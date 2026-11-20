package org.matrix.vector.logging;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Android Log facade that honours the live capture policy, including errors. */
public class ManagedLog {
    public static final int VERBOSE = android.util.Log.VERBOSE;
    public static final int DEBUG = android.util.Log.DEBUG;
    public static final int INFO = android.util.Log.INFO;
    public static final int WARN = android.util.Log.WARN;
    public static final int ERROR = android.util.Log.ERROR;
    public static final int ASSERT = android.util.Log.ASSERT;

    public static int println(int priority, String tag, String message) {
        if (!LogPolicy.allows(priority, tag)) return 0;
        return android.util.Log.println(priority, tag, message);
    }

    public static int module(int priority, String tag, String message) {
        if (LogPolicy.getLevel() != LogPolicy.DETAILED && priority < WARN) return 0;
        return println(priority, tag, message);
    }

    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        StringWriter sw = new StringWriter();
        tr.printStackTrace(new PrintWriter(sw));
        return sw.toString().stripTrailing();
    }

    private static int emit(int priority, String tag, String message, Throwable tr) {
        if (!LogPolicy.allows(priority, tag)) return 0;
        return println(priority, tag, message + "\n" + getStackTraceString(tr));
    }
    public static int v(String tag, String message) { return println(VERBOSE, tag, message); }
    public static int v(String tag, String message, Throwable tr) { return emit(VERBOSE, tag, message, tr); }
    public static int d(String tag, String message) { return println(DEBUG, tag, message); }
    public static int d(String tag, String message, Throwable tr) { return emit(DEBUG, tag, message, tr); }
    public static int i(String tag, String message) { return println(INFO, tag, message); }
    public static int i(String tag, String message, Throwable tr) { return emit(INFO, tag, message, tr); }
    public static int w(String tag, String message) { return println(WARN, tag, message); }
    public static int w(String tag, String message, Throwable tr) { return emit(WARN, tag, message, tr); }
    public static int e(String tag, String message) { return println(ERROR, tag, message); }
    public static int e(String tag, String message, Throwable tr) { return emit(ERROR, tag, message, tr); }
    public static int wtf(String tag, String message) { return println(ASSERT, tag, message); }
    public static int wtf(String tag, String message, Throwable tr) { return emit(ASSERT, tag, message, tr); }
    public static int w(String tag, Throwable tr) { return emit(WARN, tag, "", tr); }
}
