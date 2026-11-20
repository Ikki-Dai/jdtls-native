package dev.jdtls.lite.osgi;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;

/**
 * A fake OSGi Bundle standing in for a real platform jar. Classes resolve through
 * the flat application classpath ({@code Class.forName}); resources remain on disk
 * inside the jar (legal file IO in a native image).
 */
public final class FakeBundle implements Bundle, org.osgi.framework.wiring.BundleRevision {

    private final long id;
    private final String symbolicName;
    private final String location;
    private final Version version;
    private final Map<String, String> headers;
    private final BundleContext context;

    public FakeBundle(long id, String symbolicName, String location, Version version,
            Map<String, String> headers, BundleContext context) {
        this.id = id;
        this.symbolicName = symbolicName;
        this.location = location;
        this.version = version;
        this.headers = headers;
        this.context = context;
    }

    @Override
    public String getSymbolicName() {
        return symbolicName;
    }

    @Override
    public String getLocation() {
        return location;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public int getState() {
        return Bundle.ACTIVE;
    }

    @Override
    public long getBundleId() {
        return id;
    }

    @Override
    public Dictionary<String, String> getHeaders() {
        return new Hashtable<>(headers);
    }

    @Override
    public Dictionary<String, String> getHeaders(String locale) {
        return getHeaders();
    }

    @Override
    public BundleContext getBundleContext() {
        return context;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    @Override
    public URL getEntry(String path) {
        return null; // per-jar resources are not reachable through this shim
    }

    @Override
    public Enumeration<URL> findEntries(String path, String filePattern, boolean recurse) {
        return null;
    }

    // ------------------------------------------------------------------ stubs

    @Override
    public int compareTo(Bundle other) {
        return 0;
    }

    @Override
    public void start(int options) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop(int options) {
    }

    @Override
    public void stop() {
    }

    @Override
    public void update() {
    }

    @Override
    public void update(InputStream input) {
    }

    @Override
    public void uninstall() {
    }

    @Override
    public URL getResource(String name) {
        return getClass().getClassLoader().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws java.io.IOException {
        return getClass().getClassLoader().getResources(name);
    }

    @Override
    public Enumeration<String> getEntryPaths(String path) {
        return null;
    }

    @Override
    public ServiceReference<?>[] getRegisteredServices() {
        return null;
    }

    @Override
    public ServiceReference<?>[] getServicesInUse() {
        return null;
    }

    @Override
    public long getLastModified() {
        return 0;
    }

    @Override
    public java.util.Map<java.security.cert.X509Certificate, java.util.List<java.security.cert.X509Certificate>> getSignerCertificates(int signersType) {
        return null;
    }

    @Override
    public File getDataFile(String filename) {
        return null;
    }

    @Override
    public boolean hasPermission(Object permission) {
        return true;
    }

    @Override
    public <A> A adapt(Class<A> type) {
        if (type == org.osgi.framework.wiring.FrameworkWiring.class && context instanceof FakeBundleContext fbc) {
            return type.cast(fbc.frameworkWiring());
        }
        return null;
    }

    // ---------------------------------------------------- BundleRevision / Resource

    @Override
    public List<org.osgi.resource.Capability> getCapabilities(String namespace) {
        return java.util.List.of();
    }

    @Override
    public List<org.osgi.resource.Requirement> getRequirements(String namespace) {
        return java.util.List.of();
    }

    @Override
    public List<org.osgi.framework.wiring.BundleCapability> getDeclaredCapabilities(String namespace) {
        return java.util.List.of();
    }

    @Override
    public List<org.osgi.framework.wiring.BundleRequirement> getDeclaredRequirements(String namespace) {
        return java.util.List.of();
    }

    @Override
    public int getTypes() {
        return 0;
    }

    @Override
    public org.osgi.framework.wiring.BundleWiring getWiring() {
        return null;
    }

    @Override
    public Bundle getBundle() {
        return this;
    }
}
