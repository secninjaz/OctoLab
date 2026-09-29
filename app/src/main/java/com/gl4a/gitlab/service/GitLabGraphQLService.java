package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabGraphQLAwardEmojiMutationResponse;
import com.gl4a.gitlab.model.GitLabGraphQLNoteAwardEmojiResponse;
import com.gl4a.gitlab.model.GitLabMembershipsResponse;
import com.gl4a.gitlab.model.GitLabNoteReactionsResponse;
import com.gl4a.gitlab.model.GitLabNotesByIdResponse;

import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.POST;

/**
 * GitLab GraphQL endpoint. REST stays the primary API; GraphQL is only used where REST has no
 * equivalent (award emoji on commit comment notes) or where it avoids per-item REST calls
 * (loading every note's award emoji in one paged query). Create via ServiceFactory.getGraphQL().
 */
public interface GitLabGraphQLService {

    @POST("graphql")
    Single<Response<GitLabNoteReactionsResponse>> getNoteReactions(@Body Map<String, Object> body);

    @POST("graphql")
    Single<Response<GitLabMembershipsResponse>> getMemberships(@Body Map<String, Object> body);

    @POST("graphql")
    Single<Response<GitLabNotesByIdResponse>> getNotesById(@Body Map<String, Object> body);

    @POST("graphql")
    Single<Response<GitLabGraphQLNoteAwardEmojiResponse>> queryNoteAwardEmoji(
            @Body Map<String, Object> body);

    @POST("graphql")
    Single<Response<GitLabGraphQLAwardEmojiMutationResponse>> awardEmojiAdd(
            @Body Map<String, Object> body);

    @POST("graphql")
    Single<Response<GitLabGraphQLAwardEmojiMutationResponse>> awardEmojiRemove(
            @Body Map<String, Object> body);
}
