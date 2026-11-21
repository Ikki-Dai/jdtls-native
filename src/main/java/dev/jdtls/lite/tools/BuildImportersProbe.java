package dev.jdtls.lite.tools;

import java.nio.file.Path;

import dev.jdtls.lite.build.BuildImporters;

/** Prints what the build-tool importers produce for a project root. */
public final class BuildImportersProbe {

    public static void main(String[] args) throws Exception {
        var result = BuildImporters.importClasspath(Path.of(args[0]));
        if (result == null) {
            System.out.println("NO IMPORT RESULT (unsupported/failed)");
            System.exit(1);
        }
        System.out.println("kind=" + result.kind());
        System.out.println("roots=" + result.sourceRoots());
        System.out.println("jars=" + result.jars().size());
        result.jars().stream().limit(5).forEach(j -> System.out.println("  " + j));
    }

    private BuildImportersProbe() {
    }
}
