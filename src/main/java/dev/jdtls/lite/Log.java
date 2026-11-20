package dev.jdtls.lite;

import java.time.LocalTime;

/** Minimal stderr logger. Never touches stdout (it carries the LSP channel). */
public final class Log {
    private Log() {
    }

    public static void info(String msg) {
        write("INFO", msg, null);
    }

    public static void warn(String msg) {
        write("WARN", msg, null);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    public static void warn(String msg, Throwable t) {
        write("WARN", msg, t);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        System.err.printf("[%s] [%s] %s%n", LocalTime.now(), level, msg);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }
}
