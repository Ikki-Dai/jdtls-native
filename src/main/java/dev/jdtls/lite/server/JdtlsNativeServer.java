package dev.jdtls.lite.server;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import dev.jdtls.lite.Log;
import dev.jdtls.lite.engine.JavaModelEngine;
import dev.jdtls.lite.server.protocol.JavaExtensionsProtocol;
import dev.jdtls.lite.workspace.WorkspaceManager;

public final class JdtlsNativeServer implements LanguageServer, dev.jdtls.lite.server.protocol.JavaExtensionsProtocol {

    private final WorkspaceManager workspace = new WorkspaceManager();
    private final TextDocumentServiceImpl textDocumentService = new TextDocumentServiceImpl(workspace);
    private final WorkspaceServiceImpl workspaceService = new WorkspaceServiceImpl();
    private volatile LanguageClient client;
    private volatile boolean shutdownRequested;
    private final java.util.concurrent.CountDownLatch engineLatch = new java.util.concurrent.CountDownLatch(1);
    private volatile JavaModelEngine modelEngineRef;

    private JavaModelEngine awaitEngine(long timeoutSeconds) {
        try {
            if (!engineLatch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return modelEngineRef;
    }

    public void publishBuildDiagnostics(String uri, java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics) {
        LanguageClient cl = client;
        if (cl != null && uri != null) {
            cl.publishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsParams(uri, diagnostics));
        }
    }

    public void connectClient(LanguageClient client) {
        this.client = client;
        this.textDocumentService.setClient(client);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        if (params.getClientInfo() != null) {
            Log.info("client: " + params.getClientInfo().getName() + " "
                    + params.getClientInfo().getVersion());
        }
        workspace.initialize(params);
        startModelEngine();

        ServerCapabilities caps = new ServerCapabilities();
        caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        CompletionOptions completion = new CompletionOptions();
        completion.setTriggerCharacters(List.of(".", "@", "#"));
        caps.setCompletionProvider(completion);
        caps.setHoverProvider(true);
        caps.setDefinitionProvider(true);
        caps.setReferencesProvider(true);
        caps.setDocumentSymbolProvider(true);
        caps.setDocumentFormattingProvider(true);
        caps.setDocumentRangeFormattingProvider(true);
        RenameOptions rename = new RenameOptions();
        rename.setPrepareProvider(false);
        caps.setRenameProvider(rename);

        InitializeResult result = new InitializeResult();
        result.setCapabilities(caps);
        try {
            result.setServerInfo(new ServerInfo("jdtls-native", "0.1.0"));
        } catch (RuntimeException e) {
            Log.warn("ServerInfo unsupported: " + e.getMessage());
        }
        Log.info("initialized, capabilities advertised");
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void initialized(InitializedParams params) {
        Log.info("client initialized");
    }

    /**
     * Boots the JavaModel engine (OSGi emulation + platform bootstrap) in the
     * background; the syntax engine keeps serving until it is ready.
     */
    private void startModelEngine() {
        java.util.List<java.nio.file.Path> roots = workspace.roots();
        if (roots.isEmpty()) {
            Log.warn("no workspace folders; staying in syntax-only mode");
            textDocumentService.bootDone();
            engineLatch.countDown();
            return;
        }
        Thread boot = new Thread(() -> {
            try {
                String rootHash = Integer.toHexString(
                        roots.stream().map(r -> r.toString().toLowerCase()).sorted()
                                .collect(java.util.stream.Collectors.joining("|")).hashCode());
                java.nio.file.Path configDir = java.nio.file.Path
                        .of(System.getProperty("user.home"), ".jdtls-native", rootHash, "config");
                JavaModelEngine modelEngine = new JavaModelEngine();
                long start = System.nanoTime();
                String configuredJdk = System.getProperty("jdtls.native.jdk");
                if (configuredJdk == null || configuredJdk.isBlank()) {
                    configuredJdk = System.getenv("JAVA_HOME");
                }
                modelEngine.start(configDir, roots, configuredJdk);
                Log.info("model engine started in " + (System.nanoTime() - start) / 1_000_000 + " ms");
                textDocumentService.setModelEngine(modelEngine);
                workspaceService.setModelEngine(modelEngine);
                modelEngineRef = modelEngine;
            } catch (Throwable t) {
                Log.error("model engine bootstrap failed; staying in syntax mode", t);
            } finally {
                textDocumentService.bootDone();
                engineLatch.countDown();
            }
        }, "model-engine-boot");
        boot.setDaemon(true);
        boot.start();
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        Log.info("exit requested, shutting down");
        var executor = textDocumentService.getExecutor();
        if (executor != null) {
            executor.shutdown();
        }
        System.exit(0);
    }

    public boolean isShutdownRequested() {
        return shutdownRequested;
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    // --------------------------------------------- jdt.ls-compatible java/* methods

    @Override
    public CompletableFuture<Boolean> buildWorkspace(Object params) {
        JavaModelEngine engine = awaitEngine(90);
        if (engine == null) {
            Log.warn("java/buildWorkspace: model engine not ready");
            return CompletableFuture.completedFuture(false);
        }
        boolean ok = engine.buildAndPublish(this::publishBuildDiagnostics);
        Log.info("java/buildWorkspace done: " + ok);
        return CompletableFuture.completedFuture(ok);
    }

    @Override
    public CompletableFuture<Object> resolveClasspath(Object params) {
        JavaModelEngine engine = awaitEngine(90);
        if (engine == null) {
            return CompletableFuture.completedFuture(null);
        }
        String mainClass = stringParam(params, "mainClass");
        String projectName = stringParam(params, "projectName");
        Log.info("java/resolveClasspath: mainClass=" + mainClass + " project=" + projectName);
        try {
            return CompletableFuture.completedFuture(engine.resolveClasspath(mainClass, projectName));
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
