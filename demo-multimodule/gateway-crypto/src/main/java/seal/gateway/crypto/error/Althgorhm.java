package seal.gateway.crypto.error;

public enum Althgorhm {
    AES_GCM,
    RSA_OAEP;

    public String headerValue() {
        return name().toLowerCase().replace('_', '-');
    }
}
