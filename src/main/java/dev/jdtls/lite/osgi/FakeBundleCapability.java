package dev.jdtls.lite.osgi;

import java.util.Map;

import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;

/** Capability exposing a fake bundle under the {@code osgi.identity} namespace. */
public final class FakeBundleCapability implements BundleCapability {

    private final FakeBundle bundle;

    public FakeBundleCapability(FakeBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public String getNamespace() {
        return "osgi.identity";
    }

    @Override
    public Map<String, String> getDirectives() {
        return Map.of();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("osgi.identity", bundle.getSymbolicName(), "version", (Object) bundle.getVersion());
    }

    @Override
    public org.osgi.framework.wiring.BundleRevision getResource() {
        return bundle; // FakeBundle implements BundleRevision
    }



    @Override
    public BundleRevision getRevision() {
        return bundle;
    }
}
