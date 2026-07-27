package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabTodo;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GitLabTodoService {

    // List todos — all params are optional filters
    // state: "pending" | "done"
    // type:  "Issue" | "MergeRequest" | "DesignManagement::Design" | "AlertManagement::Alert"
    // action: "assigned" | "mentioned" | "build_failed" | "marked" | "approval_required" |
    //         "unmergeable" | "directly_addressed" | "merge_train_removed" |
    //         "review_requested" | "member_access_requested" | "review_submitted" |
    //         "okr_checkin_requested"
    @GET("todos")
    Single<Response<List<GitLabTodo>>> listTodos(
            @Query("state") String state,
            @Query("type") String type,
            @Query("action") String action,
            @Query("author_id") Long authorId,
            @Query("project_id") Long projectId,
            @Query("group_id") Long groupId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Convenience overload for issue/MR mention and participation lists.
    // state="pending" | "done" — must be called twice and results merged to get all todos.
    @GET("todos")
    Single<Response<List<GitLabTodo>>> listTodos(
            @androidx.annotation.Nullable @Query("type") String type,
            @androidx.annotation.Nullable @Query("action") String action,
            @Query("state") String state,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Convenience overload for simple state-only filtering
    @GET("todos")
    Single<Response<List<GitLabTodo>>> listTodosByState(
            @Query("state") String state,   // "pending" | "done"
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Mark a single todo as done — POST /todos/:id/mark_as_done
    @POST("todos/{id}/mark_as_done")
    Single<Response<GitLabTodo>> markAsDone(@Path("id") long todoId);

    // Mark all pending todos as done — POST /todos/mark_as_done
    @POST("todos/mark_as_done")
    Single<Response<Void>> markAllAsDone();

    // Delete a todo — DELETE /todos/:id
    @DELETE("todos/{id}")
    Single<Response<Void>> deleteTodo(@Path("id") long todoId);
}
