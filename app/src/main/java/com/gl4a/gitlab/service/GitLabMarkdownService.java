package com.gl4a.gitlab.service;

import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface GitLabMarkdownService {
    @POST("markdown")
    Single<Response<com.gl4a.gitlab.model.GitLabMarkdownResult>> render(
            @Body Map<String, Object> body);
}
