package dev.jdtls.lite.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.jdtls.lite.Log;

/**
 * Resolves the JDK home used for the JRE system library (jrt-fs.jar).
 * Order: system property {@code jdtls.native.jdk} → {@code JAVA_HOME} →
 * {@code java.home} (present on a normal JVM, may be null/absent in a native image).
 */
public final class JdkLocator {

    private static volatile Path resolved;

    public static Path resolve() {
        Path p = resolved;
        if (p != null) {
            return p;
        }
        for (String candidate : new String[] { System.getProperty("jdtls.native.jdk"),
                System.getenv("JAVA_HOME"), System.getProperty("java.home") }) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path dir = Path.of(candidate);
            if (Files.isRegularFile(dir.resolve("lib").resolve("jrt-fs.jar"))) {
                resolved = dir;
                return dir;
            }
            Log.warn("candidate JDK home has no lib/jrt-fs.jar: " + candidate);
        }
        throw new IllegalStateException("no usable JDK found (set -Djdtls.native.jdk=<home> or JAVA_HOME)");
    }

    private JdkLocator() {
    }
}
