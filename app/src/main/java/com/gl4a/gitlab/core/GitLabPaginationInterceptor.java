package com.gl4a.gitlab.core;

import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;

/**
 * Pass-through interceptor for GitLab API requests.
 *
 * <p>Previously this interceptor injected a per_page parameter on every outgoing request,
 * which conflicted with explicit per_page values set by individual service methods. The
 * interceptor is now a no-op: all per_page values must be declared as @Query parameters
 * on the Retrofit service interface methods so that callers have full control over page
 * size. Pagination header parsing (X-Page, X-Next-Page, X-Total-Pages, X-Total) is
 * handled in {@code ApiHelpers.toPage()} at the fragment/loadPage() layer.</p>
 */
public class GitLabPaginationInterceptor implements Interceptor {

    public GitLabPaginationInterceptor(int defaultPerPage) {
        // defaultPerPage no longer used; kept for backward-compatible construction.
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        return chain.proceed(chain.request());
    }
}
