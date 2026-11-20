package dev.jdtls.lite;

import org.eclipse.lsp4j.launch.LSPLauncher;

import dev.jdtls.lite.server.JdtlsNativeServer;

/**
 * Entry point. The whole LSP channel runs over stdin/stdout; all logs go to stderr.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        Log.info("jdtls-native 0.1.0 starting, pid=" + ProcessHandle.current().pid());

        JdtlsNativeServer server = new JdtlsNativeServer();
        var launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connectClient(launcher.getRemoteProxy());
        launcher.startListening().get();

        Log.info("jdtls-native stopped in " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    private Main() {
    }
}
