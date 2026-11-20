package dev.jdtls.lite.osgi;

import org.osgi.framework.InvalidSyntaxException;

import org.eclipse.osgi.internal.framework.FilterImpl;

/** LDAP filters delegated to Equinox's own {@link FilterImpl} implementation. */
public final class FakeFilter {

    public static org.osgi.framework.Filter parse(String filterString) throws InvalidSyntaxException {
        return FilterImpl.newInstance(filterString);
    }

    private FakeFilter() {
    }
}
