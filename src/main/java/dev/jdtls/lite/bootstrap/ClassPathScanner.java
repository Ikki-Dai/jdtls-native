package dev.jdtls.lite.bootstrap;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.osgi.framework.Version;

import dev.jdtls.lite.Log;

/**
 * Finds platform bundle jars on the (flat) classpath and parses their OSGi
 * manifests. Handles wildcard classpath entries ({@code libs/*}) because
 * {@code java.class.path} reports them unexpanded.
 */
final class ClassPathScanner {

    private ClassPathScanner() {
    }

    static Map<String, BundleJarInfo> discover() throws IOException {
        Map<String, BundleJarInfo> result = new LinkedHashMap<>();
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry != null && entry.endsWith("*") && !Files.isDirectory(Path.of(entry.substring(0, entry.length() - 1)))) {
                // baked-in classpath entry that no longer exists (packaged native exe):
                // fall back to a "libs" folder next to the current directory / executable
                Path cwdLibs = Path.of("libs");
                if (Files.isDirectory(cwdLibs)) {
                    entry = cwdLibs.toString() + java.io.File.separator + "*";
                }
            }
            if (entry.isBlank() || entry.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                continue;
            }
            Path p = Path.of(entry);
            if (Files.isDirectory(p)) {
                continue; // classes dir: no bundle metadata needed from there
            }
            if (entry.endsWith("*")) {
                Path dir = Path.of(entry.substring(0, entry.length() - 1));
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> jars = Files.list(dir)) {
                        for (Path jar : jars.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) {
                            addJar(result, jar);
                        }
                    }
                }
                continue;
            }
            addJar(result, p);
        }
        if (result.isEmpty()) {
            // packaged native exe: java.class.path carries no usable entries.
            // Fall back to a "libs" folder next to the cwd or next to the executable.
            List<Path> candidates = new ArrayList<>();
            candidates.add(Path.of("libs"));
            ProcessHandle.current().info().command().ifPresent(cmd -> {
                try {
                    candidates.add(Path.of(cmd).toAbsolutePath().getParent().resolve("libs"));
                } catch (RuntimeException ignore) {
                    // exotic launchers may report no command
                }
            });
            for (Path dir : candidates) {
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> jars = Files.list(dir)) {
                        for (Path jar : jars.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) {
                            addJar(result, jar);
                        }
                    }
                }
                if (!result.isEmpty()) {
                    Log.info("bundle discovery used fallback dir: " + dir);
                    break;
                }
            }
        }
        return result;
    }

    private static void addJar(Map<String, BundleJarInfo> result, Path jar) {
        if (!Files.isRegularFile(jar)) {
            return;
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) {
                return;
            }
            String symbolic = mf.getMainAttributes().getValue("Bundle-SymbolicName");
            if (symbolic == null) {
                return; // not a bundle
            }
            int semi = symbolic.indexOf(';');
            if (semi >= 0) {
                symbolic = symbolic.substring(0, semi).trim();
            }
            String versionStr = mf.getMainAttributes().getValue("Bundle-Version");
            Version version;
            try {
                version = versionStr == null ? Version.emptyVersion : Version.parseVersion(versionStr.split(" ")[0]);
            } catch (IllegalArgumentException e) {
                version = Version.emptyVersion;
            }
            String activator = mf.getMainAttributes().getValue("Bundle-Activator");
            Map<String, String> headers = new HashMap<>();
            mf.getMainAttributes().forEach((k, v) -> headers.put(k.toString(), v.toString()));
            byte[] pluginXml = readEntry(jf, "plugin.xml");
            byte[] fragmentXml = readEntry(jf, "fragment.xml");
            BundleJarInfo info = new BundleJarInfo(jar, symbolic, version, activator, headers, pluginXml, fragmentXml);
            result.put(symbolic, info); // first jar wins for duplicate symbolic names
        } catch (IOException e) {
            Log.warn("skipping unreadable jar " + jar + ": " + e.getMessage());
        }
    }

    private static byte[] readEntry(JarFile jf, String name) {
        ZipEntry entry = jf.getJarEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = jf.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
