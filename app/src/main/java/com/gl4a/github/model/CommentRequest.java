package com.gl4a.github.model;
/** Stub for GitHub SDK CommentRequest. Replace with GitLab comment note creation request. */
public class CommentRequest {
    private final String body;
    private CommentRequest(String body) { this.body = body; }
    public String body() { return body; }
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private String body;
        public Builder body(String b) { this.body = b; return this; }
        public CommentRequest build() { return new CommentRequest(body); }
    }
}
