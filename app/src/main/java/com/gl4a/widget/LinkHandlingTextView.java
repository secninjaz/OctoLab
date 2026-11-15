package com.gl4a.widget;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.net.Uri;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.TextView;
import android.widget.Toast;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.utils.IntentUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.fragment.app.FragmentActivity;

public class LinkHandlingTextView extends AppCompatTextView {

    private static final LinkMovementMethod CHECKING_LINK_METHOD = new LinkMovementMethod() {
        @Override
        public boolean onTouchEvent(@NonNull TextView widget,
                @NonNull Spannable buffer, @NonNull MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP
                    && openOwnInstanceLink(widget, buffer, event)) {
                return true;
            }
            try {
                return super.onTouchEvent(widget, buffer, event);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(widget.getContext(), R.string.link_not_openable, Toast.LENGTH_LONG)
                        .show();
                return true;
            } catch (SecurityException e) {
                // some apps have intent filters set for the VIEW action for
                // internal, non-exported activities
                // -> ignore
                return true;
            }
        }
    };

    /**
     * A link Android made from plain text (autoLink: a project's URL, a profile's website, a
     * commit message) to a server the user has an account on: opened as links in rendered text
     * are, keeping the active account. Android would otherwise hand it back to the app as a link
     * from another app, which asks which account to use (#332). Other links open as before.
     */
    private static boolean openOwnInstanceLink(TextView widget, Spannable buffer,
            MotionEvent event) {
        Layout layout = widget.getLayout();
        if (layout == null) return false;
        int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
        int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
        int offset = layout.getOffsetForHorizontal(layout.getLineForVertical(y), x);
        URLSpan[] spans = buffer.getSpans(offset, offset, URLSpan.class);
        if (spans.length == 0) return false;
        Uri uri = Uri.parse(spans[0].getURL());
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) return false;
        if (Gl4Application.get().getLoginsForInstanceHost(uri.getHost()).isEmpty()) return false;
        FragmentActivity activity = IntentUtils.findActivity(widget.getContext());
        if (activity == null) return false;
        Selection.removeSelection(buffer);
        IntentUtils.openLinkInternallyOrExternally(activity, uri);
        return true;
    }

    public LinkHandlingTextView(Context context) {
        super(context, null);
    }

    public LinkHandlingTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initAttributes(context, attrs, 0);
    }

    public LinkHandlingTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initAttributes(context, attrs, defStyle);
    }

    private void initAttributes(Context context, AttributeSet attrs, int defStyle) {
        Resources.Theme theme = context.getTheme();
        boolean needsLinkHandling = false;

        // If text is selectable, TextView.onTouchEvent() triggers a link click action,
        // which might cause double link open actions. Since we don't need auto-linkification
        // (we install our own movement method), just turn off auto-installation of
        // LinkMovementMethod
        setLinksClickable(false);

        TypedArray a = theme.obtainStyledAttributes(attrs, R.styleable.LinkHandlingTextView, defStyle, 0);
        for (int i = 0; i < a.getIndexCount(); i++) {
            int attr = a.getIndex(i);
            if (attr == R.styleable.LinkHandlingTextView_needsLinkHandling) {
                needsLinkHandling = a.getBoolean(attr, false);
            }
        }
        a.recycle();

        boolean isAutoLinkEnabled = getAutoLinkMask() > 0;
        if (needsLinkHandling || isAutoLinkEnabled) {
            setMovementMethod(CHECKING_LINK_METHOD);
        }
    }

    // workaround for https://code.google.com/p/android/issues/detail?id=208169
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isTextSelectable() && isEnabled()) {
            MovementMethod method = getMovementMethod();
            setMovementMethod(null);
            setMovementMethod(method);
        }
    }

    // workaround for https://code.google.com/p/android/issues/detail?id=191430
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int startSelection = getSelectionStart();
        int endSelection = getSelectionEnd();
        if (startSelection != endSelection && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            final CharSequence text = getText();
            setText(null);
            setText(text);
        }
        return super.dispatchTouchEvent(event);
    }
}
