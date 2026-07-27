package com.gl4a.utils;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp interceptor that feeds API call summaries into DebugLogger.
 * Strips auth tokens from URLs before logging.
 */
public class DebugLoggingInterceptor implements Interceptor {

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        DebugLogger logger = DebugLogger.get();
        if (logger.isEnabled()) {
            String url = sanitizeUrl(request.url().toString());
            long bodyLength = -1;
            if (response.body() != null) {
                bodyLength = response.body().contentLength();
            }
            logger.api(request.method(), url, response.code(), bodyLength);
        }

        return response;
    }

    /** Remove private_token and similar query params from the URL before logging. */
    private String sanitizeUrl(String url) {
        return url.replaceAll("([?&])(private_token|access_token|token)=[^&]*", "$1$2=***");
    }
}
