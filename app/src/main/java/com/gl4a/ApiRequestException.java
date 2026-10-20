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
     * {"error": "...", "error_description": "..."}; its message can also be an object or a list,
     * e.g. {"message": {"error": "…"}} when rate limited or {"message": {"title": ["…"]}} for
     * invalid fields, so each is read as whatever it is and turned into text (#294).
     */
    public static class ErrorBody {
        @Json(name = "message") public Object message;
        @Json(name = "error") public Object error;
        @Json(name = "error_description") public Object errorDescription;
        @Json(name = "errors") public Object errors;

        public String message() { return text(message); }
        public List<String> errors() {
            List<String> result = new java.util.ArrayList<>();
            if (errors instanceof List) {
                for (Object item : (List<?>) errors) {
                    String text = text(item);
                    if (!TextUtils.isEmpty(text)) result.add(text);
                }
            } else {
                String text = text(errors);
                if (!TextUtils.isEmpty(text)) result.add(text);
            }
            return result;
        }
    }

    /** GitLab's message as text: as is, a list's items, an object's "field: …" entries. */
    static String text(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof List) {
            List<String> parts = new java.util.ArrayList<>();
            for (Object item : (List<?>) value) {
                String text = text(item);
                if (!TextUtils.isEmpty(text)) parts.add(text);
            }
            return TextUtils.join(", ", parts);
        }
        if (value instanceof java.util.Map) {
            List<String> parts = new java.util.ArrayList<>();
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) value).entrySet()) {
                String text = text(entry.getValue());
                if (TextUtils.isEmpty(text)) continue;
                // {"error": "…"} reads as its text; a field's errors as "field: …"
                String key = String.valueOf(entry.getKey());
                parts.add("error".equals(key) || "message".equals(key) ? text : key + ": " + text);
            }
            return TextUtils.join("; ", parts);
        }
        if (value instanceof Double && ((Double) value) == Math.rint((Double) value)) {
            return String.valueOf(((Double) value).longValue());
        }
        return String.valueOf(value);
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
        } catch (IOException | RuntimeException e) {
            // ignored — leave parsed null: an error answer that can't be read is still the
            // error it says it is (its status), not a failure to read GitLab's answer (#294)
        }
        mErrorBody = parsed;
    }

    public int getStatus() {
        return mStatus;
    }

    /** GitLab's "too many requests": a rate limit, e.g. on searches (#294). */
    public boolean isRateLimited() {
        return mStatus == 429;
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

        String message = mErrorBody.message();
        if (TextUtils.isEmpty(message)) message = text(mErrorBody.error);
        List<String> fieldErrors = mErrorBody.errors();

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
