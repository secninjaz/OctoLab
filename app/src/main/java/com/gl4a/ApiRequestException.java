package com.gl4a;

import android.text.TextUtils;

import com.squareup.moshi.Json;
import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import retrofit2.Response;

public class ApiRequestException extends RuntimeException {
    private static final long serialVersionUID = -4331443972707730572L;

    /**
     * Minimal representation of a GitLab API error body.
     * GitLab returns errors as {"message": "...", "error": "..."} or
     * {"error": "...", "error_description": "..."}.
     */
    public static class ErrorBody {
        @Json(name = "message") public String message;
        @Json(name = "error") public String error;
        @Json(name = "error_description") public String errorDescription;
        @Json(name = "errors") public List<String> errors;

        public String message() { return message; }
        public List<String> errors() { return errors; }
    }

    private static final Moshi MOSHI = new Moshi.Builder().build();

    private final ErrorBody mErrorBody;
    private final int mStatus;

    public ApiRequestException(Response response) {
        mStatus = response.code();

        ErrorBody parsed = null;
        try {
            if (response.errorBody() != null) {
                parsed = MOSHI.adapter(ErrorBody.class)
                        .fromJson(response.errorBody().source());
            }
        } catch (IOException e) {
            // ignored — leave parsed null
        }
        mErrorBody = parsed;
    }

    public int getStatus() {
        return mStatus;
    }

    /**
     * Returns the parsed error body, or null if the response body could not be parsed.
     */
    public ErrorBody getResponse() {
        return mErrorBody;
    }

    @Override
    public String getMessage() {
        if (mErrorBody == null) {
            return "HTTP status " + mStatus;
        }

        String message = mErrorBody.message != null ? mErrorBody.message : mErrorBody.error;
        List<String> fieldErrors = mErrorBody.errors;

        if (!TextUtils.isEmpty(message) && fieldErrors != null && !fieldErrors.isEmpty()) {
            return String.format(Locale.US, "%1$s (%2$d) [%3$s]",
                    message, mStatus, TextUtils.join(", ", fieldErrors));
        } else if (!TextUtils.isEmpty(message)) {
            return String.format(Locale.US, "%1$s (%2$d)", message, mStatus);
        } else {
            return "HTTP status " + mStatus;
        }
    }
}
