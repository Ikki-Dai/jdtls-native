package dev.jdtls.lite.server.protocol;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;

import dev.jdtls.lite.Log;
import dev.jdtls.lite.engine.JavaModelEngine;

/** Implements the jdt.ls-compatible java/* custom LSP methods. */
public final class JavaExtensionsHandler implements JavaExtensionsProtocol {

    private final EngineAccess engine;

    /** Indirection so the handler can wait for the async boot. */
    public interface EngineAccess {
        JavaModelEngine await(long timeoutSeconds);

        void publishBuildDiagnostics(String uri, java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics);
    }

    private static final int BOOT_WAIT_SECONDS = 90;

    public JavaExtensionsHandler(EngineAccess access) {
        this.engine = access;
    }

    @Override
    public CompletableFuture<Boolean> buildWorkspace(Object params) {
        JavaModelEngine modelEngine = engine.await(BOOT_WAIT_SECONDS);
        if (modelEngine == null) {
            Log.warn("java/buildWorkspace: model engine not ready");
            return CompletableFuture.completedFuture(false);
        }
        boolean ok = modelEngine.buildAndPublish(engine::publishBuildDiagnostics);
        Log.info("java/buildWorkspace done: " + ok);
        return CompletableFuture.completedFuture(ok);
    }

    @Override
    public CompletableFuture<Object> resolveClasspath(Object params) {
        JavaModelEngine modelEngine = engine.await(BOOT_WAIT_SECONDS);
        if (modelEngine == null) {
            return CompletableFuture.completedFuture(null);
        }
        String mainClass = stringParam(params, "mainClass");
        String projectName = stringParam(params, "projectName");
        Log.info("java/resolveClasspath: mainClass=" + mainClass + " project=" + projectName);
        try {
            return CompletableFuture.completedFuture(
                    modelEngine.resolveClasspath(mainClass, projectName));
        } catch (Exception e) {
            Log.warn("resolveClasspath failed: " + e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static String stringParam(Object params, String key) {
        if (params instanceof java.util.Map<?, ?> map) {
            Object v = map.get(key);
            return v == null ? null : v.toString();
        }
        return null;
    }
}
