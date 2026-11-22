package seal.gateway.web;

import seal.gateway.crypto.error.Althgorhm;
public final class GatewayHandler {
    private static final FakeLogger log = FakeLogger.getLogger(GatewayHandler.class);

    public String algorithmHeader(Althgorhm althgorhm) {
        return althgorhm.headerValue();
    }
}
