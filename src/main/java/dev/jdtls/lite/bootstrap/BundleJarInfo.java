package dev.jdtls.lite.bootstrap;

import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;

import org.osgi.framework.Version;

/** A discovered platform bundle jar with its OSGi manifest metadata. */
public final class BundleJarInfo {

    final Path jarPath;
    final String symbolicName;
    final Version version;
    final String activator;
    final Map<String, String> headers;
    final byte[] pluginXml;   // null when absent
    final byte[] fragmentXml; // null when absent

    BundleJarInfo(Path jarPath, String symbolicName, Version version, String activator,
            Map<String, String> headers, byte[] pluginXml, byte[] fragmentXml) {
        this.jarPath = jarPath;
        this.symbolicName = symbolicName;
        this.version = version;
        this.activator = activator;
        this.headers = headers;
        this.pluginXml = pluginXml;
        this.fragmentXml = fragmentXml;
    }

    /** Convenience accessor mirroring Bundle.getHeaders(). */
    public Attributes asAttributes() {
        Attributes a = new Attributes();
        headers.forEach(a::putValue);
        return a;
    }
}
