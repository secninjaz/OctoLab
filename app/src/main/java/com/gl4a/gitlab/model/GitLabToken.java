package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabToken {
    @Json(name = "access_token") public String accessToken;
    @Json(name = "token_type") public String tokenType;
    @Json(name = "expires_in") public Long expiresIn;
    @Json(name = "refresh_token") public String refreshToken;
    @Json(name = "scope") public String scope;
    @Json(name = "created_at") public long createdAt;

    public String accessToken() { return accessToken; }
    public String refreshToken() { return refreshToken; }
    public Long expiresIn() { return expiresIn; }
}
