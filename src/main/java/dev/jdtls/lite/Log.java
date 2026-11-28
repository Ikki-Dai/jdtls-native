package dev.jdtls.lite;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Dual logger: stderr + rolling log file at {@code ~/.jdtls-native/logs/server.log}.
 * The file is what you tail while operating the IDE; stderr is what the
 * IDE might capture. Never touches stdout (it carries the LSP channel).
 *
 * Log file is truncated on each server start; every LSP request is logged
 * with its method name and duration.
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static volatile PrintWriter fileWriter;
    private static volatile String logFilePath;

    private Log() {
    }

    /** Initialises the log file. Call once from Main before any LSP traffic. */
    public static synchronized void initFile(Path dir) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("server.log");
            // truncate on start
            fileWriter = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            logFilePath = file.toString();
            fileWriter.printf("[%s] [INFO] log file started: %s%n",
                    LocalDateTime.now().format(TS), file);
            fileWriter.flush();
        } catch (IOException e) {
            System.err.println("[Log] file init failed: " + e);
        }
    }

    public static String logFilePath() {
        return logFilePath;
    }

    public static void info(String msg) {
        write("INFO", msg, null);
    }

    public static void warn(String msg) {
        write("WARN", msg, null);
    }

    public static void warn(String msg, Throwable t) {
        write("WARN", msg, t);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    /** Logs a completed LSP request: `→ method (xx ms) [detail]`. */
    public static void lsp(String method, long startMs, String detail) {
        long elapsed = System.currentTimeMillis() - startMs;
        String d = detail == null || detail.isEmpty() ? "" : " " + detail;
        write("LSP", "→ " + method + " (" + elapsed + " ms)" + d, null);
    }

    /** Logs a failed LSP request with its stack trace. */
    public static void lspFail(String method, long startMs, Throwable t) {
        long elapsed = System.currentTimeMillis() - startMs;
        write("LSP", "→ " + method + " FAILED after " + elapsed + " ms", t);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        String line = String.format("[%s] [%s] %s", LocalDateTime.now().format(TS), level, msg);
        System.err.println(line);
        if (t != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            System.err.println(sw);
            if (fileWriter != null) {
                fileWriter.println(line);
                fileWriter.println(sw);
                fileWriter.flush();
            }
        } else if (fileWriter != null) {
            fileWriter.println(line);
            fileWriter.flush();
        }
    }
}
