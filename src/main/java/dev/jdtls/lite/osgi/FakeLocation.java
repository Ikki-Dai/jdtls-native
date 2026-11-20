package dev.jdtls.lite.osgi;

import java.net.URL;

import org.eclipse.osgi.service.datalocation.Location;

/**
 * Fixed-location stand-in for the OSGi instance location: it tells the Eclipse
 * workspace where to place its .metadata state.
 */
public final class FakeLocation implements Location {

    private final URL url;
    private final String type;

    public FakeLocation(URL url, String type) {
        this.url = url;
        this.type = type;
    }

    public String type() {
        return type;
    }

    @Override
    public boolean allowsDefault() {
        return false;
    }

    @Override
    public URL getDefault() {
        return null;
    }

    @Override
    public Location getParentLocation() {
        return null;
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public boolean isSet() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean setURL(URL value, boolean lock) {
        return true;
    }

    @Override
    public boolean set(URL value, boolean lock) {
        return true;
    }

    @Override
    public boolean set(URL value, boolean lock, String lockFileSuffix) {
        return true;
    }

    @Override
    public boolean lock() {
        return true;
    }

    @Override
    public void release() {
    }

    @Override
    public boolean isLocked() {
        return false;
    }

    @Override
    public Location createLocation(Location parent, URL newUrl, boolean readOnly) {
        return new FakeLocation(newUrl, type);
    }

    @Override
    public URL getDataArea(String path) throws java.io.IOException {
        String base = url.toExternalForm();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return new URL(base + path);
    }
}
