package dev.jdtls.lite.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.core.IClassFile;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import dev.jdtls.lite.Log;

/**
 * FernFlower wrapper (same engine jdt.ls uses): renders .class files without
 * attached sources as Java source. Results are cached per class file.
 */
public final class Decompiler {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Decompiler() {
    }

    /** Decompiled source of a class file, or null on failure. */
    public static String decompile(IClassFile classFile) {
        String key = cacheKey(classFile);
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            byte[] bytes = classFile.getBytes();
            Path tmp = Files.createTempFile("jdtls-native-decomp", ".class");
            Files.write(tmp, bytes);
            StringBuilder source = new StringBuilder();
            Map<String, Object> options = new HashMap<>();
            options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
            options.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1");
            options.put(IFernflowerPreferences.LOG_LEVEL, "warn");
            options.put(IFernflowerPreferences.INDENT_STRING, "    ");
            BaseDecompiler decompiler = new BaseDecompiler(
                    (externalPath, internalPath) -> bytes,
                    new IResultSaver() {
                        @Override
                        public void saveFolder(String path) {
                        }

                        @Override
                        public void copyFile(String source, String target, String entryName) {
                        }

                        @Override
                        public void saveClassFile(String path, String qualifiedName, String entryName,
                                String content, int[] mapping) {
                            if (content != null && source.isEmpty()) {
                                source.append(content);
                            }
                        }

                        @Override
                        public void createArchive(String path, String entryName, java.util.jar.Manifest manifest) {
                        }

                        @Override
                        public void saveDirEntry(String path, String archiveName, String entryName) {
                        }

                        @Override
                        public void copyEntry(String source, String targetPath, String archiveName, String entryName) {
                        }

                        @Override
                        public void saveClassEntry(String path, String archiveName, String qualifiedName,
                                String entryName, String content) {
                        }

                        @Override
                        public void closeArchive(String path, String archiveName) {
                        }
                    },
                    options, new PrintStreamLogger(System.err));
            decompiler.addSource(tmp.toFile());
            decompiler.decompileContext();
            Files.deleteIfExists(tmp);
            if (!source.isEmpty()) {
                CACHE.put(key, source.toString());
                return source.toString();
            }
            Log.warn("decompiler produced no source for " + classFile.getElementName());
            return null;
        } catch (Throwable t) {
            Log.warn("decompile failed for " + classFile.getElementName() + ": " + t);
            return null;
        }
    }

    /** Stable on-disk path for decompiled sources (per class file, hashed). */
    public static Path decompiledFileFor(IClassFile classFile) throws Exception {
        String hash = sha8(String.valueOf(classFile.getPath()));
        String pkg = classFile.getParent() == null ? "" : classFile.getParent().getElementName().replace('.', '/');
        String simple = classFile.getElementName().replaceAll("\\.class$", "") + ".java";
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-native", "decompiled", hash, pkg);
        Files.createDirectories(dir);
        return dir.resolve(simple);
    }

    private static String cacheKey(IClassFile classFile) {
        return String.valueOf(classFile.getPath());
    }

    private static String sha8(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
