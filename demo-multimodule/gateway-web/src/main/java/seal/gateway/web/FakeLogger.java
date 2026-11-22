package seal.gateway.web;

public final class FakeLogger {
    public static FakeLogger getLogger(Class<?> c) {
        return new FakeLogger();
    }

    public void warn(String format, Object... args) {}
}
