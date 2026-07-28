package com.gl4a;

import android.content.Context;

import com.gl4a.gitlab.core.GitLabPaginationInterceptor;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter;

import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.CacheControl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.moshi.MoshiConverterFactory;

public class ServiceFactory {
    private static final int DEFAULT_PER_PAGE = 30;
    private static final int MAX_PER_PAGE = 100;

    private static final Moshi MOSHI = new Moshi.Builder()
            .add(Date.class, new Rfc3339DateJsonAdapter().nullSafe())
            // Treat JSON null for any primitive boolean field as false.
            // GitLab EE omits boolean fields that are null/not-applicable
            // (e.g. force_remove_source_branch, is_admin, bot, resolved).
            // Without this adapter Moshi throws JsonDataException on those fields.
            .add(boolean.class, new com.squareup.moshi.JsonAdapter<Boolean>() {
                @androidx.annotation.NonNull @Override
                public Boolean fromJson(@androidx.annotation.NonNull com.squareup.moshi.JsonReader reader)
                        throws java.io.IOException {
                    if (reader.peek() == com.squareup.moshi.JsonReader.Token.NULL) {
                        reader.nextNull();
                        return false;
                    }
                    return reader.nextBoolean();
                }
                @Override
                public void toJson(@androidx.annotation.NonNull com.squareup.moshi.JsonWriter writer,
                        Boolean value) throws java.io.IOException {
                    writer.value(value != null && value);
                }
            })
            // Treat JSON null for any primitive int field as 0.
            // GitLab EE omits int fields that are null (e.g. push_data.ref_count).
            // Without this adapter Moshi throws JsonDataException on those fields.
            .add(int.class, new com.squareup.moshi.JsonAdapter<Integer>() {
                @androidx.annotation.NonNull @Override
                public Integer fromJson(@androidx.annotation.NonNull com.squareup.moshi.JsonReader reader)
                        throws java.io.IOException {
                    if (reader.peek() == com.squareup.moshi.JsonReader.Token.NULL) {
                        reader.nextNull();
                        return 0;
                    }
                    return reader.nextInt();
                }
                @Override
                public void toJson(@androidx.annotation.NonNull com.squareup.moshi.JsonWriter writer,
                        Integer value) throws java.io.IOException {
                    writer.value(value != null ? value : 0);
                }
            })
            // Treat JSON null for any primitive long field as 0 (e.g. noteable_id in activity events)
            .add(long.class, new com.squareup.moshi.JsonAdapter<Long>() {
                @androidx.annotation.NonNull @Override
                public Long fromJson(@androidx.annotation.NonNull com.squareup.moshi.JsonReader reader)
                        throws java.io.IOException {
                    if (reader.peek() == com.squareup.moshi.JsonReader.Token.NULL) {
                        reader.nextNull();
                        return 0L;
                    }
                    return reader.nextLong();
                }
                @Override
                public void toJson(@androidx.annotation.NonNull com.squareup.moshi.JsonWriter writer,
                        Long value) throws java.io.IOException {
                    writer.value(value != null ? value : 0L);
                }
            })
            .build();

    private static final HttpLoggingInterceptor LOGGING_INTERCEPTOR =
            new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC);

    private static final Interceptor CACHE_MAX_AGE_INTERCEPTOR = chain -> {
        Response response = chain.proceed(chain.request());
        CacheControl origCacheControl = CacheControl.parse(response.headers());
        if (origCacheControl.maxAgeSeconds() <= 2) return response;
        CacheControl.Builder nb = new CacheControl.Builder().maxAge(2, TimeUnit.SECONDS);
        if (origCacheControl.noCache()) nb.noCache();
        if (origCacheControl.noStore()) nb.noStore();
        return response.newBuilder()
                .header("Cache-Control", nb.build().toString()).build();
    };

    private static final Interceptor CACHE_BYPASS_INTERCEPTOR = chain ->
            chain.proceed(chain.request().newBuilder()
                    .addHeader("Cache-Control", "no-cache").build());

    private static OkHttpClient sApiHttpClient;
    private static OkHttpClient sImageHttpClient;
    private static final HashMap<String, Object> sCache = new HashMap<>();

    public static <S> S get(Class<S> serviceClass, boolean bypassCache) {
        return get(serviceClass, bypassCache, null, null, null);
    }

    public static <S> S get(Class<S> serviceClass, boolean bypassCache, Integer pageSize) {
        return get(serviceClass, bypassCache, null, null, pageSize);
    }

    public static <S> S getForFullPagedLists(Class<S> serviceClass, boolean bypassCache) {
        return get(serviceClass, bypassCache, MAX_PER_PAGE);
    }

    public static <S> S get(Class<S> serviceClass, boolean bypassCache, String ignored,
            String token, Integer pageSize) {
        String key = String.format(Locale.US, "%s-%d-%s-%d",
                serviceClass.getSimpleName(), bypassCache ? 1 : 0,
                token != null ? token : "", pageSize != null ? pageSize : 0);
        S service = (S) sCache.get(key);
        if (service == null) {
            service = createService(serviceClass, bypassCache, token, pageSize);
            sCache.put(key, service);
        }
        return service;
    }

    public static <S> S getOAuthService(Class<S> serviceClass) {
        String baseUrl = Gl4Application.get().getInstanceBaseUrl();
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(MoshiConverterFactory.create(MOSHI))
                .client(sApiHttpClient)
                .build()
                .create(serviceClass);
    }

    private static <S> S createService(Class<S> serviceClass, boolean bypassCache,
            String token, Integer pageSize) {
        int perPage = pageSize != null ? pageSize : DEFAULT_PER_PAGE;
        OkHttpClient.Builder cb = sApiHttpClient.newBuilder()
                .addInterceptor(new GitLabPaginationInterceptor(perPage))
                .addNetworkInterceptor(CACHE_MAX_AGE_INTERCEPTOR)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder rb = original.newBuilder()
                            .method(original.method(), original.body());
                    String tok = token != null ? token : Gl4Application.get().getAuthToken();
                    if (tok != null) {
                        // PATs use PRIVATE-TOKEN header; OAuth2 access tokens use Bearer.
                        String tokenType = token != null
                                ? inferTokenType(token)
                                : Gl4Application.get().getAuthTokenType();
                        if (Gl4Application.TOKEN_TYPE_OAUTH.equals(tokenType)) {
                            rb.header("Authorization", "Bearer " + tok);
                        } else {
                            rb.header("PRIVATE-TOKEN", tok);
                        }
                    }
                    return chain.proceed(rb.build());
                });
        if (BuildConfig.DEBUG) cb.addInterceptor(LOGGING_INTERCEPTOR);
        cb.addInterceptor(new com.gl4a.utils.DebugLoggingInterceptor());
        if (bypassCache) cb.addInterceptor(CACHE_BYPASS_INTERCEPTOR);
        return new Retrofit.Builder()
                .baseUrl(Gl4Application.get().getApiBaseUrl())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(MoshiConverterFactory.create(MOSHI))
                .client(cb.build())
                .build()
                .create(serviceClass);
    }

    /** Sniff token type from its format. glpat-/glgat-/gldt- prefixes = PAT, else assume OAuth2. */
    private static String inferTokenType(String token) {
        if (token == null) return Gl4Application.TOKEN_TYPE_PAT;
        if (token.startsWith("glpat-") || token.startsWith("glgat-")
                || token.startsWith("gldt-") || token.startsWith("glsoat-")) {
            return Gl4Application.TOKEN_TYPE_PAT;
        }
        // GitLab OAuth2 access tokens are 20 random alphanumeric characters (no prefix).
        // Legacy PATs (pre-14.0) have no prefix but are typically longer.
        if (token.length() == 20 && token.matches("[a-zA-Z0-9]+")) {
            return Gl4Application.TOKEN_TYPE_OAUTH;
        }
        // Default to PAT since that's what the token login screen is for.
        return Gl4Application.TOKEN_TYPE_PAT;
    }

    public static void invalidateCache() { sCache.clear(); }
    public static OkHttpClient.Builder getHttpClientBuilder() { return sApiHttpClient.newBuilder(); }
    public static OkHttpClient getImageHttpClient() { return sImageHttpClient; }

    static void initClient(Context context) {
        int twentyMB = 20 * 1024 * 1024;
        sApiHttpClient = new OkHttpClient.Builder()
                .cache(new Cache(new File(context.getCacheDir(), "api-http"), twentyMB))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        sImageHttpClient = sApiHttpClient.newBuilder()
                .cache(new Cache(new File(context.getCacheDir(), "image-http"), twentyMB))
                .addInterceptor(chain -> {
                    okhttp3.Request req = chain.request();
                    String host = req.url().host();
                    String instanceHost = android.net.Uri.parse(
                            Gl4Application.get().getInstanceUrl()).getHost();
                    if (host != null && host.equals(instanceHost)) {
                        String tok = Gl4Application.get().getAuthToken();
                        if (tok != null) {
                            req = req.newBuilder().header("PRIVATE-TOKEN", tok).build();
                        }
                    }
                    return chain.proceed(req);
                })
                .build();
    }
}
