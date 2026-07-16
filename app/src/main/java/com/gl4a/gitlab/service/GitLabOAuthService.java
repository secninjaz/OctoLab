package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabToken;

import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface GitLabOAuthService {
    @POST("oauth/token")
    Single<Response<GitLabToken>> getToken(@Body Map<String, String> body);
}
