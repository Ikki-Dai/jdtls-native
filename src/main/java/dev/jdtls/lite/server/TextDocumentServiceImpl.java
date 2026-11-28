package dev.jdtls.lite.server;

import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
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
import dev.jdtls.lite.engine.AstHover;
import dev.jdtls.lite.engine.AstSymbols;
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

    /** Logs method + duration + result summary when the future completes. */
    private static <T> CompletableFuture<T> timed(String method, java.util.function.Function<T, String> summary,
            CompletableFuture<T> f) {
        long start = System.currentTimeMillis();
        return f.whenComplete((r, e) -> {
            if (e != null) {
                Log.lspFail(method, start, e);
            } else {
                Log.lsp(method, start, summary == null ? null : summary.apply(r));
            }
        });
    }

    /** `file:///a/b/c/D.java` → `D.java` for compact log lines. */
    private static String shortUri(String uri) {
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        long start = System.currentTimeMillis();
        var td = params.getTextDocument();
        workspace.putDocument(td.getUri(), td.getVersion(), td.getText());
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            me.putDocument(td.getUri(), td.getText());
        }
        scheduleDiagnostics(td.getUri());
        Log.lsp("textDocument/didOpen", start, shortUri(td.getUri()) + " v" + td.getVersion()
                + " " + td.getText().length() + " chars");
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        long start = System.currentTimeMillis();
        var td = params.getTextDocument();
        String text = null;
        WorkspaceManager.Doc current = workspace.document(td.getUri());
        for (var change : params.getContentChanges()) {
            if (change.getRange() == null || current == null) {
                text = change.getText(); // full sync
            } else {
                text = applyIncremental(text == null ? current.text() : text, change);
            }
        }
        if (text == null) {
            Log.warn("didChange with no applicable content");
            return;
        }
        workspace.putDocument(td.getUri(), td.getVersion(), text);
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            me.putDocument(td.getUri(), text);
        }
        scheduleDiagnostics(td.getUri());
        Log.lsp("textDocument/didChange", start, shortUri(td.getUri()) + " v" + td.getVersion());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        long start = System.currentTimeMillis();
        String uri = params.getTextDocument().getUri();
        workspace.removeDocument(uri);
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady()) {
            me.discardDocument(uri);
        }
        publishDiagnostics(uri, List.of()); // clear
        Log.lsp("textDocument/didClose", start, shortUri(uri));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        long start = System.currentTimeMillis();
        String uri = params.getTextDocument().getUri();
        if (params.getText() != null && !params.getText().isEmpty()) {
            workspace.putDocument(uri, 0, params.getText());
            JavaModelEngine me = modelEngine;
            if (me != null && me.isReady()) {
                me.putDocument(uri, params.getText());
            }
        }
        scheduleDiagnostics(uri);
        Log.lsp("textDocument/didSave", start, shortUri(uri));
    }

    /** Applies one ranged content change (LSP incremental sync) to the given text. */
    private static String applyIncremental(String oldText, org.eclipse.lsp4j.TextDocumentContentChangeEvent change) {
        var r = change.getRange();
        int start = dev.jdtls.lite.engine.PositionUtil.offsetAt(oldText, r.getStart().getLine(),
                r.getStart().getCharacter());
        int end = dev.jdtls.lite.engine.PositionUtil.offsetAt(oldText, r.getEnd().getLine(),
                r.getEnd().getCharacter());
        if (end < start) {
            end = start;
        }
        return oldText.substring(0, start) + change.getText() + oldText.substring(Math.min(end, oldText.length()));
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
            return timed("textDocument/completion",
                    r -> r == null ? "result=null" : r.getLeft().size() + " item(s)",
                    CompletableFuture.completedFuture(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(items)));
        }
        return timed("textDocument/completion", r -> "result=null (engine not ready)",
                CompletableFuture.completedFuture(null));
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
        long start = System.currentTimeMillis();
        String uri = params.getTextDocument().getUri();
        JavaModelEngine me = modelEngine;
        CompletableFuture<org.eclipse.lsp4j.Hover> f;
        if (me != null && me.isReady() && me.handles(uri)) {
            f = CompletableFuture.completedFuture(me.hover(uri,
                    params.getPosition().getLine(), params.getPosition().getCharacter()));
        } else {
            f = hoverFallback(uri, params.getPosition().getLine(), params.getPosition().getCharacter());
        }
        return timed("textDocument/hover", r -> r == null ? "result=null" : "hover", f);
    }

    /** decompiled jar/jrt files and other loose sources: plain AST hover */
    private CompletableFuture<org.eclipse.lsp4j.Hover> hoverFallback(String uri, int line, int character) {
        Log.info("hover fallback: uri=" + uri);
        String hoverText = null;
        WorkspaceManager.Doc doc = workspace.document(uri);
        if (doc != null) {
            hoverText = doc.text();
        } else {
            try {
                Path fp = Path.of(java.net.URI.create(uri.replace("\\", "/")));
                Log.info("hover fallback: disk path=" + fp + " exists=" + Files.isRegularFile(fp));
                if (Files.isRegularFile(fp)) {
                    hoverText = Files.readString(fp, StandardCharsets.UTF_8);
                    Log.info("hover fallback: disk read " + hoverText.length() + " chars");
                }
            } catch (Exception e) {
                Log.info("hover fallback: disk read failed: " + e);
            }
        }
        Log.info("hover fallback: hoverText=" + (hoverText == null ? "null" : hoverText.length() + " chars"));
        if (hoverText != null) {
            return CompletableFuture.completedFuture(
                    AstHover.hoverAt(hoverText, line, character));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>>> definition(
            org.eclipse.lsp4j.DefinitionParams params) {
        return timed("textDocument/definition",
                r -> r == null ? "result=null" : r.getLeft().size() + " location(s)",
                modelCall(me -> {
                    List<org.eclipse.lsp4j.Location> locs = me.definition(params.getTextDocument().getUri(),
                            params.getPosition().getLine(), params.getPosition().getCharacter());
                    return locs.isEmpty() ? null
                            : org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft((List<? extends org.eclipse.lsp4j.Location>) locs);
                }));
    }

    @Override
    public CompletableFuture<List<? extends org.eclipse.lsp4j.Location>> references(
            org.eclipse.lsp4j.ReferenceParams params) {
        return timed("textDocument/references",
                r -> r == null ? "result=null" : r.size() + " reference(s)",
                modelCall(me -> me.references(params.getTextDocument().getUri(),
                        params.getPosition().getLine(), params.getPosition().getCharacter())));
    }

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>> documentSymbol(
            org.eclipse.lsp4j.DocumentSymbolParams params) {
        String uri = params.getTextDocument().getUri();
        CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>> f =
                computeDocumentSymbols(uri);
        return timed("textDocument/documentSymbol",
                r -> r == null ? "result=null" : r.size() + " top-level symbol(s)", f);
    }

    private CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>> computeDocumentSymbols(
            String uri) {
        JavaModelEngine me = modelEngine;
        if (me != null && me.isReady() && me.handles(uri)) {
            List<org.eclipse.lsp4j.DocumentSymbol> symbols = me.documentSymbols(uri);
            if (!symbols.isEmpty()) {
                return CompletableFuture.completedFuture(toEitherRightList(symbols));
            }
        }
        // decompiled jar/jrt files opened by the client (outside the project):
        // parse the buffered text with the plain AST — no bindings, no model
        WorkspaceManager.Doc doc = workspace.document(uri);
        if (doc != null) {
            List<org.eclipse.lsp4j.DocumentSymbol> symbols = AstSymbols.build(doc.text());
            if (!symbols.isEmpty()) {
                return CompletableFuture.completedFuture(toEitherRightList(symbols));
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private static List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>> toEitherRightList(
            List<org.eclipse.lsp4j.DocumentSymbol> symbols) {
        List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>> out =
                new ArrayList<>();
        for (org.eclipse.lsp4j.DocumentSymbol s : symbols) {
            out.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(s));
        }
        return out;
    }

    @Override
    public CompletableFuture<List<? extends org.eclipse.lsp4j.TextEdit>> formatting(
            org.eclipse.lsp4j.DocumentFormattingParams params) {
        return timed("textDocument/formatting",
                r -> r == null ? "result=null" : r.size() + " edit(s)",
                modelCall(me -> me.format(params.getTextDocument().getUri())));
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> rename(org.eclipse.lsp4j.RenameParams params) {
        return timed("textDocument/rename",
                r -> r == null ? "result=null" : "workspace edit",
                modelCall(me -> me.rename(params.getTextDocument().getUri(),
                        params.getPosition().getLine(), params.getPosition().getCharacter(), params.getNewName())));
    }
}
