package dev.jdtls.lite.server;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;

import dev.jdtls.lite.Log;
import dev.jdtls.lite.engine.JavaModelEngine;

/** Workspace-level requests: workspace/symbol is served by the JavaModel index. */
public final class WorkspaceServiceImpl implements WorkspaceService {

    private volatile JavaModelEngine modelEngine;

    public void setModelEngine(JavaModelEngine modelEngine) {
        this.modelEngine = modelEngine;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        Log.info("workspace/didChangeConfiguration (ignored)");
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        Log.info("workspace/didChangeWatchedFiles (ignored)");
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        Log.warn("workspace/executeCommand not supported: " + params.getCommand());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends org.eclipse.lsp4j.WorkspaceSymbol>>> symbol(
            org.eclipse.lsp4j.WorkspaceSymbolParams params) {
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            List<org.eclipse.lsp4j.SymbolInformation> infos = me.workspaceSymbols(params.getQuery());
            if (!infos.isEmpty()) {
                return CompletableFuture.completedFuture(Either.forLeft(
                        (List<? extends org.eclipse.lsp4j.SymbolInformation>) infos));
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
