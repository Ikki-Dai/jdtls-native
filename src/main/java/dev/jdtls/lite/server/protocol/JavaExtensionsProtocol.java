package dev.jdtls.lite.server.protocol;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment;

/**
 * jdt.ls-compatible custom LSP methods used by Java IDE/debug clients:
 * <ul>
 *   <li>{@code java/buildWorkspace} — full workspace build before launch/debug</li>
 *   <li>{@code java/resolveClasspath} — resolve launch classpath for a main class
 *       (used by java debug adapters: "building and resolving classpath...")</li>
 * </ul>
 */
@JsonSegment("java")
public interface JavaExtensionsProtocol {

    @JsonRequest
    CompletableFuture<Boolean> buildWorkspace(Object params);

    @JsonRequest
    CompletableFuture<Object> resolveClasspath(Object params);
}
