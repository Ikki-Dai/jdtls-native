package dev.jdtls.lite.osgi;

import java.util.Dictionary;

import org.osgi.framework.ServiceRegistration;

/** ServiceRegistration backed by {@link ServiceRegistry}. */
public final class FakeServiceRegistration<S> implements ServiceRegistration<S> {

    private final ServiceRegistry.Reg owner;
    private boolean unregistered;

    FakeServiceRegistration(ServiceRegistry.Reg owner) {
        this.owner = owner;
    }

    ServiceRegistry.Reg owner() {
        return owner;
    }

    @Override
    public void setProperties(Dictionary<String, ?> properties) {
        // not needed by the boot sequence
    }

    @Override
    public void unregister() {
        if (unregistered) {
            return;
        }
        unregistered = true;
        owner.registry.unregister(this);
    }

    @Override
    public FakeServiceReference<S> getReference() {
        return owner.ref;
    }
}
