package com.gl4a.github.model;
/** Stub for GitHub SDK CreateCommitComment. */
public class CreateCommitComment {
    private String body;
    private String path;
    private int position;
    private CreateCommitComment() {}
    public String body() { return body; }
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final CreateCommitComment r = new CreateCommitComment();
        public Builder body(String b) { r.body = b; return this; }
        public Builder path(String p) { r.path = p; return this; }
        public Builder position(int p) { r.position = p; return this; }
        public CreateCommitComment build() { return r; }
    }
}
