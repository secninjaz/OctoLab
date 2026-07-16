package com.gl4a.github.model;
/** Stub for GitHub SDK RequestToken. */
public class RequestToken {
    private String clientId;
    private String clientSecret;
    private String code;
    private RequestToken() {}
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final RequestToken r = new RequestToken();
        public Builder clientId(String id) { r.clientId = id; return this; }
        public Builder clientSecret(String s) { r.clientSecret = s; return this; }
        public Builder code(String c) { r.code = c; return this; }
        public RequestToken build() { return r; }
    }
}
