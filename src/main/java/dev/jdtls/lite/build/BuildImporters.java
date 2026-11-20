package dev.jdtls.lite.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.jdtls.lite.Log;

/**
 * v1 classpath importers: shell out to the project's own build tool CLI and parse
 * the result. No Eclipse m2e/buildship involved. The classpath output feeds
 * {@code IClasspathEntry} — the same handoff point m2e uses.
 */
public final class BuildImporters {

    public record Result(String kind, List<Path> sourceRoots, List<String> jars) {
    }

    private BuildImporters() {
    }

    /** Detects the build tool and imports source roots + classpath. Returns null when unsupported. */
    public static Result importClasspath(Path projectRoot) {
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            Result r = maven(projectRoot);
            if (r != null) {
                return r;
            }
        }
        if (Files.isRegularFile(projectRoot.resolve("build.gradle"))
                || Files.isRegularFile(projectRoot.resolve("build.gradle.kts"))) {
            Result r = gradle(projectRoot);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ maven

    private static Result maven(Path projectRoot) {
        Path cpFile = null;
        try {
            cpFile = Files.createTempFile("jdtls-native-cp", ".txt");
            List<String> cmd = new ArrayList<>(List.of(
                    mavenCommand(), "-B", "-q", "-Dmdep.outputFile=" + cpFile.toAbsolutePath(),
                    "dependency:build-classpath"));
            if (run(cmd, projectRoot) != 0) {
                Log.warn("mvn dependency:build-classpath failed");
                return null;
            }
            String cp = Files.readString(cpFile, StandardCharsets.UTF_8).trim();
            if (cp.isEmpty()) {
                return null;
            }
            List<String> jars = new ArrayList<>();
            for (String entry : cp.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank() && Files.isRegularFile(Path.of(entry))) {
                    jars.add(Path.of(entry).toString());
                }
            }
            List<Path> roots = new ArrayList<>();
            addIfDir(roots, projectRoot.resolve("src/main/java"));
            addIfDir(roots, projectRoot.resolve("src/test/java"));
            addIfDir(roots, projectRoot.resolve("src/main/resources"));
            Log.info("maven import: " + jars.size() + " jar(s)");
            return new Result("maven", roots, jars);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.warn("maven import failed: " + e);
            return null;
        } finally {
            if (cpFile != null) {
                try {
                    Files.deleteIfExists(cpFile);
                } catch (IOException ignore) {
                    // temp cleanup only
                }
            }
        }
    }

    // ------------------------------------------------------------------ gradle

    private static Result gradle(Path projectRoot) {
        Path init = null;
        Path cpFile = null;
        try {
            init = Files.createTempFile("jdtls-native-init", ".gradle");
            cpFile = Files.createTempFile("jdtls-native-cp", ".txt");
            String script = """
                    rootProject {
                        plugins.withId('java') {
                            tasks.register('jdtlsNativeDump') {
                                doLast {
                                    new File('%s').text = sourceSets.main.compileClasspath.asPath
                                }
                            }
                        }
                    }
                    """.formatted(cpFile.toAbsolutePath().toString().replace("\\", "\\\\"));
            Files.writeString(init, script);
            List<String> cmd = new ArrayList<>(List.of(
                    gradleCommand(), "-q", "-I", init.toAbsolutePath().toString(),
                    "--console=plain", "jdtlsNativeDump"));
            if (run(cmd, projectRoot) != 0) {
                Log.warn("gradle classpath dump failed");
                return null;
            }
            String cp = Files.readString(cpFile, StandardCharsets.UTF_8).trim();
            if (cp.isEmpty()) {
                return null;
            }
            List<String> jars = new ArrayList<>();
            for (String entry : cp.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                if (Files.isRegularFile(Path.of(entry)) && entry.endsWith(".jar")) {
                    jars.add(Path.of(entry).toString());
                } else if (Files.isDirectory(Path.of(entry))) {
                    jars.add(entry); // classes dirs from the build
                }
            }
            List<Path> roots = new ArrayList<>();
            addIfDir(roots, projectRoot.resolve("src/main/java"));
            addIfDir(roots, projectRoot.resolve("src/test/java"));
            Log.info("gradle import: " + jars.size() + " classpath entr(ies)");
            return new Result("gradle", roots, jars);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.warn("gradle import failed: " + e);
            return null;
        } finally {
            cleanup(init);
            cleanup(cpFile);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void addIfDir(List<Path> roots, Path p) {
        if (Files.isDirectory(p)) {
            roots.add(p);
        }
    }

    private static void cleanup(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignore) {
                // temp cleanup only
            }
        }
    }

    /** ProcessBuilder cannot execute .cmd batches without the extension on Windows. */
    private static String mavenCommand() {
        for (String candidate : new String[] {"mvn.cmd", "mvn.bat", "mvn"}) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        return "mvn";
    }

    private static String gradleCommand() {
        for (String candidate : new String[] {"gradle.bat", "gradle"}) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        return "gradle";
    }

    private static boolean isExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(";")) {
            for (String ext : new String[] {"", ".cmd", ".bat", ".exe"}) {
                if (Files.isRegularFile(Path.of(dir, name + ext))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int run(List<String> cmd, Path dir) throws IOException, InterruptedException {
        Log.info("running: " + String.join(" ", cmd) + " (in " + dir + ")");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            return -1;
        }
        int code = process.exitValue();
        if (code != 0) {
            Log.warn("build tool output (exit " + code + "):\n" + output);
        }
        return code;
    }
}
