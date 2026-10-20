package com.gl4a.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;

/**
 * The vertical timeline line of GitLab web's issue/MR activity (#179): drawn down the centre
 * of the avatar column behind every row except the header (the description), so avatars and
 * the system-note dots sit on it.
 */
public class TimelineLineDecoration extends RecyclerView.ItemDecoration {
    /** Rows the line doesn't run through, e.g. the feed's project headers (#287). */
    public interface Skip {
        boolean skip(int adapterPosition);
    }

    private final Paint mPaint = new Paint();
    private final float mX;
    private final Skip mSkip;

    public TimelineLineDecoration(Context context, boolean hasHeader) {
        this(context, context.getResources().getDimensionPixelSize(R.dimen.timeline_side_padding)
                + context.getResources().getDimensionPixelSize(R.dimen.timeline_avatar_size) / 2f,
                hasHeader ? (Skip) position -> position == 0 : null);
    }

    /**
     * The feed's line (#287): through the centre of row_feed_item's avatar.
     *
     * @param skip rows without the line; null for none
     */
    public static TimelineLineDecoration forFeed(Context context, @androidx.annotation.Nullable
            Skip skip) {
        return new TimelineLineDecoration(context,
                context.getResources().getDimensionPixelSize(R.dimen.feed_side_padding)
                + context.getResources().getDimensionPixelSize(R.dimen.feed_avatar_size) / 2f,
                skip);
    }

    private TimelineLineDecoration(Context context, float x, Skip skip) {
        mPaint.setColor(ContextCompat.getColor(context, R.color.timeline_line));
        mPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2);
        mX = x;
        mSkip = skip;
    }

    @Override
    public void onDraw(Canvas c, RecyclerView parent, RecyclerView.State state) {
        float x = parent.getPaddingLeft() + mX;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION
                    || (mSkip != null && mSkip.skip(position))) continue;
            c.drawLine(x, child.getTop() + child.getTranslationY(), x,
                    child.getBottom() + child.getTranslationY(), mPaint);
        }
    }
}
