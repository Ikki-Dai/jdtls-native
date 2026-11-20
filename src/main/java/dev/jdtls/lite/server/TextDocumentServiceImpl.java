package dev.jdtls.lite.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import dev.jdtls.lite.Log;
import dev.jdtls.lite.engine.JavaEngine;
import dev.jdtls.lite.engine.JavaModelEngine;
import dev.jdtls.lite.workspace.WorkspaceManager;

/**
 * Document lifecycle + diagnostics + completion. Two engines:
 * <ul>
 *   <li>{@link JavaModelEngine} — full JavaModel engine (index, completion) when the
 *       platform bootstrap succeeded and the file belongs to the project</li>
 *   <li>{@link JavaEngine} — workspace-free ASTParser fallback ("syntax mode")</li>
 * </ul>
 */
public final class TextDocumentServiceImpl implements TextDocumentService {

    private final WorkspaceManager workspace;
    private final JavaEngine engine = new JavaEngine();
    private volatile JavaModelEngine modelEngine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "diagnostics");
        t.setDaemon(true);
        return t;
    });
    private volatile LanguageClient client;

    public TextDocumentServiceImpl(WorkspaceManager workspace) {
        this.workspace = workspace;
    }

    public void setClient(LanguageClient client) {
        this.client = client;
    }

    public void setModelEngine(JavaModelEngine modelEngine) {
        this.modelEngine = modelEngine;
        // replay documents that were opened before the engine finished booting
        if (modelEngine != null && modelEngine.isReady()) {
            for (var entry : workspace.allDocuments().entrySet()) {
                modelEngine.putDocument(entry.getKey(), entry.getValue().text());
                scheduleDiagnostics(entry.getKey());
            }
        }
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    /** Diagnostics must wait for the platform boot: an early JavaModelManager classload
     *  would construct the singleton while Platform.isRunning() is still false, which
     *  skips IndexManager creation and breaks all searches. */
    private final java.util.concurrent.CountDownLatch bootLatch = new java.util.concurrent.CountDownLatch(1);

    public void bootDone() {
        bootLatch.countDown();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var td = params.getTextDocument();
        Log.info("didOpen " + td.getUri() + " v" + td.getVersion());
        workspace.putDocument(td.getUri(), td.getVersion(), td.getText());
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            me.putDocument(td.getUri(), td.getText());
        }
        scheduleDiagnostics(td.getUri());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var td = params.getTextDocument();
        String text = null;
        for (var change : params.getContentChanges()) {
            if (change.getRange() == null) {
                text = change.getText(); // full sync
            }
        }
        if (text == null) {
            Log.warn("didChange with incremental change ignored (full sync expected)");
            return;
        }
        Log.info("didChange " + td.getUri() + " v" + td.getVersion());
        workspace.putDocument(td.getUri(), td.getVersion(), text);
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            me.putDocument(td.getUri(), text);
        }
        scheduleDiagnostics(td.getUri());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        Log.info("didClose " + uri);
        workspace.removeDocument(uri);
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            me.discardDocument(uri);
        }
        publishDiagnostics(uri, List.of()); // clear
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        if (params.getText() != null && !params.getText().isEmpty()) {
            workspace.putDocument(uri, 0, params.getText());
            JavaModelEngine me = modelEngine;
            if (me != null && me.isReady()) {
                me.putDocument(uri, params.getText());
            }
        }
        scheduleDiagnostics(uri);
    }

    private void scheduleDiagnostics(String uri) {
        WorkspaceManager.Doc doc = workspace.document(uri);
        if (doc == null) {
            return;
        }
        String text = doc.text();
        executor.submit(() -> {
            try {
                publishDiagnosticsForText(uri, text);
            } catch (RuntimeException e) {
                Log.error("diagnostics failed for " + uri, e);
            }
        });
    }

    private void publishDiagnosticsForText(String uri, String text) {
        try {
            bootLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        JavaModelEngine me = modelEngine;
        List<org.eclipse.lsp4j.Diagnostic> diagnostics;
        if (me != null && me.isReady() && me.handles(uri)) {
            long start = System.nanoTime();
            diagnostics = me.diagnostics(uri);
            long ms = (System.nanoTime() - start) / 1_000_000;
            Log.info("diagnostics(model) " + uri + ": " + diagnostics.size() + " problem(s) in " + ms + " ms");
        } else {
            String unitName = workspace.unitNameFor(uri);
            if (unitName == null) {
                return;
            }
            long start = System.nanoTime();
            var result = engine.parse(unitName, text, workspace.classpath(), workspace.sourcepath());
            diagnostics = result.diagnostics();
            long ms = (System.nanoTime() - start) / 1_000_000;
            Log.info("diagnostics(syntax) " + unitName + ": " + diagnostics.size() + " problem(s) in " + ms + " ms");
        }
        publishDiagnostics(uri, diagnostics);
    }

    private void publishDiagnostics(String uri, List<org.eclipse.lsp4j.Diagnostic> diagnostics) {
        LanguageClient c = client;
        if (c == null) {
            return;
        }
        c.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }

    // ---------------------------------------------------------------- features

    @Override
    public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.CompletionItem>, org.eclipse.lsp4j.CompletionList>> completion(
            org.eclipse.lsp4j.CompletionParams params) {
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            String uri = params.getTextDocument().getUri();
            org.eclipse.lsp4j.Position pos = params.getPosition();
            List<org.eclipse.lsp4j.CompletionItem> items =
                    me.complete(uri, "", pos.getLine(), pos.getCharacter());
            return CompletableFuture.completedFuture(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(items));
        }
        return CompletableFuture.completedFuture(null);
    }

    private <T> CompletableFuture<T> modelCall(java.util.function.Function<JavaModelEngine, T> fn) {
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            T result = fn.apply(me);
            return CompletableFuture.completedFuture(result);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.Hover> hover(org.eclipse.lsp4j.HoverParams params) {
        return modelCall(me -> me.hover(params.getTextDocument().getUri(),
                params.getPosition().getLine(), params.getPosition().getCharacter()));
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>>> definition(
            org.eclipse.lsp4j.DefinitionParams params) {
        return modelCall(me -> {
            List<org.eclipse.lsp4j.Location> locs = me.definition(params.getTextDocument().getUri(),
                    params.getPosition().getLine(), params.getPosition().getCharacter());
            return locs.isEmpty() ? null
                    : org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft((List<? extends org.eclipse.lsp4j.Location>) locs);
        });
    }

    @Override
    public CompletableFuture<List<? extends org.eclipse.lsp4j.Location>> references(
            org.eclipse.lsp4j.ReferenceParams params) {
        return modelCall(me -> me.references(params.getTextDocument().getUri(),
                params.getPosition().getLine(), params.getPosition().getCharacter()));
    }

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>> documentSymbol(
            org.eclipse.lsp4j.DocumentSymbolParams params) {
        return modelCall(me -> {
            List<org.eclipse.lsp4j.DocumentSymbol> symbols = me.documentSymbols(params.getTextDocument().getUri());
            if (symbols.isEmpty()) {
                return null;
            }
            List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>> out =
                    new ArrayList<>();
            for (org.eclipse.lsp4j.DocumentSymbol s : symbols) {
                out.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(s));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<List<? extends org.eclipse.lsp4j.TextEdit>> formatting(
            org.eclipse.lsp4j.DocumentFormattingParams params) {
        return modelCall(me -> me.format(params.getTextDocument().getUri()));
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> rename(org.eclipse.lsp4j.RenameParams params) {
        return modelCall(me -> me.rename(params.getTextDocument().getUri(),
                params.getPosition().getLine(), params.getPosition().getCharacter(), params.getNewName()));
    }
}
