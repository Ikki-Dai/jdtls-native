package dev.jdtls.lite.engine;

import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import dev.jdtls.lite.Log;

/**
 * Workspace-free JDT front end. Uses only {@link ASTParser} with
 * {@code setEnvironment(...)} — the supported standalone path (no JavaModel,
 * no Eclipse workspace, no OSGi). A single ASTParser instance is reused; it is
 * not thread-safe, so callers must synchronize (diagnostics executor is single-threaded).
 */
public final class JavaEngine {

    private final ASTParser parser;

    public JavaEngine() {
        this.parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setStatementsRecovery(true);
        parser.setBindingsRecovery(true);
        parser.setCompilerOptions(CompilerDefaults.options());
    }

    /**
     * Parses one compilation unit and resolves bindings against the given classpath.
     *
     * @param unitName   slash-separated path relative to a source root, e.g. {@code demo/Bad.java};
     *                   required for correct package resolution
     * @param source     the compilation unit text
     * @param classpath  classpath entries (dirs and jars), may be empty
     * @param sourcepath source roots, may be empty
     */
    public synchronized ParseResult parse(String unitName, String source, String[] classpath, String[] sourcepath) {
        // explicit jrt-fs.jar entry (see WorkspaceManager.classpath) resolves the JDK in both
        // JVM and native runs; do not rely on the running VM's bootclasspath detection
        parser.setEnvironment(classpath, sourcepath, null /* default encoding */, false);
        parser.setUnitName(unitName);
        parser.setSource(source.toCharArray());
        try {
            CompilationUnit cu = (CompilationUnit) parser.createAST(null);
            List<org.eclipse.jdt.core.compiler.IProblem> problems =
                    cu == null ? List.of() : List.of(cu.getProblems());
            return new ParseResult(unitName, source, cu, problems);
        } catch (RuntimeException e) {
            Log.error("AST parse failed for " + unitName, e);
            return new ParseResult(unitName, source, null, List.of());
        }
    }

    /** Parse result: the AST (nullable on failure) plus raw compiler problems. */
    public record ParseResult(String unitName, String source, CompilationUnit cu,
            List<org.eclipse.jdt.core.compiler.IProblem> problems) {

        /** Converts raw ECJ problems into LSP diagnostics. */
        public List<org.eclipse.lsp4j.Diagnostic> diagnostics() {
            return problems.stream().map(p -> DiagnosticMapper.of(source, p)).toList();
        }
    }
}
