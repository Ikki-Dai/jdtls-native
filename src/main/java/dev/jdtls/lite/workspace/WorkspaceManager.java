package dev.jdtls.lite.workspace;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.lsp4j.InitializeParams;

import dev.jdtls.lite.Log;

/**
 * Minimal workspace state: the root folder, heuristic source roots, open documents
 * and the classpath. Deliberately free of any Eclipse workspace machinery.
 */
public final class WorkspaceManager {

    private final Map<String, Doc> docs = new ConcurrentHashMap<>();
    private volatile Path root;
    private final List<Path> roots = new ArrayList<>();

    public record Doc(int version, String text) {
    }

    public void initialize(InitializeParams params) {
        Path found = null;
        if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
            found = pathOfUri(params.getWorkspaceFolders().get(0).getUri());
        } else if (params.getRootUri() != null) {
            found = pathOfUri(params.getRootUri());
        } else if (params.getRootPath() != null) {
            found = Path.of(params.getRootPath());
        }
        root = found;
        if (params.getWorkspaceFolders() != null) {
            for (var folder : params.getWorkspaceFolders()) {
                Path fr = pathOfUri(folder.getUri());
                if (fr != null && !roots.contains(fr)) {
                    roots.add(fr);
                }
            }
        }
        if (roots.isEmpty() && root != null) {
            roots.add(root);
        }
        Log.info("workspace root: " + found + ", folders: " + roots);
    }

    /** All workspace folders (falls back to the single root). */
    public List<Path> roots() {
        List<Path> all = new ArrayList<>(roots);
        if (all.isEmpty() && root != null) {
            all.add(root);
        }
        return all;
    }

    private static Path pathOfUri(String uri) {
        try {
            return Path.of(URI.create(uri.replace("\\", "/")));
        } catch (RuntimeException e) {
            Log.warn("cannot convert uri to path: " + uri + " (" + e.getMessage() + ")");
            return null;
        }
    }

    public Path root() {
        return root;
    }

    /** Source roots, most specific first. Heuristics only for now (maven/gradle import later). */
    public List<Path> sourceRoots() {
        Path r = root;
        if (r == null) {
            return List.of();
        }
        Path mavenLayout = r.resolve("src").resolve("main").resolve("java");
        if (mavenLayout.toFile().isDirectory()) {
            return List.of(mavenLayout, r.resolve("src"), r);
        }
        if (r.resolve("src").toFile().isDirectory()) {
            return List.of(r.resolve("src"), r);
        }
        return List.of(r);
    }

    /** v1 classpath: explicit JDK (jrt-fs stub) + jars under {@code root/lib}. */
    public String[] classpath() {
        Path r = root;
        List<String> entries = new ArrayList<>();
        try {
            entries.add(dev.jdtls.lite.bootstrap.JdkLocator.resolve().resolve("lib").resolve("jrt-fs.jar").toString());
        } catch (RuntimeException e) {
            Log.warn("no JDK resolved: " + e.getMessage());
        }
        if (r == null) {
            return entries.toArray(String[]::new);
        }
        Path lib = r.resolve("lib");
        java.io.File[] jars = lib.toFile().listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null) {
            java.util.Arrays.stream(jars).map(f -> f.getAbsolutePath()).forEach(entries::add);
        }
        return entries.toArray(String[]::new);
    }

    public String[] sourcepath() {
        return sourceRoots().stream().map(p -> p.toString()).toArray(String[]::new);
    }

    /** Maps a file URI to the unit name expected by ASTParser (path relative to a source root). */
    public String unitNameFor(String uri) {
        Path p = pathOfUri(uri);
        if (p == null) {
            return null;
        }
        for (Path srcRoot : sourceRoots()) {
            if (p.startsWith(srcRoot)) {
                Path rel = srcRoot.relativize(p);
                return rel.toString().replace('\\', '/');
            }
        }
        // Not under a known root (single-file mode): bare file name still parses fine.
        return p.getFileName() == null ? null : p.getFileName().toString();
    }

    public Doc document(String uri) {
        return docs.get(uri);
    }

    public java.util.Map<String, Doc> allDocuments() {
        return java.util.Collections.unmodifiableMap(docs);
    }

    public void putDocument(String uri, int version, String text) {
        docs.put(uri, new Doc(version, text));
    }

    public Doc removeDocument(String uri) {
        return docs.remove(uri);
    }
}
