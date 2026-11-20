package dev.jdtls.lite.osgi;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceObjects;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * A shared BundleContext standing in for every platform bundle. All services
 * funnel into one {@link ServiceRegistry}; one shared context is enough because
 * the platform bootstrap only registers/looks services up, never relies on
 * per-bundle isolation.
 */
public final class FakeBundleContext implements BundleContext {

    private final ServiceRegistry registry;
    private FakeBundle self;
    private final java.util.Map<String, FakeBundle> bundlesByLocation = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, FakeBundle> bundlesById = new java.util.concurrent.ConcurrentHashMap<>();

    public FakeBundleContext(ServiceRegistry registry, FakeBundle self) {
        this.registry = registry;
        this.self = self;
    }

    /** Late binding of the bundle this context represents. */
    public void setSelf(FakeBundle bundle) {
        this.self = bundle;
    }

    public void addBundle(FakeBundle bundle) {
        bundlesByLocation.put(bundle.getLocation(), bundle);
        bundlesById.put(bundle.getBundleId(), bundle);
        bundlesByLocation.putIfAbsent("System Bundle", bundle);
        bundlesByLocation.putIfAbsent(bundle.getSymbolicName(), bundle);
        bundleList.add(bundle);
    }

    private final List<FakeBundle> bundleList = new ArrayList<>();
    private final FakeFrameworkWiring frameworkWiring = new FakeFrameworkWiring(this);

    public FakeFrameworkWiring frameworkWiring() {
        return frameworkWiring;
    }

    public List<FakeBundle> bundleList() {
        return bundleList;
    }

    // ------------------------------------------------------------------ services

    @Override
    public ServiceRegistration<?> registerService(String[] classNames, Object service, Dictionary<String, ?> properties) {
        return registry.registerService(classNames, service, copyOf(properties));
    }

    @Override
    public ServiceRegistration<?> registerService(String className, Object service, Dictionary<String, ?> properties) {
        return registerService(new String[] { className }, service, properties);
    }

    @Override
    public <S> ServiceRegistration<S> registerService(Class<S> clazz, S service, Dictionary<String, ?> properties) {
        return (ServiceRegistration<S>) registerService(new String[] { clazz.getName() }, service, properties);
    }

    @Override
    public <S> ServiceRegistration<S> registerService(Class<S> clazz, org.osgi.framework.ServiceFactory<S> factory, Dictionary<String, ?> properties) {
        S instance = factory.getService(self, null);
        ServiceRegistration<S> reg = (ServiceRegistration<S>) registerService(clazz, instance, properties);
        // the fake registry keeps the instance directly; release is a no-op
        return reg;
    }

    private static Dictionary<String, Object> copyOf(Dictionary<String, ?> src) {
        Dictionary<String, Object> copy = new Hashtable<>();
        if (src != null) {
            for (Enumeration<String> e = src.keys(); e.hasMoreElements(); ) {
                String key = e.nextElement();
                copy.put(key, src.get(key));
            }
        }
        return copy;
    }

    @Override
    public ServiceReference<?> getServiceReference(String className) {
        return registry.getServiceReference(className);
    }

    @Override
    public <S> ServiceReference<S> getServiceReference(Class<S> clazz) {
        return (ServiceReference<S>) registry.getServiceReference(clazz.getName());
    }

    @Override
    public ServiceReference<?>[] getServiceReferences(String className, String filter) throws InvalidSyntaxException {
        List<FakeServiceReference<?>> refs =
                registry.getServiceReferences(className, filter == null ? null : FakeFilter.parse(filter));
        return refs.toArray(ServiceReference[]::new);
    }

    @Override
    public ServiceReference<?>[] getAllServiceReferences(String className, String filter) throws InvalidSyntaxException {
        return getServiceReferences(className, filter);
    }

    @Override
    public <S> java.util.Collection<ServiceReference<S>> getServiceReferences(Class<S> clazz, String filter) throws InvalidSyntaxException {
        List<FakeServiceReference<?>> refs =
                registry.getServiceReferences(clazz.getName(), filter == null ? null : FakeFilter.parse(filter));
        List<ServiceReference<S>> result = new ArrayList<>();
        for (FakeServiceReference<?> r : refs) {
            result.add((ServiceReference<S>) r);
        }
        return result;
    }

    @Override
    public <S> S getService(ServiceReference<S> reference) {
        return registry.getService((FakeServiceReference<S>) reference);
    }

    @Override
    public boolean ungetService(ServiceReference<?> reference) {
        return true;
    }

    @Override
    public <S> ServiceObjects<S> getServiceObjects(ServiceReference<S> reference) {
        throw new UnsupportedOperationException("getServiceObjects");
    }

    // ------------------------------------------------------------------ listeners

    @Override
    public void addServiceListener(ServiceListener listener, String filter) {
        registry.addServiceListener(listener, filter);
    }

    @Override
    public void addServiceListener(ServiceListener listener) {
        registry.addServiceListener(listener, null);
    }

    @Override
    public void removeServiceListener(ServiceListener listener) {
        registry.removeServiceListener(listener);
    }

    @Override
    public void addBundleListener(org.osgi.framework.BundleListener listener) {
    }

    @Override
    public void removeBundleListener(org.osgi.framework.BundleListener listener) {
    }

    @Override
    public void addFrameworkListener(FrameworkListener listener) {
    }

    @Override
    public void removeFrameworkListener(FrameworkListener listener) {
    }

    // ------------------------------------------------------------------ bundles

    @Override
    public Bundle getBundle() {
        return self;
    }

    @Override
    public Bundle installBundle(String location) throws BundleException {
        throw new UnsupportedOperationException("installBundle (runtime bundle install is unsupported)");
    }

    @Override
    public Bundle installBundle(String location, InputStream input) throws BundleException {
        throw new UnsupportedOperationException("installBundle (runtime bundle install is unsupported)");
    }

    @Override
    public Bundle getBundle(long id) {
        return bundlesById.get(id);
    }

    /** Equinox-specific lookup by location or symbolic name (e.g. "System Bundle"). */
    @Override
    public Bundle getBundle(String location) {
        return bundlesByLocation.get(location);
    }

    @Override
    public Bundle[] getBundles() {
        return bundlesByLocation.values().toArray(Bundle[]::new);
    }

    // ------------------------------------------------------------------ misc

    @Override
    public Filter createFilter(String expression) throws InvalidSyntaxException {
        return FakeFilter.parse(expression);
    }

    private volatile java.nio.file.Path stateBase; // workspace dir, set by the boot

    public void setStateBase(java.nio.file.Path base) {
        this.stateBase = base;
    }

    @Override
    public File getDataFile(String filename) {
        if (stateBase == null) {
            return null;
        }
        String sub = self == null ? "system" : self.getSymbolicName();
        File dir = stateBase.resolve(".metadata").resolve(".plugins").resolve(sub).toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, filename);
    }

    @Override
    public String getProperty(String key) {
        // Equinox framework properties (Platform.getOS() etc. read these)
        String value = switch (key) {
            case "osgi.os" -> System.getProperty("os.name", "win32").toLowerCase().startsWith("win") ? "win32" : "linux";
            case "osgi.ws" -> "win32";
            case "osgi.arch" -> System.getProperty("os.arch", "x86_64").contains("64") ? "x86_64" : "x86";
            case "osgi.nl" -> "en_US";
            case "osgi.framework" -> "file:///dev/null/org.eclipse.osgi.jar";
            default -> null;
        };
        if (value != null) {
            return value;
        }
        return System.getProperty(key);
    }
}
