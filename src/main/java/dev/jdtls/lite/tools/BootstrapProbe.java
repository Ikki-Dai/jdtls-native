package dev.jdtls.lite.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import dev.jdtls.lite.Log;
import dev.jdtls.lite.bootstrap.PlatformBoot;

/**
 * Standalone probe for the platform bootstrap: boots the registry/preferences/
 * workspace/JavaCore, then drives completion and index search through the real
 * JavaModel. Run: java -cp "target/classes;target/libs/*" dev.jdtls.lite.tools.BootstrapProbe
 */
public final class BootstrapProbe {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        Path base = Files.createTempDirectory("jdtlsp-probe-");
        Path configDir = base.resolve("config");
        Path workspaceDir = base.resolve("ws");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceDir);

        System.out.println("== stage 1: platform boot");
        PlatformBoot.boot(configDir, workspaceDir);
        System.out.println("registry ok, extensions registered: "
                + PlatformBoot.extensionRegistry.getExtensionPoints().length + " extension points");

        System.out.println("== stage 2: workspace");
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        System.out.println("workspace root: " + root.getLocation());

        System.out.println("== stage 3: project + classpath");
        IProject project = root.getProject("probe");
        IProjectDescription desc = project.getWorkspace().newProjectDescription("probe");
        desc.setLocationURI(base.resolve("probe").toUri());
        project.create(desc, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        IProjectDescription desc2 = project.getDescription();
        desc2.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(desc2, new NullProgressMonitor());
        IJavaProject javaProject = JavaCore.create(project);

        // source file
        IFolder srcFolder = project.getFolder("src");
        srcFolder.create(true, true, new NullProgressMonitor());
        IFolder pkg = srcFolder.getFolder("demo");
        pkg.create(true, true, new NullProgressMonitor());
        Path srcFile = base.resolve("probe/src/demo/Probe.java");
        Files.createDirectories(srcFile.getParent());
        String source = "package demo;\n\nimport java.util.L;\n\npublic class Probe {\n    java.util.List<String> names = new java.util.ArrayL;\n    StringBuilder sb = new StringBuilder();\n}\n";
        Files.writeString(srcFile, source);
        org.eclipse.core.resources.IFile probeIFile = project.getFile("src/demo/Probe.java");
        probeIFile.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, new NullProgressMonitor());

        IPath srcEntry = new org.eclipse.core.runtime.Path("/probe/src");
        List<org.eclipse.jdt.core.IClasspathEntry> entries = new ArrayList<>();
        entries.add(JavaCore.newSourceEntry(srcEntry));
        String jrtFs = Path.of(System.getProperty("java.home"), "lib", "jrt-fs.jar").toString();
        entries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(jrtFs), null, null));
        javaProject.setRawClasspath(entries.toArray(org.eclipse.jdt.core.IClasspathEntry[]::new),
                new org.eclipse.core.runtime.Path("/probe/bin"), new NullProgressMonitor());
        System.out.println("classpath set; jrt-fs=" + jrtFs);

        System.out.println("== stage 4: completion");
        ICompilationUnit cu = (ICompilationUnit) JavaCore.create(probeIFile);
        System.out.println("cu exists=" + cu.exists() + ", file exists=" + probeIFile.exists()
                + ", file loc=" + probeIFile.getLocation());
        List<String> problems = new ArrayList<>();
        cu.becomeWorkingCopy(new org.eclipse.jdt.core.IProblemRequestor() {
            @Override
            public void acceptProblem(org.eclipse.jdt.core.compiler.IProblem problem) {
                problems.add(problem.getMessage());
            }

            @Override
            public void beginReporting() {
            }

            @Override
            public void endReporting() {
            }

            @Override
            public boolean isActive() {
                return true;
            }
        }, new NullProgressMonitor());
        String sourceText = cu.getSource();
        System.out.println("source length=" + (sourceText == null ? "null" : sourceText.length()));
        int offset = sourceText.indexOf("ArrayL") + "ArrayL".length();
        System.out.println("completion offset=" + offset + " (char=" + sourceText.charAt(offset - 1) + ")");
        List<String> proposals = new ArrayList<>();
        CompletionRequestor requestor = new CompletionRequestor() {
            @Override
            public void accept(CompletionProposal proposal) {
                String name = safe(proposal);
                String label = switch (proposal.getKind()) {
                    case CompletionProposal.TYPE_REF -> name;
                    case CompletionProposal.METHOD_REF -> name + "()";
                    case CompletionProposal.FIELD_REF -> name;
                    case CompletionProposal.PACKAGE_REF -> "(pkg) " + name;
                    case CompletionProposal.KEYWORD -> "(kw) " + name;
                    default -> "(kind=" + proposal.getKind() + ") " + name;
                };
                proposals.add(label);
            }

            private String safe(CompletionProposal proposal) {
                char[] n = proposal.getName();
                if (n == null) {
                    char[] sig = proposal.getSignature();
                    return sig == null ? "(no-name)" : new String(sig);
                }
                return new String(n);
            }
        };
        int[] kinds = { CompletionProposal.TYPE_REF, CompletionProposal.METHOD_REF, CompletionProposal.FIELD_REF,
                CompletionProposal.PACKAGE_REF, CompletionProposal.KEYWORD };
        for (int k : kinds) {
            requestor.setIgnored(k, false);
        }
        System.out.println("compliance=" + javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true)
                + ", source=" + javaProject.getOption(JavaCore.COMPILER_SOURCE, true));
        try {
            cu.codeComplete(offset, requestor, new NullProgressMonitor());
        } catch (Throwable t2) {
            System.out.println("codeComplete threw: " + t2);
        }

        // direct internal engine call with debug tracing
        try {
            org.eclipse.jdt.internal.codeassist.CompletionEngine.DEBUG = true;
            try {
                java.lang.reflect.Field f = org.eclipse.jdt.internal.core.JavaModelManager.class
                        .getDeclaredField("TRACE_TO_STDOUT");
                f.setAccessible(true);
                f.setBoolean(null, true);
            } catch (Throwable ignore) {
                System.out.println("TRACE_TO_STDOUT not settable: " + ignore);
            }
            org.eclipse.jdt.internal.core.SearchableEnvironment se2 =
                    new org.eclipse.jdt.internal.core.SearchableEnvironment(
                            (org.eclipse.jdt.internal.core.JavaProject) javaProject,
                            (org.eclipse.jdt.core.WorkingCopyOwner) null);
            List<String> direct = new ArrayList<>();
            org.eclipse.jdt.internal.codeassist.CompletionEngine engine =
                    new org.eclipse.jdt.internal.codeassist.CompletionEngine(se2, requestor,
                            javaProject.getOptions(true), javaProject, null, new NullProgressMonitor());
            engine.complete((org.eclipse.jdt.internal.compiler.env.ICompilationUnit) cu, offset, 0, cu);
            System.out.println("direct engine proposals: " + direct.size());
        } catch (Throwable t4) {
            System.out.println("direct engine threw:"); t4.printStackTrace(System.out);
        }
        System.out.println("problems during completion: " + problems.size() + " -> "
                + problems.subList(0, Math.min(5, problems.size())));

        // findType through the model (index-backed)
        org.eclipse.jdt.core.IType listType = javaProject.findType("java.util.List", new NullProgressMonitor());
        System.out.println("findType(java.util.List)=" + listType);

        // searchable environment prefix lookup (the entry point the completion engine uses)
        try {
            org.eclipse.jdt.internal.core.SearchableEnvironment se =
                    new org.eclipse.jdt.internal.core.SearchableEnvironment(
                            (org.eclipse.jdt.internal.core.JavaProject) javaProject,
                            (org.eclipse.jdt.core.WorkingCopyOwner) null);
            List<String> found = new ArrayList<>();
            se.findTypes("java.util.ArrayL".toCharArray(), false, 0,
                    org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE,
                    new org.eclipse.jdt.internal.codeassist.ISearchRequestor() {
                        @Override
                        public void acceptType(char[] pkg, char[] simple, char[][] enclosing, int modifiers,
                                org.eclipse.jdt.internal.compiler.env.AccessRestriction access) {
                            found.add(new String(pkg) + "." + new String(simple));
                        }

                        @Override
                        public void acceptConstructor(int modifiers, char[] simpleName, int parameterCount,
                                char[] signature, char[][] parameterTypes, char[][] parameterNames,
                                int typeModifiers, char[] packageName, int extraFlags, String path,
                                org.eclipse.jdt.internal.compiler.env.AccessRestriction access) {
                        }

                        @Override
                        public void acceptPackage(char[] packageName) {
                        }

                        @Override
                        public void acceptModule(char[] moduleName) {
                        }
                    }, new NullProgressMonitor());
            System.out.println("SearchableEnvironment.findTypes(ArrayL): " + found.size() + " -> "
                    + found.subList(0, Math.min(5, found.size())));
        } catch (Throwable t3) {
            System.out.println("se.findTypes threw: " + t3);
        }

        System.out.println("completion proposals (TYPE_REF): " + proposals.size() + " -> " + proposals.subList(0, Math.min(8, proposals.size())));

        System.out.println("== stage 5: index search");
        List<String> types = new ArrayList<>();
        new SearchEngine().searchAllTypeNames(null, SearchPattern.R_PATTERN_MATCH,
                "Str*".toCharArray(), SearchPattern.R_PATTERN_MATCH,
                org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE,
                SearchEngine.createWorkspaceScope(),
                new TypeNameMatchRequestor() {
                    @Override
                    public void acceptTypeNameMatch(TypeNameMatch match) {
                        types.add(match.getFullyQualifiedName());
                    }
                },
                org.eclipse.jdt.core.search.IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, new NullProgressMonitor());
        System.out.println("searchAllTypeNames(Str*): " + types.size() + " -> " + types.subList(0, Math.min(8, types.size())));

        System.out.println("== PROBE DONE (config=" + configDir + ")");
        Log.info("probe finished");
    }

    private BootstrapProbe() {
    }
}
