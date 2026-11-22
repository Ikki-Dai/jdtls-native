package dev.jdtls.lite.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;

import dev.jdtls.lite.Log;
import dev.jdtls.lite.bootstrap.PlatformBoot;
import dev.jdtls.lite.build.BuildImporters;

/**
 * JavaModel-backed engine running on the OSGi emulation layer: completion,
 * diagnostics and search over a real Eclipse workspace, without any OSGi
 * container. Single project per LSP root in this version.
 */
public final class JavaModelEngine {

    private IJavaProject javaProject; // primary (first folder), kept for capability checks
    private final Map<String, ICompilationUnit> workingCopies = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<org.eclipse.lsp4j.Diagnostic>> lastProblems = new java.util.concurrent.ConcurrentHashMap<>();

    /** One Eclipse project per workspace folder. */
    public static final class ProjectEntry {
        public final Path root;
        public final IProject project;
        public final IJavaProject javaProject;

        ProjectEntry(Path root, IProject project, IJavaProject javaProject) {
            this.root = root;
            this.project = project;
            this.javaProject = javaProject;
        }
    }

    private final List<ProjectEntry> entries = new ArrayList<>();

    /**
     * Boots the platform (idempotent) and creates/opens one Java project per
     * workspace folder. Workspace metadata goes under {@code configDir}'s
     * sibling workspace.
     */
    public synchronized void start(Path configDir, List<Path> projectRoots, String jdkHomeOverride) throws Exception {
        if (!entries.isEmpty()) {
            return;
        }
        if (projectRoots == null || projectRoots.isEmpty()) {
            throw new IllegalArgumentException("no workspace folders");
        }
        Path workspaceDir = configDir.resolve("workspace");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceDir);
        PlatformBoot.boot(configDir, workspaceDir);
        ensureIndexManager();
        Path jdkHome = jdkHomeOverride != null && !jdkHomeOverride.isBlank()
                ? Path.of(jdkHomeOverride)
                : dev.jdtls.lite.bootstrap.JdkLocator.resolve();

        for (Path projectRoot : projectRoots) {
            createProjectEntry(projectRoot, jdkHome);
        }
        javaProject = entries.get(0).javaProject;
        Log.info("JavaModelEngine ready: " + entries.size() + " project(s)");
    }

    private void createProjectEntry(Path projectRoot, Path jdkHome) throws Exception {

        String name = sanitize(projectRoot.getFileName() == null ? "project" : projectRoot.getFileName().toString());
        while (ResourcesPlugin.getWorkspace().getRoot().getProject(name).exists()
                && !ResourcesPlugin.getWorkspace().getRoot().getProject(name).getLocation()
                        .toFile().toPath().equals(projectRoot.toAbsolutePath().normalize())) {
            name = name + "_2"; // same-name folder opened twice
        }
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject(name);
        if (!project.exists()) {
            IProjectDescription desc = project.getWorkspace().newProjectDescription(name);
            desc.setLocationURI(projectRoot.toUri());
            project.create(desc, new NullProgressMonitor());
        }
        if (!project.isOpen()) {
            project.open(new NullProgressMonitor());
        }
        IProjectDescription desc = project.getDescription();
        if (desc.getNatureIds() == null || desc.getNatureIds().length == 0) {
            desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
            project.setDescription(desc, new NullProgressMonitor());
        }
        ensureIndexManager();
        javaProject = JavaCore.create(project);

        // compiler options: compliance follows the newest level the bundled JDT supports
        // (so a JDK/JDT upgrade keeps working), unless pinned via -Djdtls.native.compliance
        String compliance = System.getProperty("jdtls.native.compliance");
        if (compliance == null || compliance.isBlank()) {
            compliance = latestSupportedCompliance();
        }
        Map<String, String> opts = javaProject.getOptions(false);
        opts.put(JavaCore.COMPILER_COMPLIANCE, compliance);
        opts.put(JavaCore.COMPILER_SOURCE, compliance);
        opts.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, compliance);
        javaProject.setOptions(opts);
        Log.info("compiler compliance=" + compliance + " (jdk=" + jdkHome + ")");

        // classpath: jrt (JDK) + build-tool import (maven/gradle) + lib/*.jar fallback
        List<org.eclipse.jdt.core.IClasspathEntry> cpEntries = new ArrayList<>();
        String jrtFs = jdkHome.resolve("lib").resolve("jrt-fs.jar").toString();
        // attach the JDK's src.zip when present: definition into java.* shows real sources
        Path jdkSrc = jdkHome.resolve("lib").resolve("src.zip");
        cpEntries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(jrtFs),
                Files.isRegularFile(jdkSrc) ? new org.eclipse.core.runtime.Path(jdkSrc.toString()) : null,
                null));

        BuildImporters.Result imported = BuildImporters.importClasspath(projectRoot);
        List<Path> sourceRoots;
        if (imported != null) {
            Log.info("build tool import: " + imported.kind() + ", roots=" + imported.sourceRoots()
                    + ", jars=" + imported.jars().size());
            sourceRoots = imported.sourceRoots();
            for (String jar : imported.jars()) {
                cpEntries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(jar),
                        sourceAttachmentFor(jar), null));
            }
        } else {
            sourceRoots = nonNestedSourceRoots(projectRoot);
        }
        Path lib = projectRoot.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var stream = Files.list(lib)) {
                for (Path jar : stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                    cpEntries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(jar.toString()),
                            sourceAttachmentFor(jar.toString()), null));
                }
            }
        }
        // source roots — must not nest (JDT forbids a source root inside another)
        List<Path> exclusiveRoots = exclusiveRoots(sourceRoots);
        if (exclusiveRoots.isEmpty()) {
            exclusiveRoots = List.of(projectRoot);
        }
        for (Path srcRoot : exclusiveRoots) {
            String rel = projectRoot.relativize(srcRoot).toString().replace('\\', '/');
            String entryPath = rel.isEmpty() ? "/" + name : "/" + name + "/" + rel;
            cpEntries.add(JavaCore.newSourceEntry(new org.eclipse.core.runtime.Path(entryPath)));
        }
        javaProject.setRawClasspath(cpEntries.toArray(org.eclipse.jdt.core.IClasspathEntry[]::new),
                new org.eclipse.core.runtime.Path("/" + name + "/bin"), new NullProgressMonitor());
        ProjectEntry entry = new ProjectEntry(projectRoot.toAbsolutePath().normalize(), project, javaProject);
        this.entries.add(entry);
        Log.info("project ready: " + name + ", entries=" + entries.size() + ", jrt=" + jrtFs);
    }

    /** Defensive fixup: the JMM constructor only creates the IndexManager when
     *  Platform.isRunning() was already true at construction time. */
    private static void ensureIndexManager() {
        try {
            var jmm = org.eclipse.jdt.internal.core.JavaModelManager.getJavaModelManager();
            var f = org.eclipse.jdt.internal.core.JavaModelManager.class.getDeclaredField("indexManager");
            f.setAccessible(true);
            if (f.get(jmm) == null) {
                Log.warn("IndexManager missing; Platform.isRunning()=" + org.eclipse.core.runtime.Platform.isRunning());
                org.eclipse.jdt.internal.core.search.indexing.IndexManager im =
                        new org.eclipse.jdt.internal.core.search.indexing.IndexManager();
                f.set(jmm, im);
                im.reset(); // starts the indexing processing thread (searches block without it)
                Log.warn("created missing IndexManager via reflection");
            }
        } catch (Throwable t) {
            Log.warn("indexManager fixup failed: " + t);
        }
    }

    public boolean isReady() {
        return !entries.isEmpty();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Newest compliance level the bundled JDT declares (JavaCore.VERSION_2x constants). */
    private static String latestSupportedCompliance() {
        int max = 21;
        for (java.lang.reflect.Field f : JavaCore.class.getFields()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("VERSION_(\\d+)$").matcher(f.getName());
            if (f.getType() == String.class && m.matches()) {
                try {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignore) {
                    // not a plain version
                }
            }
        }
        return String.valueOf(max);
    }

    /** Sibling {@code -sources.jar} of a dependency jar, when the local repo has one. */
    private static org.eclipse.core.runtime.Path sourceAttachmentFor(String jarPath) {
        if (jarPath.endsWith(".jar")) {
            Path src = Path.of(jarPath.substring(0, jarPath.length() - 4) + "-sources.jar");
            if (Files.isRegularFile(src)) {
                return new org.eclipse.core.runtime.Path(src.toString());
            }
        }
        return null;
    }

    /** Drops any root that is inside another root (keeps the deeper one). */
    private static List<Path> exclusiveRoots(List<Path> roots) {
        List<Path> result = new ArrayList<>();
        for (Path r : roots) {
            Path abs = r.toAbsolutePath().normalize();
            boolean nested = result.stream()
                    .anyMatch(other -> abs.startsWith(other.toAbsolutePath().normalize()));
            if (!nested) {
                result.add(r);
            }
        }
        return result;
    }

    /** Exclusive source roots: the most specific layout wins, never nested. */
    private static List<Path> nonNestedSourceRoots(Path root) {
        Path javaMain = root.resolve("src").resolve("main").resolve("java");
        Path javaTest = root.resolve("src").resolve("test").resolve("java");
        if (Files.isDirectory(javaMain)) {
            List<Path> roots = new ArrayList<>();
            roots.add(javaMain);
            if (Files.isDirectory(javaTest)) {
                roots.add(javaTest);
            }
            return roots;
        }
        if (Files.isDirectory(root.resolve("src"))) {
            return List.of(root.resolve("src"));
        }
        return List.of(root);
    }

    // ------------------------------------------------------------------ working copies

    /** Opens/updates the working copy for an open editor. Returns false if the file is outside the project. */
    public synchronized boolean putDocument(String uri, String text) {
        ICompilationUnit cu = findCompilationUnit(uri);
        if (cu == null) {
            Log.info("putDocument: no compilation unit for " + uri);
            return false;
        }
        try {
            if (!workingCopies.containsKey(uri)) {
                cu.becomeWorkingCopy(new ProblemCollector(uri), new NullProgressMonitor());
                workingCopies.put(uri, cu);
            }
            cu.getBuffer().setContents(text);
            return true;
        } catch (Exception e) {
            Log.warn("putDocument failed for " + uri, e);
            return false;
        }
    }

    public synchronized void discardDocument(String uri) {
        ICompilationUnit cu = workingCopies.remove(uri);
        if (cu != null) {
            try {
                cu.discardWorkingCopy();
            } catch (Exception e) {
                Log.warn("discardWorkingCopy failed: " + e);
            }
        }
    }

    /** Maps a file URI to the project IFile; null when outside the project. */
    private IFile findFile(String uri) {
        if (javaProject == null) {
            Log.info("findFile: engine not ready");
            return null;
        }
        java.net.URI u;
        try {
            u = java.net.URI.create(uri.replace("\\", "/"));
        } catch (RuntimeException e) {
            Log.info("findFile: bad uri " + uri + " (" + e + ")");
            return null;
        }
        Path filePath;
        try {
            filePath = Path.of(u);
        } catch (RuntimeException e) {
            Log.info("findFile: bad path " + uri + " (" + e + ")");
            return null;
        }
        org.eclipse.core.resources.IProject project = javaProject.getProject();
        java.nio.file.Path loc = project.getLocation() == null ? null
                : java.nio.file.Path.of(project.getLocation().toFile().toURI());
        if (loc == null) {
            Log.info("findFile: project location null for " + project.getName());
            return null;
        }
        java.nio.file.Path projectPath = real(loc);
        filePath = real(filePath);
        if (!filePath.startsWith(projectPath)) {
            Log.info("findFile: outside project: file=" + filePath + " project=" + projectPath);
            return null;
        }
        String rel = projectPath.relativize(filePath).toString().replace('\\', '/');
        IFile file = project.getFile(rel);
        if (!file.exists()) {
            try {
                file.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, new NullProgressMonitor());
            } catch (Exception e) {
                Log.info("findFile: refreshLocal failed for " + rel + ": " + e);
                return null;
            }
        }
        if (!file.exists()) {
            Log.info("findFile: still missing after refresh: " + rel);
            return null;
        }
        return file;
    }

    private ICompilationUnit findCompilationUnit(String uri) {
        IFile file = findFile(uri);
        if (file == null) {
            Log.info("fcu: no file for " + uri);
            return null;
        }
        org.eclipse.jdt.core.IJavaElement el = JavaCore.create(file);
        if (el == null) {
            try {
                Log.info("fcu: JavaCore.create null for " + file.getFullPath()
                        + " nature=" + file.getProject().hasNature(JavaCore.NATURE_ID)
                        + " open=" + file.getProject().isOpen()
                        + " onClasspath=" + javaProject.isOnClasspath(file)
                        + " rawCp=" + javaProject.getRawClasspath().length);
            } catch (Exception e) {
                Log.info("fcu: JavaCore.create null; probe failed: " + e);
            }
            return null;
        }
        if (!(el instanceof ICompilationUnit)) {
            Log.info("fcu: element is not ICU: " + el);
            return null;
        }
        return (ICompilationUnit) el;
    }

    private java.nio.file.Path projectPath() {
        if (javaProject == null) {
            return null;
        }
        return real(java.nio.file.Path.of(javaProject.getProject().getLocation().toFile().toURI()));
    }

    /** Canonical form (resolves Windows 8.3 short names like RUNNER~1); raw on failure. */
    private static java.nio.file.Path real(java.nio.file.Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            return p;
        }
    }

    /** True when the URI is inside the model project. */
    public boolean handles(String uri) {
        if (entries.isEmpty()) {
            return false;
        }
        try {
            java.nio.file.Path p = real(Path.of(java.net.URI.create(uri.replace("\\", "/"))));
            return entries.stream().anyMatch(e -> p.startsWith(real(
                    java.nio.file.Path.of(e.project.getLocation().toFile().toURI()))));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ completion

    public List<org.eclipse.lsp4j.CompletionItem> complete(String uri, String text, int line, int character) {
        List<org.eclipse.lsp4j.CompletionItem> items = new ArrayList<>();
        ICompilationUnit cu = workingCopies.get(uri);
        if (cu == null) {
            return items;
        }
        try {
            String bufferText = cu.getBuffer().getContents();
            int offset = offsetOf(bufferText, line, character);
            if (offset < 0) {
                return items;
            }
            CompletionRequestor requestor = new CompletionRequestor() {
                @Override
                public void accept(CompletionProposal proposal) {
                    org.eclipse.lsp4j.CompletionItem item = toItem(proposal, bufferText);
                    if (item != null) {
                        items.add(item);
                    }
                }
            };
            int[] kinds = { CompletionProposal.TYPE_REF, CompletionProposal.METHOD_REF, CompletionProposal.FIELD_REF,
                    CompletionProposal.PACKAGE_REF, CompletionProposal.KEYWORD, CompletionProposal.LOCAL_VARIABLE_REF,
                    CompletionProposal.METHOD_NAME_REFERENCE, CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION };
            for (int k : kinds) {
                requestor.setIgnored(k, false);
            }
            cu.codeComplete(offset, requestor, new NullProgressMonitor());
        } catch (Throwable t) {
            Log.warn("complete failed: " + t, t);
        }
        return items;
    }

    private static int offsetOf(String text, int line, int character) {
        int lineIndex = 0;
        int col = 0;
        for (int i = 0; i < text.length(); i++) {
            if (lineIndex == line && col == character) {
                return i;
            }
            char c = text.charAt(i);
            if (c == '\n') {
                lineIndex++;
                col = 0;
            } else {
                col++;
            }
        }
        return lineIndex == line ? text.length() : -1;
    }

    private org.eclipse.lsp4j.CompletionItem toItem(CompletionProposal proposal, String bufferText) {
        try {
            org.eclipse.lsp4j.CompletionItem item = new org.eclipse.lsp4j.CompletionItem();
            String completion = proposal.getCompletion() == null ? "" : new String(proposal.getCompletion());
            char[] nameChars = proposal.getName();
            String name = nameChars == null ? completion : new String(nameChars);
            switch (proposal.getKind()) {
                case CompletionProposal.TYPE_REF -> {
                    item.setLabel(name.isEmpty() ? simpleNameOf(proposal.getSignature()) : name);
                    item.setKind(org.eclipse.lsp4j.CompletionItemKind.Class);
                    item.setDetail(simpleNameOf(proposal.getDeclarationSignature()));
                }
                case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_NAME_REFERENCE -> {
                    item.setLabel(name + "()");
                    item.setKind(org.eclipse.lsp4j.CompletionItemKind.Method);
                    item.setDetail(new String(proposal.getSignature()));
                }
                case CompletionProposal.FIELD_REF, CompletionProposal.LOCAL_VARIABLE_REF -> {
                    item.setLabel(name);
                    item.setKind(org.eclipse.lsp4j.CompletionItemKind.Field);
                }
                case CompletionProposal.PACKAGE_REF -> {
                    item.setLabel(name);
                    item.setKind(org.eclipse.lsp4j.CompletionItemKind.Module);
                }
                case CompletionProposal.KEYWORD -> {
                    item.setLabel(name);
                    item.setKind(org.eclipse.lsp4j.CompletionItemKind.Keyword);
                }
                default -> {
                    item.setLabel(name.isEmpty() ? completion : name);
                    item.setKind(org.eclipse.lsp4j.CompletionItemKind.Text);
                }
            }
            int start = proposal.getReplaceStart();
            int end = proposal.getReplaceEnd();
            if (start >= 0 && end <= bufferText.length() && start <= end) {
                var startPos = DiagnosticMapper.positionOf(bufferText, start);
                var endPos = DiagnosticMapper.positionOf(bufferText, end);
                item.setTextEdit(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new org.eclipse.lsp4j.TextEdit(
                        new org.eclipse.lsp4j.Range(startPos, endPos), completion)));
            }
            return item;
        } catch (Throwable t) {
            Log.warn("proposal conversion failed: " + t);
            return null;
        }
    }

    private static String simpleNameOf(char[] signature) {
        if (signature == null) {
            return "";
        }
        String sig = new String(signature); // e.g. Ljava.util.ArrayList;
        String s = sig.startsWith("L") && sig.endsWith(";") ? sig.substring(1, sig.length() - 1) : sig;
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    // ------------------------------------------------------------------ diagnostics

    /** Reconciles the working copy and returns the LSP diagnostics for it. */
    public synchronized List<org.eclipse.lsp4j.Diagnostic> diagnostics(String uri) {
        ICompilationUnit cu = workingCopies.get(uri);
        if (cu == null) {
            return List.of();
        }
        try {
            cu.reconcile(ICompilationUnit.NO_AST, true /* force problem detection */, null, new NullProgressMonitor());
        } catch (Exception e) {
            Log.warn("reconcile failed for " + uri + ": " + e);
        }
        List<org.eclipse.lsp4j.Diagnostic> result = lastProblems.getOrDefault(uri, List.of());
        return result;
    }

    /** Collects reconcile problems per URI (registered at becomeWorkingCopy). */
    private final class ProblemCollector implements IProblemRequestor {
        private final String uri;
        private final ConcurrentLinkedQueue<IProblem> collected = new ConcurrentLinkedQueue<>();

        ProblemCollector(String uri) {
            this.uri = uri;
        }

        @Override
        public void acceptProblem(IProblem problem) {
            collected.add(problem);
        }

        @Override
        public void beginReporting() {
            collected.clear();
        }

        @Override
        public void endReporting() {
            ICompilationUnit cu = workingCopies.get(uri);
            String text = null;
            try {
                text = cu == null ? null : cu.getBuffer().getContents();
            } catch (Exception e) {
                // fall through with null
            }
            List<org.eclipse.lsp4j.Diagnostic> diags = new ArrayList<>();
            for (IProblem p : collected) {
                diags.add(DiagnosticMapper.of(text == null ? "" : text, p));
            }
            lastProblems.put(uri, diags);
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }

    // ---------------------------------------------------------------- hover

    public synchronized org.eclipse.lsp4j.Hover hover(String uri, int line, int character) {
        ICompilationUnit cu = workingCopies.get(uri);
        if (cu == null) {
            return null;
        }
        try {
            int offset = offsetOf(cu.getBuffer().getContents(), line, character);
            if (offset < 0) {
                return null;
            }
            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length == 0) {
                return null;
            }
            String markdown = describe(elements[0]);
            org.eclipse.lsp4j.Hover hover = new org.eclipse.lsp4j.Hover();
            hover.setContents(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                    new org.eclipse.lsp4j.MarkupContent("markdown", markdown)));
            return hover;
        } catch (Exception e) {
            Log.warn("hover failed: " + e, e);
            return null;
        }
    }

    /** Renders a JDT type signature without Signature.toString (which rejects plain
     *  type signatures on this JDK). */
    private static String typeName(String typeSignature) {
        String s = new String(typeSignature).trim();
        int dims = 0;
        while (!s.isEmpty() && s.charAt(0) == '[') {
            dims++;
            s = s.substring(1);
        }
        String base;
        switch (s) {
            case "V" -> base = "void";
            case "I" -> base = "int";
            case "J" -> base = "long";
            case "D" -> base = "double";
            case "F" -> base = "float";
            case "Z" -> base = "boolean";
            case "B" -> base = "byte";
            case "C" -> base = "char";
            case "S" -> base = "short";
            default -> {
                if ((s.startsWith("L") || s.startsWith("Q")) && s.endsWith(";")) {
                    s = s.substring(1, s.length() - 1);
                }
                int dot = s.lastIndexOf('.');
                base = dot >= 0 ? s.substring(dot + 1) : s;
            }
        }
        return base + "[]".repeat(dims);
    }

    private String describe(IJavaElement el) throws Exception {
        if (el instanceof org.eclipse.jdt.core.IType t) {
            String kind = t.isInterface() ? "interface" : t.isEnum() ? "enum" : t.isRecord() ? "record" : "class";
            return "```java\n" + kind + " " + t.getFullyQualifiedName() + "\n```";
        }
        if (el instanceof org.eclipse.jdt.core.IMethod m) {
            StringBuilder sb = new StringBuilder();
            sb.append(typeName(m.getReturnType()));
            sb.append(' ').append(m.getElementName()).append('(');
            String[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(typeName(params[i]));
            }
            sb.append(')');
            String decl = m.getDeclaringType() == null ? "" : m.getDeclaringType().getFullyQualifiedName() + ".";
            return "```java\n" + decl + sb + "\n```";
        }
        if (el instanceof org.eclipse.jdt.core.IField f) {
            return "```java\n" + typeName(f.getTypeSignature()) + " " + f.getElementName() + "\n```";
        }
        return el.getElementType() + " " + el.getElementName();
    }

    // ---------------------------------------------------------------- definition

    public synchronized List<org.eclipse.lsp4j.Location> definition(String uri, int line, int character) {
        List<org.eclipse.lsp4j.Location> out = new ArrayList<>();
        try {
            ICompilationUnit cu = workingCopies.get(uri);
            if (cu == null) {
                return out;
            }
            int offset = offsetOf(cu.getBuffer().getContents(), line, character);
            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length == 0) {
                return out;
            }
            IJavaElement el = elements[0];
            org.eclipse.lsp4j.Location loc = elementLocation(el);
            if (loc != null) {
                out.add(loc);
            }
        } catch (Exception e) {
            Log.warn("definition failed: " + e);
        }
        return out;
    }

    /** Source location of an element when it lives in a compilation unit of this project. */
    /** Source location of an element: project source, attached sources, or decompiled jar. */
    private org.eclipse.lsp4j.Location elementLocation(IJavaElement el) throws Exception {
        if (!(el instanceof org.eclipse.jdt.core.ISourceReference sr)) {
            return null;
        }
        // project source / working copy
        if (el.getOpenable() instanceof ICompilationUnit icu) {
            org.eclipse.core.resources.IResource resource = el.getResource();
            if (resource == null) {
                return null;
            }
            String source = icu.getSource();
            org.eclipse.jdt.core.ISourceRange range = sr.getNameRange();
            if (range == null || range.getOffset() < 0) {
                range = sr.getSourceRange();
            }
            int start = Math.max(0, range.getOffset());
            int end = start + Math.max(1, range.getLength());
            org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
            loc.setUri(resource.getLocationURI().toString());
            loc.setRange(new org.eclipse.lsp4j.Range(
                    DiagnosticMapper.positionOf(source, start),
                    DiagnosticMapper.positionOf(source, end)));
            return loc;
        }
        // class file inside a jar / jrt: attached sources first, then FernFlower
        if (el.getOpenable() instanceof IClassFile classFile) {
            String source = null;
            boolean realSource = false;
            try {
                source = classFile.getSource();
                realSource = source != null;
            } catch (Exception ignore) {
                // no attached source
            }
            if (source == null) {
                source = Decompiler.decompile(classFile);
            }
            if (source == null) {
                return null;
            }
            Path file = Decompiler.decompiledFileFor(classFile);
            Files.writeString(file, source, StandardCharsets.UTF_8);
            org.eclipse.lsp4j.Position start;
            org.eclipse.lsp4j.Position end;
            if (realSource && sr.getNameRange() != null && sr.getNameRange().getOffset() >= 0) {
                start = DiagnosticMapper.positionOf(source, sr.getNameRange().getOffset());
                end = DiagnosticMapper.positionOf(source,
                        sr.getNameRange().getOffset() + Math.max(1, sr.getNameRange().getLength()));
            } else {
                int line = memberLineInSource(source, el);
                start = new org.eclipse.lsp4j.Position(line, 0);
                end = new org.eclipse.lsp4j.Position(line + 1, 0);
            }
            org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
            loc.setUri(file.toUri().toString());
            loc.setRange(new org.eclipse.lsp4j.Range(start, end));
            return loc;
        }
        return null;
    }

    /** Best-effort line of a member inside decompiled source (name search). */
    private static int memberLineInSource(String source, IJavaElement el) {
        String needle = el.getElementName();
        if (el instanceof org.eclipse.jdt.core.IMethod) {
            needle = needle + "(";
        }
        int idx = source.indexOf(needle);
        if (idx < 0) {
            return 0;
        }
        return DiagnosticMapper.positionOf(source, idx).getLine();
    }

    // ---------------------------------------------------------------- references

    public synchronized List<org.eclipse.lsp4j.Location> references(String uri, int line, int character) {
        List<org.eclipse.lsp4j.Location> out = new ArrayList<>();
        try {
            ICompilationUnit cu = workingCopies.get(uri);
            if (cu == null) {
                return out;
            }
            int offset = offsetOf(cu.getBuffer().getContents(), line, character);
            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length == 0) {
                return out;
            }
            IJavaElement el = elements[0];
            org.eclipse.lsp4j.Location decl = elementLocation(el);
            if (decl != null) {
                out.add(decl);
            }
            org.eclipse.jdt.core.search.SearchPattern pattern = org.eclipse.jdt.core.search.SearchPattern
                    .createPattern(el, org.eclipse.jdt.core.search.IJavaSearchConstants.REFERENCES);
            if (pattern == null) {
                return out;
            }
            new org.eclipse.jdt.core.search.SearchEngine().search(pattern,
                    new org.eclipse.jdt.core.search.SearchParticipant[] {
                            org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant() },
                    org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope(),
                    new org.eclipse.jdt.core.search.SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(org.eclipse.jdt.core.search.SearchMatch match) {
                            org.eclipse.core.resources.IResource res = match.getResource();
                            if (res == null || res.getLocationURI() == null) {
                                return;
                            }
                            org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
                            loc.setUri(res.getLocationURI().toString());
                            loc.setRange(new org.eclipse.lsp4j.Range(
                                    DiagnosticMapper.positionOf("", match.getOffset()),
                                    DiagnosticMapper.positionOf("", match.getOffset() + Math.max(1, match.getLength()))));
                            out.add(loc);
                        }
                    }, new NullProgressMonitor());
        } catch (Exception e) {
            Log.warn("references failed: " + e, e);
        }
        return out;
    }

    // ---------------------------------------------------------------- document symbols

    public synchronized List<org.eclipse.lsp4j.DocumentSymbol> documentSymbols(String uri) {
        List<org.eclipse.lsp4j.DocumentSymbol> out = new ArrayList<>();
        try {
            ICompilationUnit cu = workingCopies.get(uri);
            if (cu == null) {
                cu = findCompilationUnit(uri);
            }
            if (cu == null) {
                return out;
            }
            String source = cu.getSource();
            for (org.eclipse.jdt.core.IJavaElement child : cu.getChildren()) {
                if (child instanceof org.eclipse.jdt.core.IType type) {
                    out.add(symbolFor(type, source));
                }
            }
        } catch (Exception e) {
            Log.warn("documentSymbols failed: " + e);
        }
        return out;
    }

    private org.eclipse.lsp4j.DocumentSymbol symbolFor(org.eclipse.jdt.core.IType type, String source) throws Exception {
        org.eclipse.lsp4j.DocumentSymbol symbol = new org.eclipse.lsp4j.DocumentSymbol();
        symbol.setName(type.getElementName());
        symbol.setKind(type.isInterface() ? org.eclipse.lsp4j.SymbolKind.Interface
                : type.isEnum() ? org.eclipse.lsp4j.SymbolKind.Enum : org.eclipse.lsp4j.SymbolKind.Class);
        symbol.setRange(rangeOf(type, source));
        symbol.setSelectionRange(rangeFrom(type.getNameRange(), source));
        List<org.eclipse.lsp4j.DocumentSymbol> children = new ArrayList<>();
        for (org.eclipse.jdt.core.IJavaElement child : type.getChildren()) {
            if (child instanceof org.eclipse.jdt.core.IType inner) {
                children.add(symbolFor(inner, source));
            } else if (child instanceof org.eclipse.jdt.core.IMethod m) {
                children.add(leafSymbol(m.getElementName() + "()", org.eclipse.lsp4j.SymbolKind.Method, m, source));
            } else if (child instanceof org.eclipse.jdt.core.IField f) {
                children.add(leafSymbol(f.getElementName(), org.eclipse.lsp4j.SymbolKind.Field, f, source));
            }
        }
        symbol.setChildren(children);
        return symbol;
    }

    private org.eclipse.lsp4j.DocumentSymbol leafSymbol(String name, org.eclipse.lsp4j.SymbolKind kind,
            org.eclipse.jdt.core.IMember member, String source) throws Exception {
        org.eclipse.lsp4j.DocumentSymbol symbol = new org.eclipse.lsp4j.DocumentSymbol();
        symbol.setName(name);
        symbol.setKind(kind);
        symbol.setRange(rangeOf(member, source));
        symbol.setSelectionRange(rangeFrom(member.getNameRange(), source));
        symbol.setChildren(List.of());
        return symbol;
    }

    private org.eclipse.lsp4j.Range rangeOf(org.eclipse.jdt.core.IMember member, String source) throws Exception {
        org.eclipse.jdt.core.ISourceRange r = member.getSourceRange();
        return rangeFrom(r, source);
    }

    private org.eclipse.lsp4j.Range rangeFrom(org.eclipse.jdt.core.ISourceRange r, String source) {
        if (r == null || r.getOffset() < 0) {
            return new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0, 0), new org.eclipse.lsp4j.Position(0, 0));
        }
        int start = r.getOffset();
        int end = start + r.getLength();
        return new org.eclipse.lsp4j.Range(
                DiagnosticMapper.positionOf(source, start),
                DiagnosticMapper.positionOf(source, end));
    }

    // ---------------------------------------------------------------- workspace symbols

    public synchronized List<org.eclipse.lsp4j.SymbolInformation> workspaceSymbols(String query) {
        List<org.eclipse.lsp4j.SymbolInformation> out = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        try {
            new org.eclipse.jdt.core.search.SearchEngine().searchAllTypeNames(null,
                    org.eclipse.jdt.core.search.SearchPattern.R_PATTERN_MATCH,
                    ("*" + query.trim() + "*").toCharArray(),
                    org.eclipse.jdt.core.search.SearchPattern.R_PATTERN_MATCH,
                    org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE,
                    org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope(),
                    new org.eclipse.jdt.core.search.TypeNameMatchRequestor() {
                        @Override
                        public void acceptTypeNameMatch(org.eclipse.jdt.core.search.TypeNameMatch match) {
                            org.eclipse.lsp4j.SymbolInformation info = new org.eclipse.lsp4j.SymbolInformation();
                            info.setName(match.getFullyQualifiedName());
                            info.setKind(org.eclipse.lsp4j.SymbolKind.Class);
                            info.setContainerName(match.getPackageName());
                            org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
                            try {
                                org.eclipse.jdt.core.IType type = match.getType();
                                org.eclipse.core.resources.IResource res = type.getResource();
                                loc.setUri(res != null && res.getLocationURI() != null
                                        ? res.getLocationURI().toString()
                                        : (type.getPath() == null ? "" : type.getPath().toFile().toURI().toString()));
                            } catch (Exception e) {
                                loc.setUri("");
                            }
                            loc.setRange(new org.eclipse.lsp4j.Range(
                                    new org.eclipse.lsp4j.Position(0, 0), new org.eclipse.lsp4j.Position(0, 0)));
                            info.setLocation(loc);
                            out.add(info);
                        }
                    },
                    org.eclipse.jdt.core.search.IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    new NullProgressMonitor());
        } catch (Exception e) {
            Log.warn("workspaceSymbols failed: " + e);
        }
        return out;
    }

    // ---------------------------------------------------------------- format

    public synchronized List<org.eclipse.lsp4j.TextEdit> format(String uri) {
        List<org.eclipse.lsp4j.TextEdit> out = new ArrayList<>();
        try {
            ICompilationUnit cu = workingCopies.get(uri);
            if (cu == null) {
                return out;
            }
            String source = cu.getBuffer().getContents();
            org.eclipse.jdt.core.formatter.CodeFormatter formatter = org.eclipse.jdt.core.ToolFactory
                    .createCodeFormatter(CompilerDefaults.options());
            org.eclipse.text.edits.TextEdit edit = formatter.format(
                    org.eclipse.jdt.core.formatter.CodeFormatter.K_COMPILATION_UNIT,
                    source, 0, source.length(), 0, "\n");
            if (edit == null) {
                Log.warn("formatter returned null (source too broken?)");
                return out;
            }
            collectEdits(edit, source, out);
        } catch (Exception e) {
            Log.warn("format failed: " + e);
        }
        return out;
    }

    private static String textOf(org.eclipse.text.edits.TextEdit edit) {
        if (edit instanceof org.eclipse.text.edits.ReplaceEdit re) {
            return re.getText();
        }
        if (edit instanceof org.eclipse.text.edits.InsertEdit ie) {
            return ie.getText();
        }
        return "";
    }

    private void collectEdits(org.eclipse.text.edits.TextEdit edit, String source, List<org.eclipse.lsp4j.TextEdit> out) {
        int offset = edit.getOffset();
        int length = edit.getLength();
        String text = textOf(edit);
        if (!(edit instanceof org.eclipse.text.edits.MultiTextEdit) && offset >= 0
                && (length > 0 || !text.isEmpty())) {
            out.add(new org.eclipse.lsp4j.TextEdit(
                    new org.eclipse.lsp4j.Range(
                            DiagnosticMapper.positionOf(source, offset),
                            DiagnosticMapper.positionOf(source, offset + length)),
                    text));
        }
        for (org.eclipse.text.edits.TextEdit child : edit.getChildren()) {
            collectEdits(child, source, out);
        }
    }

    // ---------------------------------------------------------------- build + launch support

    /** Runs the java builder over all projects (compiles to output dirs, creates problem markers). */
    public synchronized boolean buildWorkspace() {
        if (entries.isEmpty()) {
            return false;
        }
        try {
            org.eclipse.core.resources.IWorkspace ws = ResourcesPlugin.getWorkspace();
            ws.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
            return true;
        } catch (Exception e) {
            Log.warn("workspace build failed: " + e);
            return false;
        }
    }

    /** Runs a workspace build and publishes problem-marker diagnostics per file. */
    public synchronized boolean buildAndPublish(java.util.function.BiConsumer<String, List<org.eclipse.lsp4j.Diagnostic>> publisher) {
        boolean ok = buildWorkspace();
        Map<String, List<org.eclipse.lsp4j.Diagnostic>> byUri = problemDiagnostics();
        byUri.forEach(publisher);
        // also clear diagnostics for projects with no markers? skip: only files with markers change
        return ok;
    }

    /** Problem markers after a build, grouped by resource URI. */
    public Map<String, List<org.eclipse.lsp4j.Diagnostic>> problemDiagnostics() {
        Map<String, List<org.eclipse.lsp4j.Diagnostic>> out = new java.util.LinkedHashMap<>();
        for (ProjectEntry entry : entries) {
            try {
                org.eclipse.core.resources.IMarker[] markers = entry.project.findMarkers(
                        org.eclipse.core.resources.IMarker.PROBLEM, true,
                        org.eclipse.core.resources.IResource.DEPTH_INFINITE);
                for (org.eclipse.core.resources.IMarker m : markers) {
                    if (m.getResource() == null || m.getResource().getLocationURI() == null) {
                        continue;
                    }
                    Integer severity = m.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
                            org.eclipse.core.resources.IMarker.SEVERITY_INFO);
                    Integer line = m.getAttribute(org.eclipse.core.resources.IMarker.LINE_NUMBER, 1);
                    String message = m.getAttribute(org.eclipse.core.resources.IMarker.MESSAGE, "");
                    org.eclipse.lsp4j.Diagnostic d = new org.eclipse.lsp4j.Diagnostic(
                            new org.eclipse.lsp4j.Range(
                                    new org.eclipse.lsp4j.Position(Math.max(0, line - 1), 0),
                                    new org.eclipse.lsp4j.Position(Math.max(0, line - 1), 0)),
                            message);
                    d.setSeverity(severity >= 2 ? org.eclipse.lsp4j.DiagnosticSeverity.Error
                            : severity == 1 ? org.eclipse.lsp4j.DiagnosticSeverity.Warning
                                    : org.eclipse.lsp4j.DiagnosticSeverity.Information);
                    d.setSource("jdt-build");
                    out.computeIfAbsent(m.getResource().getLocationURI().toString(), k -> new ArrayList<>()).add(d);
                }
            } catch (Exception e) {
                Log.warn("marker harvest failed for " + entry.root + ": " + e);
            }
        }
        return out;
    }

    /**
     * Launch classpath for a main class: project output dirs + dependency jars
     * (mirrors jdt.ls java/resolveClasspath; returns {classpath, jreHome}).
     */
    public synchronized Map<String, Object> resolveClasspath(String mainClass, String projectName) {
        ProjectEntry entry = entries.get(0);
        if (projectName != null && !projectName.isBlank()) {
            for (ProjectEntry e : entries) {
                if (e.project.getName().equals(projectName)) {
                    entry = e;
                }
            }
        }
        List<String> cp = new ArrayList<>();
        try {
            for (org.eclipse.jdt.core.IClasspathEntry cpe : entry.javaProject.getResolvedClasspath(false)) {
                if (cpe.getEntryKind() == org.eclipse.jdt.core.IClasspathEntry.CPE_LIBRARY) {
                    String p = cpe.getPath().toString();
                    if (!p.contains("jrt-fs.jar")) {
                        cp.add(p);
                    }
                }
            }
        } catch (JavaModelException e) {
            Log.warn("resolved classpath failed: " + e);
        }
        Path bin = entry.project.getLocation().toFile().toPath().resolve("bin");
        if (Files.isDirectory(bin)) {
            cp.add(0, bin.toString());
        }
        Path jre = dev.jdtls.lite.bootstrap.JdkLocator.resolve();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("classpath", cp);
        result.put("jreHome", jre.toString());
        return result;
    }

    // ---------------------------------------------------------------- rename (LTK with textual fallback)

    public synchronized org.eclipse.lsp4j.WorkspaceEdit rename(String uri, int line, int character, String newName) {
        try {
            ICompilationUnit cu = workingCopies.get(uri);
            if (cu == null) {
                cu = findCompilationUnit(uri);
            }
            if (cu != null) {
                int offset = offsetOf(cu.getBuffer().getContents(), line, character);
                IJavaElement[] elements = cu.codeSelect(offset, 0);
                if (elements.length > 0) {
                    org.eclipse.lsp4j.WorkspaceEdit ltkEdit = LtkRename.rename(elements[0], newName,
                            new NullProgressMonitor());
                    if (ltkEdit != null) {
                        return ltkEdit;
                    }
                }
            }
        } catch (Exception e) {
            Log.warn("ltk rename path failed, falling back to textual: " + e);
        }
        return renameTextual(uri, line, character, newName);
    }

    private synchronized org.eclipse.lsp4j.WorkspaceEdit renameTextual(String uri, int line, int character,
            String newName) {
        try {
            ICompilationUnit cu = workingCopies.get(uri);
            if (cu == null) {
                return null;
            }
            int offset = offsetOf(cu.getBuffer().getContents(), line, character);
            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length == 0) {
                return null;
            }
            IJavaElement el = elements[0];
            if (!(el instanceof org.eclipse.jdt.core.IMethod || el instanceof org.eclipse.jdt.core.IType
                    || el instanceof org.eclipse.jdt.core.IField || el instanceof org.eclipse.jdt.core.ILocalVariable
                    || el instanceof org.eclipse.jdt.core.ITypeParameter)) {
                Log.warn("rename: unsupported element type " + el.getElementType());
                return null;
            }
            String oldName = el.getElementName();
            org.eclipse.jdt.core.search.SearchPattern pattern = org.eclipse.jdt.core.search.SearchPattern
                    .createPattern(el, org.eclipse.jdt.core.search.IJavaSearchConstants.ALL_OCCURRENCES);
            Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.LinkedHashMap<>();
            new org.eclipse.jdt.core.search.SearchEngine().search(pattern,
                    new org.eclipse.jdt.core.search.SearchParticipant[] {
                            org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant() },
                    org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope(),
                    new org.eclipse.jdt.core.search.SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(org.eclipse.jdt.core.search.SearchMatch match) {
                            try {
                                org.eclipse.core.resources.IResource res = match.getResource();
                                if (res == null || res.getLocationURI() == null) {
                                    return;
                                }
                                String fileUri = res.getLocationURI().toString();
                                String text = documentText(res, fileUri);
                                if (text == null) {
                                    return;
                                }
                                int start = match.getOffset();
                                int len = Math.max(1, match.getLength());
                                if (start < 0 || start + len > text.length()) {
                                    return;
                                }
                                // validate the region is really the identifier; declaration matches
                                // may span the whole member — widen to the nearest exact occurrence
                                int idx = -1;
                                if (text.regionMatches(start, oldName, 0, oldName.length())) {
                                    idx = start;
                                } else {
                                    int windowStart = Math.max(0, start - 60);
                                    int windowEnd = Math.min(text.length(), start + len + 60);
                                    idx = text.indexOf(oldName, windowStart);
                                    while (idx >= 0 && idx < windowEnd) {
                                        boolean leftBoundary = idx == 0
                                                || !Character.isJavaIdentifierPart(text.charAt(idx - 1));
                                        int after = idx + oldName.length();
                                        boolean rightBoundary = after >= text.length()
                                                || !Character.isJavaIdentifierPart(text.charAt(after));
                                        if (leftBoundary && rightBoundary) {
                                            break;
                                        }
                                        idx = text.indexOf(oldName, idx + 1);
                                    }
                                }
                                if (idx < 0) {
                                    return;
                                }
                                changes.computeIfAbsent(fileUri, k -> new ArrayList<>())
                                        .add(new org.eclipse.lsp4j.TextEdit(
                                                new org.eclipse.lsp4j.Range(
                                                        DiagnosticMapper.positionOf(text, idx),
                                                        DiagnosticMapper.positionOf(text, idx + oldName.length())),
                                                newName));
                            } catch (Exception e) {
                                Log.warn("rename match handling failed: " + e);
                            }
                        }
                    }, new NullProgressMonitor());
            if (changes.isEmpty()) {
                return null;
            }
            org.eclipse.lsp4j.WorkspaceEdit edit = new org.eclipse.lsp4j.WorkspaceEdit();
            edit.setChanges(changes);
            Log.info("rename " + oldName + " -> " + newName + ": " + changes.size() + " file(s)");
            return edit;
        } catch (Exception e) {
            Log.warn("rename failed: " + e, e);
            return null;
        }
    }

    /** Text of a document: prefer the open working copy, fall back to disk. */
    private String documentText(org.eclipse.core.resources.IResource resource, String fileUri) throws Exception {
        ICompilationUnit cu = workingCopies.get(fileUri);
        if (cu != null) {
            return cu.getBuffer().getContents();
        }
        if (resource.getLocation() != null && resource.getLocation().toFile().isFile()) {
            return Files.readString(resource.getLocation().toFile().toPath(), StandardCharsets.UTF_8);
        }
        return null;
    }
}
