package dev.jdtls.lite.osgi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.eclipse.osgi.internal.framework.FilterImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Requirement;

/**
 * Capability lookup for {@code Platform.getBundle(...)}. Parses the
 * {@code osgi.identity} requirement filter and answers with matching fake
 * bundles (as {@link BundleCapability}s whose resource is the bundle itself).
 */
public final class FakeFrameworkWiring implements FrameworkWiring {

    private final FakeBundleContext context;

    public FakeFrameworkWiring(FakeBundleContext context) {
        this.context = context;
    }

    @Override
    public Collection<BundleCapability> findProviders(Requirement requirement) {
        String filter = requirement.getDirectives() == null ? null : requirement.getDirectives().get("filter");
        if (filter == null) {
            return List.of();
        }
        FilterImpl parsed;
        try {
            parsed = FilterImpl.newInstance(filter);
        } catch (org.osgi.framework.InvalidSyntaxException e) {
            return List.of();
        }
        List<BundleCapability> result = new ArrayList<>();
        for (FakeBundle bundle : context.bundleList()) {
            Map<String, Object> identity = new Hashtable<>();
            identity.put("osgi.identity", bundle.getSymbolicName());
            identity.put("version", bundle.getVersion());
            if (parsed.matches(asPlainMap(identity))) {
                result.add(new FakeBundleCapability(bundle));
            }
        }
        return result;
    }

    private static Map<String, Object> asPlainMap(Map<String, Object> src) {
        return new java.util.HashMap<>(src);
    }

    // ------------------------------------------------------------------ stubs

    @Override
    public void refreshBundles(Collection<Bundle> bundles, FrameworkListener... listeners) {
    }

    @Override
    public boolean resolveBundles(Collection<Bundle> bundles) {
        return true;
    }

    @Override
    public Collection<Bundle> getRemovalPendingBundles() {
        return Collections.emptyList();
    }

    @Override
    public Collection<Bundle> getDependencyClosure(Collection<Bundle> bundles) {
        return bundles == null ? Collections.emptyList() : bundles;
    }

    @Override
    public Bundle getBundle() {
        return context.getBundle();
    }
}
