package dev.jdtls.lite.osgi;

import org.eclipse.osgi.service.environment.EnvironmentInfo;

/** Static stand-in for OSGi's EnvironmentInfo service. */
public final class FakeEnvironmentInfo implements EnvironmentInfo {

    public static final FakeEnvironmentInfo INSTANCE = new FakeEnvironmentInfo();

    private FakeEnvironmentInfo() {
    }

    @Override
    public String[] getCommandLineArgs() {
        return new String[0];
    }

    @Override
    public String[] getFrameworkArgs() {
        return new String[0];
    }

    @Override
    public String[] getNonFrameworkArgs() {
        return new String[0];
    }

    @Override
    public String getOSArch() {
        return System.getProperty("os.arch", "x86_64");
    }

    @Override
    public String getNL() {
        return "en_US";
    }

    @Override
    public String getOS() {
        return System.getProperty("os.name", "win32").toLowerCase().startsWith("win") ? "win32" : "linux";
    }

    @Override
    public String getWS() {
        return "win32";
    }

    @Override
    public boolean inDebugMode() {
        return false;
    }

    @Override
    public boolean inDevelopmentMode() {
        return false;
    }

    @Override
    public String getProperty(String key) {
        return System.getProperty(key);
    }

    @Override
    public String setProperty(String key, String value) {
        return System.setProperty(key, value);
    }
}
