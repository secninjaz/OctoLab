package com.gl4a.widget;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;

import androidx.core.view.NestedScrollingChild2;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentActivity;

import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import com.gl4a.R;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.MarkdownStyle;
import com.gl4a.utils.StringUtils;

public class MarkdownPreviewWebView extends WebView implements NestedScrollingChild2 {
    private final NestedScrollingChildHelper mChildHelper;
    private final int[] mScrollOffset = new int[2];
    private final int[] mScrollConsumed = new int[2];
    private int mNestedOffsetY;
    private int mLastY;

    // The text to preview, and whether the shown preview is older than it (#266)
    private String mText = "";
    private boolean mStale;
    private io.reactivex.disposables.Disposable mRendering;
    private final Runnable mRenderIfShown = this::renderIfShown;
    /** Typing pause after which the preview is rendered again. */
    private static final long RENDER_DELAY_MS = 600;
    /** The preview's own margin around the content (the shared page has none). */
    private static final String PREVIEW_CSS = "body{padding:16px}";

    public MarkdownPreviewWebView(Context context) {
        this(context, null);
    }

    public MarkdownPreviewWebView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.webViewStyle);
    }

    public MarkdownPreviewWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mChildHelper = new NestedScrollingChildHelper(this);
        setNestedScrollingEnabled(true);

        if (!isInEditMode()) {
            initWebViewSettings(getSettings());
            setWebViewClient(getUrlHandlingClient());
            setBackgroundColor(android.graphics.Color.TRANSPARENT);
            showPage("");
            // Switching to the Preview tab scrolls it into sight: render then if needed
            getViewTreeObserver().addOnScrollChangedListener(() -> {
                if (mStale) {
                    renderIfShown();
                }
            });
        }
    }

    private WebViewClient getUrlHandlingClient() {
        return new WebViewClient() {
            @Override
            @TargetApi(24)
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                IntentUtils.openLinkInternallyOrExternally((FragmentActivity) getContext(), request.getUrl());
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                IntentUtils.openLinkInternallyOrExternally((FragmentActivity) getContext(), Uri.parse(url));
                return true;
            }
        };
    }

    public void setEditText(EditText editor) {
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                setContent(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result;

        MotionEvent event = MotionEvent.obtain(ev);
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mNestedOffsetY = 0;
        }

        int eventY = (int) event.getY();
        event.offsetLocation(0, mNestedOffsetY);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mLastY = eventY;
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH);
                result = super.onTouchEvent(event);
                break;
            case MotionEvent.ACTION_MOVE:
                int deltaY = mLastY - eventY;
                if (dispatchNestedPreScroll(0, deltaY, mScrollConsumed,
                        mScrollOffset, ViewCompat.TYPE_TOUCH)) {
                    deltaY -= mScrollConsumed[1];
                    mLastY = eventY - mScrollOffset[1];
                    event.offsetLocation(0, -mScrollOffset[1]);
                    mNestedOffsetY += mScrollOffset[1];
                }

                result = super.onTouchEvent(event);

                if (dispatchNestedScroll(0, mScrollOffset[1], 0, deltaY,
                        mScrollOffset, ViewCompat.TYPE_TOUCH)) {
                    event.offsetLocation(0, mScrollOffset[1]);
                    mNestedOffsetY += mScrollOffset[1];
                    mLastY -= mScrollOffset[1];
                }
                break;
            default:
                stopNestedScroll(ViewCompat.TYPE_TOUCH);
                result = super.onTouchEvent(event);
                break;
        }

        return result;
    }

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        mChildHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return mChildHelper.isNestedScrollingEnabled();
    }

    @Override
    public boolean startNestedScroll(int axes, int type) {
        return mChildHelper.startNestedScroll(axes, type);
    }

    @Override
    public void stopNestedScroll(int type) {
        mChildHelper.stopNestedScroll(type);
    }

    @Override
    public boolean hasNestedScrollingParent(int type) {
        return mChildHelper.hasNestedScrollingParent(type);
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed,  int[] offsetInWindow, int type) {
        return mChildHelper.dispatchNestedScroll(dxConsumed, dyConsumed,
                dxUnconsumed, dyUnconsumed, offsetInWindow, type);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed,
            int[] offsetInWindow, int type) {
        return mChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return mChildHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return mChildHelper.dispatchNestedPreFling(velocityX, velocityY);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebViewSettings(WebSettings s) {
        s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        s.setLoadsImagesAutomatically(true);
        s.setJavaScriptEnabled(true);
        s.setUseWideViewPort(false);
        s.setAllowFileAccess(false);
    }

    /**
     * The preview is GitLab's own rendering of the text, as GitLab web's preview, in the shared
     * page and style (MarkdownStyle), so it looks as the posted comment will (#266). It's
     * rendered once typing pauses and only while the preview is on screen.
     */
    private void setContent(String content) {
        mText = content;
        mStale = true;
        removeCallbacks(mRenderIfShown);
        postDelayed(mRenderIfShown, RENDER_DELAY_MS);
    }

    private void renderIfShown() {
        if (!mStale || !isAttachedToWindow() || !getGlobalVisibleRect(new android.graphics.Rect())) {
            return;
        }
        mStale = false;
        final String text = mText;
        if (text.trim().isEmpty()) {
            showPage("");
            return;
        }
        if (mRendering != null) {
            mRendering.dispose();
        }
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("text", text);
        body.put("gfm", true);
        String project = com.gl4a.Gl4Application.get().getCurrentProjectPath();
        if (project != null) {
            body.put("project", project);
        }
        mRendering = com.gl4a.ServiceFactory.get(
                com.gl4a.gitlab.service.GitLabMarkdownService.class, false)
                .render(body)
                .map(response -> {
                    com.gl4a.gitlab.model.GitLabMarkdownResult result = response.body();
                    if (!response.isSuccessful() || result == null || result.html == null) {
                        throw new java.io.IOException("Markdown API " + response.code());
                    }
                    return result.html;
                })
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(html -> {
                    if (text.equals(mText)) {
                        showPage(html);
                    }
                }, error -> {
                    // Offline or refused: the in-app rendering, in the same page and style
                    if (text.equals(mText)) {
                        showLocalRendering(text);
                    }
                });
    }

    /** Shows GitLab's HTML in the shared page. */
    private void showPage(String html) {
        String base = com.gl4a.Gl4Application.get().getInstanceUrl() + "/";
        loadDataWithBaseURL(base, MarkdownStyle.webViewPage(getContext(), html, PREVIEW_CSS),
                "text/html", "utf-8", null);
    }

    /** The text rendered in the app (showdown.js), when GitLab's rendering isn't available. */
    private void showLocalRendering(String text) {
        addJavascriptInterface(new Base64JavascriptInterface(), "Base64");
        String page = MarkdownStyle.webViewPage(getContext(),
                "<div id='content'></div>"
                        + "<script src='file:///android_asset/showdown.js'></script>"
                        + "<script>var text = Base64.decode('" + StringUtils.toBase64(text)
                        + "');var converter = new showdown.Converter();"
                        + "converter.setFlavor('github');"
                        + "document.getElementById('content').innerHTML ="
                        + " converter.makeHtml(text);</script>",
                PREVIEW_CSS);
        loadDataWithBaseURL("file:///android_asset/", page, "text/html", "utf-8", null);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mRenderIfShown);
        if (mRendering != null) {
            mRendering.dispose();
        }
        super.onDetachedFromWindow();
    }

    private static class Base64JavascriptInterface {
        @JavascriptInterface
        public String decode(String base64) {
            return StringUtils.fromBase64(base64);
        }
    }
}
