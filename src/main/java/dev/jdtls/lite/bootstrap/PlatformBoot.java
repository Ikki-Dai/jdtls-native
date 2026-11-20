package dev.jdtls.lite.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.RegistryFactory;
import org.eclipse.core.runtime.spi.IRegistryProvider;
import org.eclipse.core.runtime.spi.RegistryContributor;
import org.eclipse.core.runtime.spi.RegistryStrategy;
import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.framework.BundleActivator;

import dev.jdtls.lite.Log;
import dev.jdtls.lite.osgi.FakeBundle;
import dev.jdtls.lite.osgi.FakeBundleContext;
import dev.jdtls.lite.osgi.FakeLocation;
import dev.jdtls.lite.osgi.ServiceRegistry;
import dev.jdtls.lite.osgi.StaticFrameworkUtilHelper;

/**
 * Boots the Eclipse platform libraries (JavaModel / core.resources / registry /
 * preferences) WITHOUT an OSGi container:
 *
 * <ol>
 *   <li>standalone extension registry fed with each bundle jar's plugin.xml/fragment.xml</li>
 *   <li>a shared {@link FakeBundleContext} over a {@link ServiceRegistry}</li>
 *   <li>fake instance/install {@link Location} services (workspace directory)</li>
 *   <li>the REAL bundle activators started in dependency order against the fake context</li>
 * </ol>
 */
public final class PlatformBoot {

    /** Start order of the bundles that ship activators (dependency order). */
    private static final List<String> START_ORDER = List.of(
            "org.eclipse.equinox.common",
            "org.eclipse.core.runtime",
            "org.eclipse.core.jobs",
            "org.eclipse.equinox.preferences",
            "org.eclipse.core.resources",
            "org.eclipse.jdt.core");

    private static volatile boolean booted;

    public static ServiceRegistry serviceRegistry;
    public static FakeBundleContext systemContext;
    public static IExtensionRegistry extensionRegistry;
    public static Map<String, BundleJarInfo> bundles;

    private PlatformBoot() {
    }

    public static synchronized void boot(Path configDir, Path workspaceDir) throws Exception {
        if (booted) {
            return;
        }
        long start = System.nanoTime();
        Log.info("platform boot starting");
        // the jrt-fs stub provider locates the modules image via java.home
        System.setProperty("java.home", dev.jdtls.lite.bootstrap.JdkLocator.resolve().toString());
        try {
            java.util.List<java.nio.file.spi.FileSystemProvider> providers =
                    java.nio.file.spi.FileSystemProvider.installedProviders();
            StringBuilder sb = new StringBuilder("installed FileSystemProviders:");
            for (java.nio.file.spi.FileSystemProvider fp : providers) {
                sb.append(' ').append(fp.getScheme()).append('[').append(fp.getClass().getName()).append(']');
            }
            Log.info(sb.toString());
        } catch (Throwable t) {
            Log.warn("provider introspection failed: " + t);
        }

        // 1. discover bundles on the flat classpath
        bundles = ClassPathScanner.discover();
        Log.info("discovered " + bundles.size() + " bundles: " + bundles.keySet());

        // 2. standalone extension registry + contributions from bundle jars
        Path registryCache = configDir.resolve("registry");
        Files.createDirectories(registryCache);
        Object masterToken = new Object();
        Object userToken = new Object();
        extensionRegistry = RegistryFactory.createRegistry(
                new RegistryStrategy(new File[] { registryCache.toFile() }, new boolean[] { false }),
                masterToken, userToken);
        RegistryFactory.setDefaultRegistryProvider((IRegistryProvider) () -> extensionRegistry);

        AtomicLong contributorIds = new AtomicLong();
        int contributed = 0;
        for (BundleJarInfo info : bundles.values()) {
            if (info.pluginXml != null) {
                extensionRegistry.addContribution(new ByteArrayInputStream(info.pluginXml),
                        new RegistryContributor(String.valueOf(contributorIds.incrementAndGet()), info.symbolicName, null, null),
                        false, info.symbolicName + ".plugin", null, masterToken);
                contributed++;
            }
            if (info.fragmentXml != null) {
                extensionRegistry.addContribution(new ByteArrayInputStream(info.fragmentXml),
                        new RegistryContributor(String.valueOf(contributorIds.incrementAndGet()), info.symbolicName, null, null),
                        false, info.symbolicName + ".fragment", null, masterToken);
                contributed++;
            }
        }
        Log.info("extension registry: contributed " + contributed + " descriptors");

        // 3. service registry, bundles, shared context
        serviceRegistry = new ServiceRegistry();
        systemContext = new FakeBundleContext(serviceRegistry, null);
        FakeBundle systemBundle = null;
        for (BundleJarInfo info : bundles.values()) {
            FakeBundle bundle = new FakeBundle(contributorIds.incrementAndGet(), info.symbolicName,
                    info.jarPath.toUri().toString(), info.version, info.headers, systemContext);
            systemContext.addBundle(bundle);
            if (systemBundle == null) {
                systemBundle = bundle;
            }
        }
        systemContext.setSelf(systemBundle);
        systemContext.setStateBase(workspaceDir);
        StaticFrameworkUtilHelper.setSystemBundle(systemBundle);

        // 4. platform services the activators expect
        serviceRegistry.registerService(new String[] { IExtensionRegistry.class.getName() }, extensionRegistry, null);
        serviceRegistry.registerService(new String[] { org.eclipse.osgi.service.environment.EnvironmentInfo.class.getName() },
                dev.jdtls.lite.osgi.FakeEnvironmentInfo.INSTANCE, null);
        serviceRegistry.registerService(new String[] { Location.class.getName() },
                new FakeLocation(workspaceDir.toUri().toURL(), "osgi.instance.area"),
                props("type", "osgi.instance.area", "url", workspaceDir.toUri().toURL().toExternalForm()));
        serviceRegistry.registerService(new String[] { Location.class.getName() },
                new FakeLocation(configDir.toUri().toURL(), "osgi.configuration.area"),
                props("type", "osgi.configuration.area", "url", configDir.toUri().toURL().toExternalForm()));
        serviceRegistry.registerService(new String[] { Location.class.getName() },
                new FakeLocation(javaHomeUrl(), "osgi.install.area"),
                props("type", "osgi.install.area", "url", javaHomeUrl().toExternalForm()));
        // contenttype has no activator: it is a DS component in OSGi; register the singleton here
        try {
            Object contentTypeManager = Class.forName("org.eclipse.core.internal.content.ContentTypeManager")
                    .getMethod("getInstance").invoke(null);
            serviceRegistry.registerService(
                    new String[] { org.eclipse.core.runtime.content.IContentTypeManager.class.getName() },
                    contentTypeManager, null);
            Log.info("registered IContentTypeManager service");
        } catch (Throwable t) {
            Log.warn("could not register IContentTypeManager: " + t);
        }

        // 5. start the real activators
        for (String name : START_ORDER) {
            BundleJarInfo info = bundles.get(name);
            if (info == null || info.activator == null || info.activator.isBlank()) {
                continue;
            }
            try {
                BundleActivator activator = (BundleActivator) Class.forName(info.activator)
                        .getDeclaredConstructor().newInstance();
                activator.start(systemContext);
                Log.info("started " + name + " (" + info.activator + ") isRunning="
                        + org.eclipse.core.runtime.Platform.isRunning());
            } catch (Throwable t) {
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                Log.error("activator start failed for " + name + " [root cause: " + cause + "]", t);
            }
        }

        booted = true;
        Log.info("platform boot done in " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    private static java.util.Dictionary<String, Object> props(String... kv) {
        java.util.Hashtable<String, Object> p = new java.util.Hashtable<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.put(kv[i], kv[i + 1]);
        }
        return p;
    }

    private static URL javaHomeUrl() throws Exception {
        return JdkLocator.resolve().toUri().toURL();
    }

    public static boolean isBooted() {
        return booted;
    }
}
