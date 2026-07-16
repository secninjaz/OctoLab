package com.gl4a.github.model;
/** Stub for GitHub SDK SubscriptionRequest. */
public class SubscriptionRequest {
    private boolean subscribed;
    private SubscriptionRequest() {}
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final SubscriptionRequest r = new SubscriptionRequest();
        public Builder subscribed(boolean s) { r.subscribed = s; return this; }
        public SubscriptionRequest build() { return r; }
    }
}
