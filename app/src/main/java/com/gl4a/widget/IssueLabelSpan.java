package com.gl4a.widget;
import com.gl4a.gitlab.model.GitLabLabel;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import android.text.style.ReplacementSpan;

import com.gl4a.R;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.UiUtils;

/**
 * A label as GitLab shows it (#341): a pill in the label's colour; a scoped label
 * ("bug::functional") in two parts, its scope on the colour and its value on the page's
 * background inside a border of the colour.
 */
public class IssueLabelSpan extends ReplacementSpan {
    private static final String SCOPE = "::";

    private final RectF mTmpRect = new RectF();
    private final Path mTmpPath = new Path();
    private final int mFgColor;
    private final int mBgColor;
    // A scoped label's value part: the page's background and text colours
    private final int mValueBgColor;
    private final int mValueFgColor;
    private final int mPadding;
    private final float mBorder;
    private final int mRightAndBottomMargin;
    private final float mTextSize;

    private int mAscent;
    private int mDescent;

    public IssueLabelSpan(Context context, GitLabLabel label, boolean withMargin) {
        super();
        mBgColor = ApiHelpers.colorForLabel(label);
        mFgColor = UiUtils.textColorForBackground(context, mBgColor);
        mValueBgColor = themeColor(context, android.R.attr.colorBackground);
        mValueFgColor = themeColor(context, android.R.attr.textColorPrimary);

        Resources res = context.getResources();
        mPadding = res.getDimensionPixelSize(R.dimen.issue_label_padding);
        mBorder = res.getDimension(R.dimen.issue_label_scoped_border);
        mRightAndBottomMargin =
                withMargin ? res.getDimensionPixelSize(R.dimen.issue_label_margin) : 0;
        mTextSize = res.getDimension(R.dimen.issue_label_text_size);
    }

    private static int themeColor(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return 0;
        if (value.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColorStateList(context,
                    value.resourceId).getDefaultColor();
        }
        return value.data;
    }

    /** Where the scope ends in the text, or -1 for a plain label (the last "::", as GitLab). */
    private static int scopeEnd(CharSequence text, int start, int end) {
        String name = text.subSequence(start, end).toString();
        int at = name.lastIndexOf(SCOPE);
        return at > 0 && at + SCOPE.length() < name.length() ? start + at : -1;
    }

    /** Each part's width: its text and padding on both sides. */
    private float partWidth(Paint paint, CharSequence text, int start, int end) {
        return paint.measureText(text, start, end) + 2 * mPadding;
    }

    private float labelWidth(Paint paint, CharSequence text, int start, int end) {
        int scope = scopeEnd(text, start, end);
        // A plain label with the same room at its ends as a scoped one (#341)
        if (scope < 0) {
            return scopeLeft() + paint.measureText(text, start, end) + plainRight();
        }
        // The scope and the value each with room on both sides, a bit more where the pill
        // rounds (#341)
        return scopeLeft() + paint.measureText(text, start, scope) + scopeRight()
                + valueLeft() + paint.measureText(text, scope + SCOPE.length(), end)
                + valueRight();
    }

    private float scopeLeft() {
        return 3 * mPadding;
    }

    private float scopeRight() {
        return 2 * mPadding;
    }

    /** A plain label's right end: as a scoped one's, which also has its border there. */
    private float plainRight() {
        return valueRight() - mBorder;
    }

    private float valueLeft() {
        return 2 * mPadding;
    }

    private float valueRight() {
        return 3 * mPadding + mBorder;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text,
            int start, int end, Paint.FontMetricsInt fm) {
        paint.setTextSize(mTextSize);
        if (fm != null) {
            paint.getFontMetricsInt(fm);
            mAscent = -fm.ascent;
            mDescent = fm.descent;
            fm.top -= mPadding;
            fm.ascent -= mPadding;
            fm.bottom += mPadding + mRightAndBottomMargin;
            fm.descent += mPadding + mRightAndBottomMargin;
        }
        return (int) Math.ceil(labelWidth(paint, text, start, end)) + mRightAndBottomMargin;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
            float x, int top, int y, int bottom, @NonNull Paint paint) {
        paint.setTextSize(mTextSize);
        final float bgLeft = x;
        final float bgRight = bgLeft + labelWidth(paint, text, start, end);
        final float bgTop = y - mAscent - mPadding;
        final float bgBottom = y + mDescent + mPadding;
        // A pill, as GitLab's labels (#341)
        final float radius = (bgBottom - bgTop) / 2;

        paint.setColor(mBgColor);
        mTmpRect.set(bgLeft, bgTop, bgRight, bgBottom);
        canvas.drawRoundRect(mTmpRect, radius, radius, paint);

        int scope = scopeEnd(text, start, end);
        float textX = x + scopeLeft();
        if (scope < 0) {
            paint.setColor(mFgColor);
            canvas.drawText(text, start, end, textX, y, paint);
            return;
        }

        float split = textX + paint.measureText(text, start, scope) + scopeRight();
        // The value on the page's background, inside the colour's border, right end rounded
        float inner = radius - mBorder;
        mTmpPath.reset();
        mTmpRect.set(split, bgTop + mBorder, bgRight - mBorder, bgBottom - mBorder);
        mTmpPath.addRoundRect(mTmpRect,
                new float[] { 0, 0, inner, inner, inner, inner, 0, 0 }, Path.Direction.CW);
        paint.setColor(mValueBgColor);
        canvas.drawPath(mTmpPath, paint);

        paint.setColor(mFgColor);
        canvas.drawText(text, start, scope, textX, y, paint);
        paint.setColor(mValueFgColor);
        canvas.drawText(text, scope + SCOPE.length(), end, split + valueLeft(), y, paint);
    }
}
