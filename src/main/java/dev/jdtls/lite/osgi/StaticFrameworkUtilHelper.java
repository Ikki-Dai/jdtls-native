package dev.jdtls.lite.osgi;

import java.util.Optional;

import org.osgi.framework.Bundle;
import org.osgi.framework.connect.FrameworkUtilHelper;

/**
 * Registered via META-INF/services. OSGi R8's {@link org.osgi.framework.FrameworkUtil#getBundle(Class)}
 * consults this hook when no real framework is present — which is exactly our
 * situation. Without it, platform code that constructs {@code ServiceCaller}s
 * (and similar) fails because it cannot identify its own bundle.
 */
public final class StaticFrameworkUtilHelper implements FrameworkUtilHelper {

    private static volatile Bundle systemBundle;

    public static void setSystemBundle(Bundle bundle) {
        systemBundle = bundle;
    }

    @Override
    public Optional<Bundle> getBundle(Class<?> caller) {
        return Optional.ofNullable(systemBundle);
    }
}
