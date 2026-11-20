package dev.jdtls.lite.engine;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;

/**
 * Compiler options built from literal strings. IMPORTANT: must NOT touch
 * {@code JavaCore.*} constants — referencing the JavaCore class triggers its
 * static initialization which class-loads {@code JavaModelManager}; if that
 * happens before the platform boot, the singleton is constructed while
 * {@code Platform.isRunning()} is false and the IndexManager is never created
 * (breaking every index search). {@link CompilerOptions} fills in defaults for
 * any key we omit.
 */
public final class CompilerDefaults {

    private static final String COMPLIANCE = "21";

    public static Map<String, String> options() {
        Map<String, String> opts = new HashMap<>();
        opts.put("org.eclipse.jdt.core.compiler.compliance", COMPLIANCE);
        opts.put("org.eclipse.jdt.core.compiler.source", COMPLIANCE);
        opts.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", COMPLIANCE);
        opts.put("org.eclipse.jdt.core.compiler.docCommentSupport", "enabled");
        opts.put("org.eclipse.jdt.core.compiler.taskTags", "TODO,FIXME,XXX");
        opts.put("org.eclipse.jdt.core.compiler.taskPriorities", "NORMAL,HIGH,NORMAL");
        opts.put(CompilerOptions.OPTION_ReportMissingJavadocComments, CompilerOptions.IGNORE);
        return opts;
    }

    private CompilerDefaults() {
    }
}
