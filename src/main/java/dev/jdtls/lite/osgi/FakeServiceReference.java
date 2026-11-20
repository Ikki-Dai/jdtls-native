package dev.jdtls.lite.osgi;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;

/** ServiceReference backed by {@link ServiceRegistry}. */
public final class FakeServiceReference<S> implements ServiceReference<S> {

    final ServiceRegistry.Reg owner;

    FakeServiceReference(ServiceRegistry.Reg owner) {
        this.owner = owner;
    }

    ServiceRegistry.Reg owner() {
        return owner;
    }

    @Override
    public Object getProperty(String key) {
        return owner.props.get(key);
    }

    @Override
    public String[] getPropertyKeys() {
        List<String> keys = new ArrayList<>();
        for (Enumeration<String> e = owner.props.keys(); e.hasMoreElements(); ) {
            keys.add(e.nextElement());
        }
        return keys.toArray(String[]::new);
    }

    @Override
    public Bundle getBundle() {
        return null;
    }

    @Override
    public Bundle[] getUsingBundles() {
        return new Bundle[0];
    }

    @Override
    public boolean isAssignableTo(Bundle bundle, String className) {
        for (String cn : owner.classNames) {
            if (cn.equals(className)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int compareTo(Object other) {
        return 0;
    }

    @Override
    public <A> A adapt(Class<A> type) {
        return null;
    }

    @Override
    public Dictionary<String, Object> getProperties() {
        Dictionary<String, Object> copy = new Hashtable<>();
        for (Enumeration<String> e = owner.props.keys(); e.hasMoreElements(); ) {
            String key = e.nextElement();
            copy.put(key, owner.props.get(key));
        }
        return copy;
    }
}
