/*
 * Copyright 2012 GitHub Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gl4a.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.os.Handler;
import android.text.Html.ImageGetter;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.caverock.androidsvg.SVG;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.fragment.SettingsFragment;

import com.gl4a.gitlab.service.GitLabMarkdownService;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.appcompat.graphics.drawable.DrawableWrapperCompat;
import androidx.core.content.ContextCompat;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import pl.droidsonroids.gif.GifDrawable;

public class HttpImageGetter {
    /** Above this, Phase 1's local preview is skipped for plain text (#186). */
    private static final int LARGE_NOTE_CHARS = 20000;
    /** Larger images aren't inlined as data URIs, they load from their URL (#187). */
    private static final int MAX_EMBEDDED_IMAGE_BYTES = 2 * 1024 * 1024;
    /** Image source for a video's thumbnail: this prefix, then the video's URL. */
    public static final String VIDEO_THUMBNAIL_PREFIX = "octolab-video-thumbnail:";
    /** First frames by video URL, so rebinding a comment doesn't fetch them again. */
    private static final LruCache<String, Bitmap> sVideoFrames = new LruCache<String, Bitmap>(
            8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static class GifCallback implements Drawable.Callback {
        private final List<WeakReference<TextView>> mViewRefs;
        private final Handler mHandler = new Handler();

        public GifCallback(List<WeakReference<TextView>> viewRefs) {
            mViewRefs = viewRefs;
        }

        @Override
        public void invalidateDrawable(@NonNull Drawable drawable) {
            for (WeakReference<TextView> ref : mViewRefs) {
                TextView view = ref.get();
                if (view != null) {
                    view.invalidate();
                    // make sure the TextView's display list is regenerated
                    boolean enabled = view.isEnabled();
                    view.setEnabled(!enabled);
                    view.setEnabled(enabled);
                }
            }
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable drawable,
                                     @NonNull Runnable runnable, long when) {
            mHandler.postAtTime(runnable, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable drawable, @NonNull Runnable runnable) {
            mHandler.removeCallbacks(runnable);
        }
    }

    private static class GifInfo {
        final WeakReference<GifDrawable> mDrawable;
        final GifCallback mCallback;

        public GifInfo(GifDrawable d, List<WeakReference<TextView>> viewRefs) {
            mCallback = new GifCallback(viewRefs);
            mDrawable = new WeakReference<>(d);
            d.setCallback(mCallback);
        }
        public void destroy() {
            GifDrawable drawable = mDrawable.get();
            if (drawable != null) {
                drawable.setCallback(null);
                drawable.stop();
                drawable.recycle();
            }
        }
    }

    // interface just used for tracking purposes
    private static class LoadedBitmapDrawable extends BitmapDrawable {
        public LoadedBitmapDrawable(Resources res, Bitmap bitmap) {
            super(res, bitmap);
        }
    }

    private static class PlaceholderDrawable extends DrawableWrapperCompat implements Runnable {
        private final String mUrl;
        private final ObjectInfo mInfo;
        private Drawable mLoadedImage;

        public PlaceholderDrawable(String url, ObjectInfo info, Drawable placeholder) {
            super(placeholder);
            setBounds(0, 0, placeholder.getIntrinsicWidth(), placeholder.getIntrinsicHeight());
            mUrl = url;
            mInfo = info;
        }

        public String getUrl() {
            return mUrl;
        }

        public void addLoadedImage(Drawable image, Handler handler) {
            synchronized (this) {
                mLoadedImage = image;
                handler.post(this);
            }
        }

        @Override
        public void run() {
            setDrawable(mLoadedImage);
            setBounds(0, 0, mLoadedImage.getIntrinsicWidth(), mLoadedImage.getIntrinsicHeight());
            mInfo.invalidateViewsForNewDrawable();
        }
    }

    private class ObjectInfo implements ImageGetter {
        private final ArrayList<WeakReference<TextView>> mViewRefs = new ArrayList<>();
        private final List<GifInfo> mGifs = new ArrayList<>();
        private final List<WeakReference<Bitmap>> mBitmaps = new ArrayList<>();

        private CharSequence mHtml;
        // Raw server HTML from Phase 2 — cached so table WebView can be re-populated
        // when a recycled ViewHolder is re-bound to the same content.
        String mServerHtml;
        // Phase 2 (server) rendering has been applied, so it isn't requested again (#152)
        boolean mServerRendered;
        private ImageGetterAsyncTask mTask;
        private boolean mHasStartedImageLoad;
        private boolean mResumed = true;

        void bind(TextView view, String html) {
            addView(view);

            if (mHtml == null) {
                encode(view.getContext(), html);
            }

            apply(mHtml);

            if (!mHasStartedImageLoad) {
                ImageSpan[] spans = getImageSpans();
                if (spans.length > 0) {
                    ArrayList<PlaceholderDrawable> imagesToLoad = new ArrayList<>();
                    for (ImageSpan span : spans) {
                        Drawable d = span.getDrawable();
                        if (d instanceof PlaceholderDrawable) {
                            imagesToLoad.add((PlaceholderDrawable) d);
                        }
                    }
                    mTask = new ImageGetterAsyncTask(HttpImageGetter.this, this);
                    mTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                            imagesToLoad.toArray(new PlaceholderDrawable[0]));
                }
                mHasStartedImageLoad = true;
            }
        }
        void encodeAndLoadImages(Context context, String html) {
            applyEncodedAndLoadImages(HtmlUtils.encode(context, html, this));
        }

        /** Like {@link #encodeAndLoadImages}, for text already converted off the main thread. */
        void applyEncodedAndLoadImages(CharSequence encoded) {
            if (encoded == null) return;
            if (mTask != null) {
                mTask.cancel(true);
                mTask = null;
            }
            mHasStartedImageLoad = false;
            synchronized (this) {
                mHtml = encoded;
            }
            apply(mHtml);
            ImageSpan[] spans = getImageSpans();
            if (spans.length > 0) {
                ArrayList<PlaceholderDrawable> imagesToLoad = new ArrayList<>();
                for (ImageSpan span : spans) {
                    Drawable d = span.getDrawable();
                    if (d instanceof PlaceholderDrawable) {
                        imagesToLoad.add((PlaceholderDrawable) d);
                    }
                }
                mTask = new ImageGetterAsyncTask(HttpImageGetter.this, this);
                mTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                        imagesToLoad.toArray(new PlaceholderDrawable[0]));
            }
            mHasStartedImageLoad = true;
        }

        void unbind(TextView view) {
            removeView(view);
        }

        void encode(Context context, String html) {
            CharSequence encoded = HtmlUtils.encode(context, html, this);
            synchronized (this) {
                mHtml = encoded;
            }
        }

        void onImageLoadDone() {
            discardLoadedImages();

            for (ImageSpan span : getImageSpans()) {
                Drawable d = span.getDrawable();
                if (d instanceof PlaceholderDrawable) {
                    PlaceholderDrawable phd = (PlaceholderDrawable) d;
                    d = phd.getDrawable();
                }
                if (d instanceof GifDrawable) {
                    GifDrawable gd = (GifDrawable) d;
                    if (mResumed) {
                        gd.start();
                    }
                    mGifs.add(new GifInfo(gd, mViewRefs));
                } else if (d instanceof LoadedBitmapDrawable) {
                    BitmapDrawable bd = (BitmapDrawable) d;
                    mBitmaps.add(new WeakReference<>(bd.getBitmap()));
                }
            }
        }

        void invalidateViewsForNewDrawable() {
            for (int i = 0; i < mViewRefs.size(); i++) {
                TextView view = mViewRefs.get(i).get();
                if (view != null) {
                    view.setText(view.getText());
                }
            }
        }

        void setResumed(boolean resumed) {
            mResumed = resumed;
            for (GifInfo info : mGifs) {
                GifDrawable drawable = info.mDrawable.get();
                if (drawable == null) {
                    continue;
                }
                if (resumed) {
                    drawable.start();
                } else {
                    drawable.stop();
                }
            }
        }

        @NonNull
        private ImageSpan[] getImageSpans() {
            if (TextUtils.isEmpty(mHtml)) {
                return new ImageSpan[0];
            }
            Spanned spanned = (Spanned) mHtml;
            return spanned.getSpans(0, spanned.length(), ImageSpan.class);
        }

        private void discardLoadedImages() {
            for (WeakReference<Bitmap> ref : mBitmaps) {
                Bitmap bitmap = ref.get();
                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
            mBitmaps.clear();
            for (GifInfo info : mGifs) {
                info.destroy();
            }
            mGifs.clear();
            mHasStartedImageLoad = false;
        }

        void clearHtmlCache() {
            if (mTask != null) {
                mTask.cancel(true);
                mTask = null;
            }
            mHtml = null;
            mServerRendered = false;
            mHasStartedImageLoad = false;
        }

        private void apply(CharSequence text) {
            int visibility = TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE;
            for (int i = 0; i < mViewRefs.size(); i++) {
                TextView view = mViewRefs.get(i).get();
                if (view != null) {
                    view.setText(text);
                    view.setVisibility(visibility);
                }
            }
        }

        /** The views currently showing this note. */
        List<TextView> views() {
            List<TextView> views = new ArrayList<>();
            for (WeakReference<TextView> ref : mViewRefs) {
                TextView view = ref.get();
                if (view != null) views.add(view);
            }
            return views;
        }

        private void addView(TextView view) {
            boolean alreadyPresent = false;
            for (int i = 0; i < mViewRefs.size(); i++) {
                TextView existing = mViewRefs.get(i).get();
                if (existing == null) {
                    mViewRefs.remove(i);
                } else if (existing == view) {
                    alreadyPresent = true;
                }
            }
            if (!alreadyPresent) {
                mViewRefs.add(new WeakReference<>(view));
            }
        }

        private void removeView(TextView view) {
            for (int i = 0; i < mViewRefs.size(); i++) {
                TextView existing = mViewRefs.get(i).get();
                if (existing == null || existing == view) {
                    mViewRefs.remove(i);
                }
            }
        }

        @Override
        public Drawable getDrawable(String source) {
            // Decode data: URIs immediately — no async loading needed.
            if (source != null && source.startsWith("data:")) {
                try {
                    int comma = source.indexOf(',');
                    if (comma > 0) {
                        String meta = source.substring(5, comma); // after "data:"
                        boolean isBase64 = meta.endsWith(";base64");
                        if (isBase64) {
                            byte[] bytes = android.util.Base64.decode(
                                    source.substring(comma + 1), android.util.Base64.DEFAULT);
                            String mime = meta.substring(0, meta.length() - 7); // strip ";base64"
                            int semi = mime.indexOf(';');
                            if (semi > 0) mime = mime.substring(0, semi).trim();
                            Bitmap bmp;
                            if (mime.startsWith("image/svg")) {
                                bmp = renderSvgToBitmap(mContext.getResources(),
                                        new ByteArrayInputStream(bytes));
                            } else {
                                bmp = getBitmap(bytes);
                            }
                            if (bmp != null) {
                                BitmapDrawable bd = new LoadedBitmapDrawable(
                                        mContext.getResources(), bmp);
                                bd.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
                                return bd;
                            }
                        }
                    }
                } catch (Exception e) {
                    // fall through to placeholder
                }
            }
            return new PlaceholderDrawable(source, this, mLoadingDrawable);
        }
    }

    private final Handler mHandler = new Handler();
    private final Map<Object, ObjectInfo> mObjectInfos = new HashMap<>();
    private final Set<Object> mMarkdownApiInProgress = new HashSet<>();
    /** Notes rendered on the server at once while pre-rendering a timeline (#152). */
    private static final int PRERENDER_CONCURRENCY = 3;
    private io.reactivex.disposables.Disposable mPrerender;
    // The project GitLab resolves #N, !N and commit references against (#197)
    private String mProjectPath;
    private final Drawable mGifPlaceholderDrawable;
    private final Drawable mLoadingDrawable;
    private final Drawable mErrorDrawable;
    private final OkHttpClient mClient;

    private final Context mContext;

    private final int mMaxWidth;
    private final int mMaxHeight;

    private boolean mDestroyed;

    public HttpImageGetter(Context context) {
        mContext = context;
        mClient = ServiceFactory.getImageHttpClient();

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        final Point size = new Point();
        wm.getDefaultDisplay().getSize(size);
        mMaxWidth = size.x;
        mMaxHeight = size.y;

        mGifPlaceholderDrawable = ContextCompat.getDrawable(context, R.drawable.image_gif_placeholder);
        mGifPlaceholderDrawable.setBounds(0, 0,
                mGifPlaceholderDrawable.getIntrinsicWidth(), mGifPlaceholderDrawable.getIntrinsicHeight());

        mLoadingDrawable = ContextCompat.getDrawable(context, R.drawable.image_loading);
        mLoadingDrawable.setBounds(0, 0,
                mLoadingDrawable.getIntrinsicWidth(), mLoadingDrawable.getIntrinsicHeight());

        mErrorDrawable = ContextCompat.getDrawable(context, R.drawable.image_error);
        mErrorDrawable.setBounds(0, 0,
                mErrorDrawable.getIntrinsicWidth(), mErrorDrawable.getIntrinsicHeight());
    }

    /**
     * Sets the project whose notes this renders, so GitLab links #N, !N and commit references
     * against it (#197). Without it the app-wide current project is used, which is unset, or
     * another project's, when an issue was opened from a cross-project list.
     */
    public void setProjectPath(String projectPath) {
        mProjectPath = projectPath;
    }

    public void pause() {
        for (ObjectInfo info : mObjectInfos.values()) {
            info.setResumed(false);
        }
    }

    public void resume() {
        for (ObjectInfo info : mObjectInfos.values()) {
            info.setResumed(true);
        }
    }

    public void clearHtmlCache() {
        for (ObjectInfo info : mObjectInfos.values()) {
            info.clearHtmlCache();
        }
        mMarkdownApiInProgress.clear();
    }

    public void destroy() {
        if (mPrerender != null) {
            mPrerender.dispose();
        }
        for (ObjectInfo info : mObjectInfos.values()) {
            info.discardLoadedImages();
        }
        mObjectInfos.clear();
        mMarkdownApiInProgress.clear();
        mDestroyed = true;
    }

    public void encode(final Context context, final Object id, final String html) {
        findOrCreateInfo(id).encode(context, html);
    }

    public void bind(final TextView view, final String html, final Object id) {
        unbind(view);
        findOrCreateInfo(id).bind(view, html);
    }

    public void bindMarkdown(final TextView view, final String markdown, final Object id) {
        unbind(view);
        if (android.text.TextUtils.isEmpty(markdown)) {
            view.setText("");
            return;
        }

        ObjectInfo info = findOrCreateInfo(id);
        info.addView(view);

        // Tag the view with this content id so the async Phase 2 callback can detect
        // if the ViewHolder has been recycled before the response arrives.
        view.setTag(com.gl4a.R.id.wv_table, id);

        if (info.mHtml != null) {
            // Already rendered — apply cached content immediately.
            info.addView(view);
            if (info.mServerHtml != null && info.mServerHtml.contains("<table")
                    && view.getParent() != null) {
                // Re-populate the table WebView from cached server HTML.
                android.webkit.WebView wvTable =
                        ((android.view.View) view.getParent()).findViewById(com.gl4a.R.id.wv_table);
                if (wvTable != null) {
                    showTableInWebView(view, wvTable, info.mServerHtml);
                    return;
                }
            }
            info.apply(info.mHtml);
            return;
        }

        // Phase 1: local rendering for instant text display.
        // encode() + apply() only — do NOT start ImageGetterAsyncTask here.
        // Images stay as loading placeholders until Phase 2 provides correct API URLs.
        // This avoids showing error drawables for relative/broken image URLs in Phase 1.
        // Very large notes (pasted logs, long lists) are shown as plain text until Phase 2's
        // rendering arrives: converting them here would block the main thread (#186).
        if (markdown.length() > LARGE_NOTE_CHARS) {
            view.setText(markdown);
        } else {
            info.encode(view.getContext(), HtmlUtils.markdownToHtml(markdown));
            info.apply(info.mHtml);
        }

        // Phase 2: server-side GFM rendering with images embedded as data URIs, unless the
        // note is already being rendered, e.g. ahead of scrolling (#152).
        if (!mMarkdownApiInProgress.contains(id)) {
            renderOnServer(info, markdown, id).subscribe();
        }
    }

    /**
     * Renders the notes of a timeline on the server ahead of scrolling, a few at a time, top
     * first (#152). Rows then show their final text and images as soon as they scroll in,
     * instead of the local rendering being replaced and images popping in while scrolling,
     * which moved the content and resized the scrollbar. Notes already rendered or rendering
     * are skipped; a row bound before its turn renders itself as usual.
     */
    public void prerenderMarkdown(List<android.util.Pair<Object, String>> notes) {
        if (mPrerender != null) {
            mPrerender.dispose();
        }
        mPrerender = io.reactivex.Observable.fromIterable(notes)
                .flatMap(note -> io.reactivex.Single.defer(() -> {
                    // Subscribed on the main thread, like bindMarkdown, so the in-progress
                    // set is only touched there
                    ObjectInfo info = findOrCreateInfo(note.first);
                    if (mDestroyed || info.mServerRendered
                            || mMarkdownApiInProgress.contains(note.first)
                            || android.text.TextUtils.isEmpty(note.second)) {
                        return io.reactivex.Single.just(false);
                    }
                    return renderOnServer(info, note.second, note.first);
                }).toObservable(), false, PRERENDER_CONCURRENCY)
                .subscribe(done -> {}, error -> {});
    }

    /**
     * Renders {@code markdown} with GitLab's markdown API, embeds instance images and converts
     * the HTML off the main thread, then applies it on the main thread to whichever views show
     * the note by then (possibly none yet). Emits true once applied.
     */
    private io.reactivex.Single<Boolean> renderOnServer(ObjectInfo info, String markdown,
            Object id) {
        mMarkdownApiInProgress.add(id);
        final String instanceUrl = com.gl4a.Gl4Application.get().getInstanceUrl();
        final String tok = com.gl4a.Gl4Application.get().getAuthToken();
        final Map<String, Object> reqBody = new java.util.HashMap<>();
        reqBody.put("text", markdown);
        reqBody.put("gfm", true);
        final String projectPath = mProjectPath != null ? mProjectPath
                : com.gl4a.Gl4Application.get().getCurrentProjectPath();
        if (projectPath != null) reqBody.put("project", projectPath);

        return ServiceFactory.get(GitLabMarkdownService.class, false)
                .render(reqBody)
                .map(response -> {
                    // Runs on IO thread — safe to do blocking image fetches here.
                    if (!response.isSuccessful() || response.body() == null
                            || android.text.TextUtils.isEmpty(response.body().html)) {
                        return new android.util.Pair<String, CharSequence>("", null);
                    }
                    String rawHtml = response.body().html;
                    // Fix lazy-loading: swap data-src → src, make relative absolute.
                    String html = rawHtml
                            .replaceAll("src=\"data:[^\"]*\"([^>]*?)data-src=\"([^\"]+)\"",
                                    "src=\"$2\"$1")
                            .replaceAll("data-src=\"([^\"]+)\"([^>]*?)src=\"data:[^\"]*\"",
                                    "src=\"$1\"$2")
                            .replaceAll("\\s*data-src=\"[^\"]*\"", "")
                            .replaceAll("\\s*data-canonical-src=\"[^\"]*\"", "")
                            .replaceAll("\\s*data-sourcepos=\"[^\"]*\"", "")
                            .replaceAll("\\s*class=\"[^\"]*\"", "")
                            .replaceAll("\\s*dir=\"[^\"]*\"", "")
                            .replaceAll("\\s*decoding=\"[^\"]*\"", "");
                    // Make relative src absolute.
                    html = html.replaceAll("src=\"(/[^\"]+)\"",
                            "src=\"" + instanceUrl + "$1\"");

                    // Embed all instance images as data URIs so they display immediately
                    // without any further async loading or auth complexity.
                    // Only <img> sources: videos matched too and were downloaded whole
                    // and embedded (hangs, memory, a 1.9 MB link that crashed; #187).
                    java.util.regex.Matcher imgM = java.util.regex.Pattern
                            .compile("(<img\\b[^>]*?\\ssrc=\")("
                                    + java.util.regex.Pattern.quote(instanceUrl)
                                    + "[^\"]+)\"")
                            .matcher(html);
                    java.lang.StringBuffer imgSb = new java.lang.StringBuffer();
                    while (imgM.find()) {
                        String prefix = imgM.group(1);
                        String imgUrl = imgM.group(2);
                        // Rewrite /uploads/ and /-/raw/ to API endpoints first.
                        imgUrl = imgUrl.replaceAll(
                                "/-/project/(\\d+)/uploads/",
                                "/api/v4/projects/$1/uploads/");
                        imgUrl = rewriteRawUrl(imgUrl, instanceUrl);
                        // Fetch and embed.
                        String dataUri = fetchAsDataUri(imgUrl, tok);
                        if (dataUri != null) {
                            imgM.appendReplacement(imgSb,
                                    java.util.regex.Matcher.quoteReplacement(
                                            prefix + dataUri + "\""));
                        } else {
                            imgM.appendReplacement(imgSb,
                                    java.util.regex.Matcher.quoteReplacement(
                                            prefix + imgUrl + "\""));
                        }
                    }
                    imgM.appendTail(imgSb);
                    String embedded = imgSb.toString();
                    // Convert to styled text here, off the main thread: for long notes the
                    // HTML parse took seconds and caused an ANR (#186). The main thread
                    // only applies the result.
                    return new android.util.Pair<String, CharSequence>(embedded,
                            embedded.isEmpty() ? null : HtmlUtils.encode(mContext, embedded, info));
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(rendered -> {
                    mMarkdownApiInProgress.remove(id);
                    final String html = rendered.first;
                    if (mDestroyed || html.isEmpty()) {
                        return false;
                    }
                    info.mServerRendered = true;
                    // If HTML contains a table, render in a companion WebView (wv_table) for
                    // proper grid display. Otherwise use Html.fromHtml() via the existing
                    // encode/apply path.
                    if (html.contains("<table")) {
                        // Cache server HTML so the table can be re-shown when the ViewHolder
                        // is recycled and rebound.
                        info.mServerHtml = html;
                        boolean shown = false;
                        for (TextView view : info.views()) {
                            android.webkit.WebView wvTable = view.getParent() != null
                                    && view.getTag(com.gl4a.R.id.wv_table) == id
                                    ? ((android.view.View) view.getParent())
                                            .findViewById(com.gl4a.R.id.wv_table)
                                    : null;
                            if (wvTable != null) {
                                showTableInWebView(view, wvTable, html);
                                shown = true;
                            }
                        }
                        if (shown) {
                            return true;
                        }
                    }
                    // Instance-hosted images are already embedded as data URIs above; anything
                    // else (external URLs) is still a plain <img src="..."> here, so it needs
                    // the async loader to actually fetch it — encode() alone leaves it stuck on
                    // the placeholder.
                    info.applyEncodedAndLoadImages(rendered.second);
                    return true;
                })
                .onErrorReturn(error -> {
                    mMarkdownApiInProgress.remove(id);
                    return false;
                })
                // Pre-rendering is cancelled on reload; let a later bind render the note
                .doOnDispose(() -> mMarkdownApiInProgress.remove(id));
    }

    private void showTableInWebView(android.view.View tvDesc,
            android.webkit.WebView wvTable, String html) {
        tvDesc.setVisibility(android.view.View.GONE);
        wvTable.setVisibility(android.view.View.VISIBLE);
        com.gl4a.activities.WebViewerActivity.addCommonJsInterfaces(wvTable, mContext);
        wvTable.getSettings().setJavaScriptEnabled(true);
        wvTable.setBackgroundColor(0);
        wvTable.setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_OVERLAY);
        wvTable.setVerticalScrollBarEnabled(false);
        wvTable.setHorizontalScrollBarEnabled(false);
        wvTable.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView v,
                    android.webkit.WebResourceRequest req) {
                // Route through LinkParser/IntentUtils so #N issue links open in-app.
                // This requires a FragmentActivity context — get it from the WebView.
                android.content.Context ctx = v.getContext();
                while (ctx instanceof android.content.ContextWrapper
                        && !(ctx instanceof androidx.fragment.app.FragmentActivity)) {
                    ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
                }
                if (ctx instanceof androidx.fragment.app.FragmentActivity) {
                    com.gl4a.utils.IntentUtils.openLinkInternallyOrExternally(
                            (androidx.fragment.app.FragmentActivity) ctx, req.getUrl());
                } else {
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, req.getUrl());
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(i);
                }
                return true;
            }
        });
        // Transparent, with header and borders as tints of the text colour, so the table sits
        // on whatever is behind it (a timeline card, the issue header) in either theme, like
        // the rest of the note (#192). colorBackground differs from the cards' colour.
        int[] attrs = {android.R.attr.textColorPrimary, android.R.attr.textColorLink};
        android.content.res.TypedArray ta = mContext.obtainStyledAttributes(attrs);
        int fgInt = ta.getColor(0, 0xFF212121);
        int linkInt = ta.getColor(1, 0xFFE24329);
        ta.recycle();
        String fg = String.format("#%06X", 0xFFFFFF & fgInt);
        String fgRgb = android.graphics.Color.red(fgInt) + ","
                + android.graphics.Color.green(fgInt) + "," + android.graphics.Color.blue(fgInt);
        String link = String.format("#%06X", 0xFFFFFF & linkInt);
        String wrapped = "<html><head><style>"
                + "body{font-size:14px;font-family:sans-serif;background:transparent"
                + ";color:" + fg + ";margin:0;padding:0;overflow:hidden}"
                + "table{border-collapse:collapse;width:100%;margin-bottom:12px}"
                + "th,td{border:1px solid rgba(" + fgRgb + ",0.25);padding:6px 10px;"
                + "text-align:left}"
                + "th{background:rgba(" + fgRgb + ",0.06);color:" + fg + ";font-weight:bold}"
                + "td{color:" + fg + "}"
                + "code{background:rgba(" + fgRgb + ",0.08);border-radius:3px;padding:0 3px}"
                + "a,a:visited{color:" + link + "}"
                + "::-webkit-scrollbar{display:none}"
                + "</style></head><body>" + html + "</body></html>";
        wvTable.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        wvTable.loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null);
    }

    private String rewriteRawUrl(String url, String instanceUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(instanceUrl)
                        + "/([^/\"]+)/([^/\"]+)/-/raw/([^/\"?]+)/([^\"?]+)")
                .matcher(url);
        if (m.find()) {
            String fp = m.group(4).replace("/", "%2F");
            return instanceUrl + "/api/v4/projects/" + m.group(1) + "%2F" + m.group(2)
                    + "/repository/files/" + fp + "/raw?ref=" + m.group(3);
        }
        return url;
    }

    private String fetchAsDataUri(String urlStr, String tok) {
        try {
            HttpUrl url = HttpUrl.parse(urlStr);
            if (url == null) return null;
            Request.Builder rb = new Request.Builder().url(url);
            if (tok != null) rb.header("PRIVATE-TOKEN", tok);
            try (Response resp = mClient.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                // Embed small images only; big ones keep their URL for the async loader (#187).
                long declared = resp.body().contentLength();
                if (declared > MAX_EMBEDDED_IMAGE_BYTES) return null;
                MediaType declaredType = resp.body().contentType();
                if (declaredType != null && !"image".equals(declaredType.type())
                        && !"application".equals(declaredType.type())) {
                    return null;
                }
                byte[] bytes = resp.body().bytes();
                if (bytes.length == 0 || bytes.length > MAX_EMBEDDED_IMAGE_BYTES) return null;
                MediaType mt = resp.body().contentType();
                String mime = mt != null ? mt.toString() : null;
                if (mime == null || mime.startsWith("application/octet-stream")
                        || mime.startsWith("text/html")) {
                    mime = URLConnection.guessContentTypeFromName(urlStr);
                }
                if (mime == null) {
                    InputStream is = new ByteArrayInputStream(bytes);
                    mime = URLConnection.guessContentTypeFromStream(is);
                }
                if (mime == null) mime = "image/png";
                if (!mime.startsWith("image/")) return null;
                // Strip charset suffix if present (e.g. "image/svg+xml; charset=utf-8")
                int semi = mime.indexOf(';');
                if (semi > 0) mime = mime.substring(0, semi).trim();
                String b64 = android.util.Base64.encodeToString(bytes,
                        android.util.Base64.NO_WRAP);
                return "data:" + mime + ";base64," + b64;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Detaches a view from any pending render, before it shows other text (#180). */
    public void unbindView(final TextView view) {
        unbind(view);
    }

    private void unbind(final TextView view) {
        for (ObjectInfo info : mObjectInfos.values()) {
            info.unbind(view);
        }
    }

    private ObjectInfo findOrCreateInfo(Object id) {
        ObjectInfo info = mObjectInfos.get(id);
        if (info == null) {
            info = new ObjectInfo();
            mObjectInfos.put(id, info);
        }
        return info;
    }

    private static class ImageGetterAsyncTask extends AsyncTask<PlaceholderDrawable, Void, Void> {
        private final HttpImageGetter mImageGetter;
        private final ObjectInfo mInfo;

        public ImageGetterAsyncTask(HttpImageGetter getter, ObjectInfo info) {
            mImageGetter = getter;
            mInfo = info;
        }

        @Override
        protected Void doInBackground(PlaceholderDrawable... params) {
            for (PlaceholderDrawable placeholder : params) {
                Drawable drawable = mImageGetter.loadImageForUrl(placeholder.getUrl());
                if (drawable != null) {
                    placeholder.addLoadedImage(drawable, mImageGetter.mHandler);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (!isCancelled()) {
                mInfo.onImageLoadDone();
            }
        }
    }

    private Drawable loadImageForUrl(String source) {
        if (source != null && source.startsWith(VIDEO_THUMBNAIL_PREFIX)) {
            return loadVideoThumbnail(source.substring(VIDEO_THUMBNAIL_PREFIX.length()));
        }
        HttpUrl url = source != null ? HttpUrl.parse(source) : null;
        Bitmap bitmap = null;

        if (!mDestroyed && url != null) {
            // Explicitly add auth for the GitLab instance — belt-and-suspenders
            // alongside the sImageHttpClient interceptor.
            Request.Builder reqBuilder = new Request.Builder().url(url);
            String instanceHost = android.net.Uri.parse(
                    com.gl4a.Gl4Application.get().getInstanceUrl()).getHost();
            if (instanceHost != null && instanceHost.equalsIgnoreCase(url.host())) {
                String tok = com.gl4a.Gl4Application.get().getAuthToken();
                if (tok != null) reqBuilder.header("PRIVATE-TOKEN", tok);
            }
            Request request = reqBuilder.build();
            try (Response response = mClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    byte[] responseBody = response.body().bytes();
                    // ByteArrayInputStream supports marking, which is required for
                    // URLConnection.guessContentTypeFromStream to work
                    InputStream is = new ByteArrayInputStream(responseBody);
                    MediaType mediaType = response.body().contentType();
                    String mime = mediaType != null ? mediaType.toString() : null;
                    // application/octet-stream is a generic fallback — guess from name/content
                    if (mime == null || mime.startsWith("application/octet-stream")) {
                        mime = URLConnection.guessContentTypeFromName(source);
                    }
                    if (mime == null) {
                        mime = URLConnection.guessContentTypeFromStream(is);
                    }
                    if (mime != null && mime.startsWith("image/svg")) {
                        bitmap = renderSvgToBitmap(mContext.getResources(), is);
                    } else {
                        boolean isGif = mime != null && mime.startsWith("image/gif");
                        if (isGif) {
                            if (canLoadGif()) {
                                GifDrawable d = new GifDrawable(responseBody);
                                d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
                                return d;
                            } else {
                                return mGifPlaceholderDrawable;
                            }
                        } else {
                            bitmap = getBitmap(responseBody);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(Gl4Application.LOG_TAG, "Couldn't display image " + url, e);
                // fall through to showing the error bitmap
            }
        }

        synchronized (this) {
            if (mDestroyed && bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
        }

        if (bitmap == null) {
            return mErrorDrawable;
        }

        BitmapDrawable drawable = new LoadedBitmapDrawable(mContext.getResources(), bitmap);
        drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        return drawable;
    }

    /**
     * The video's first frame with a play button over it, or a plain tile with the play
     * button if the frame can't be read. A fresh bitmap each time, as loaded bitmaps are
     * recycled when the text is rebound; the cached frame never is.
     */
    private Drawable loadVideoThumbnail(String videoUrl) {
        Bitmap frame;
        synchronized (sVideoFrames) {
            frame = sVideoFrames.get(videoUrl);
        }
        if (frame == null && !mDestroyed) {
            frame = readFirstFrame(videoUrl);
            if (frame != null) {
                synchronized (sVideoFrames) {
                    sVideoFrames.put(videoUrl, frame);
                }
            }
        }
        float density = mContext.getResources().getDisplayMetrics().density;
        int tileWidth = Math.min(Math.round(320 * density), mMaxWidth / 2);
        int width = frame != null ? frame.getWidth() : tileWidth;
        int height = frame != null ? frame.getHeight() : tileWidth * 9 / 16;
        Bitmap thumbnail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(thumbnail);
        if (frame != null) {
            canvas.drawBitmap(frame, 0, 0, null);
        } else {
            canvas.drawColor(0xff303030);
        }
        drawPlayButton(canvas, width, height, density);

        BitmapDrawable drawable = new LoadedBitmapDrawable(mContext.getResources(), thumbnail);
        drawable.setBounds(0, 0, width, height);
        return drawable;
    }

    @androidx.annotation.Nullable
    private Bitmap readFirstFrame(String videoUrl) {
        String instanceUrl = Gl4Application.get().getInstanceUrl();
        Map<String, String> headers = new HashMap<>();
        HttpUrl url = HttpUrl.parse(videoUrl);
        String instanceHost = instanceUrl != null ? android.net.Uri.parse(instanceUrl).getHost() : null;
        if (url != null && instanceHost != null && instanceHost.equalsIgnoreCase(url.host())) {
            String tok = Gl4Application.get().getAuthToken();
            if (tok != null) headers.put("PRIVATE-TOKEN", tok);
        }
        // The uploads API first, like images; the web URL still works for public uploads.
        String apiUrl = videoUrl.replaceAll("/-/project/(\\d+)/uploads/",
                "/api/v4/projects/$1/uploads/");
        String[] urls = apiUrl.equals(videoUrl)
                ? new String[] { videoUrl } : new String[] { apiUrl, videoUrl };
        for (String candidate : urls) {
            // The retriever reads only the parts of the file it needs, using range requests.
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(candidate, headers);
                Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    return scaleToMaxWidth(frame);
                }
            } catch (RuntimeException e) {
                Log.d(Gl4Application.LOG_TAG, "Couldn't read a frame of " + candidate, e);
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) {
                    // nothing to clean up
                }
            }
        }
        return null;
    }

    private Bitmap scaleToMaxWidth(Bitmap frame) {
        int maxWidth = mMaxWidth / 2;
        if (frame.getWidth() <= maxWidth) {
            return frame;
        }
        int height = Math.round(frame.getHeight() * (maxWidth / (float) frame.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(frame, maxWidth, Math.max(height, 1), true);
        if (scaled != frame) {
            frame.recycle();
        }
        return scaled;
    }

    private static void drawPlayButton(Canvas canvas, int width, int height, float density) {
        float radius = Math.min(28 * density, Math.min(width, height) / 3f);
        float cx = width / 2f;
        float cy = height / 2f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x99000000);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(Color.WHITE);
        Path triangle = new Path();
        float side = radius * 0.9f;
        triangle.moveTo(cx - side * 0.4f, cy - side * 0.5f);
        triangle.lineTo(cx - side * 0.4f, cy + side * 0.5f);
        triangle.lineTo(cx + side * 0.5f, cy);
        triangle.close();
        canvas.drawPath(triangle, paint);
    }

    private boolean canLoadGif() {
        SharedPreferences prefs = mContext.getSharedPreferences(SettingsFragment.PREF_NAME,
                Context.MODE_PRIVATE);
        int mode = prefs.getInt(SettingsFragment.KEY_GIF_LOADING, 1);
        switch (mode) {
            case 1: // load via Wifi
                return !DownloadUtils.downloadNeedsWarning(mContext);
            case 2: // always load
                return true;
            default:
                return false;
        }
    }

    private Bitmap getBitmap(final byte[] image) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, options);

        int scale = 1;
        while (options.outWidth >= mMaxWidth) {
            options.outWidth /= 2;
            options.outHeight /= 2;
            scale *= 2;
        }

        options.inJustDecodeBounds = false;
        options.inDither = false;
        options.inSampleSize = scale;

        return BitmapFactory.decodeByteArray(image, 0, image.length, options);
    }

    private Bitmap renderSvgToBitmap(Resources res, InputStream is) {
        try {
            SVG svg = SVG.getFromInputStream(is);
            if (svg != null) {
                svg.setRenderDPI(DisplayMetrics.DENSITY_DEFAULT);
                Float density = res.getDisplayMetrics().density;
                int docWidth = (int) (svg.getDocumentWidth() * density);
                int docHeight = (int) (svg.getDocumentHeight() * density);
                if (docWidth < 0 || docHeight < 0) {
                    float aspectRatio = svg.getDocumentAspectRatio();
                    if (aspectRatio > 0) {
                        float heightForAspect = (float) mMaxWidth / aspectRatio;
                        float widthForAspect = (float) mMaxHeight * aspectRatio;
                        if (widthForAspect < heightForAspect) {
                            docWidth = Math.round(widthForAspect);
                            docHeight = mMaxHeight;
                        } else {
                            docWidth = mMaxWidth;
                            docHeight = Math.round(heightForAspect);
                        }
                    } else {
                        docWidth = mMaxWidth;
                        docHeight = mMaxHeight;
                    }

                    // we didn't take density into account anymore when calculating docWidth
                    // and docHeight, so don't scale with it and just let the renderer
                    // figure out the scaling
                    density = null;
                }

                while (docWidth >= mMaxWidth) {
                    docWidth /= 2;
                    docHeight /= 2;
                    if (density != null) {
                        density /= 2;
                    }
                }

                Bitmap bitmap = Bitmap.createBitmap(docWidth, docHeight, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                if (density != null) {
                    canvas.scale(density, density);
                }
                svg.renderToCanvas(canvas);
                return bitmap;
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }
}
