package com.gl4a.widget;

import android.content.Context;
import android.util.SparseIntArray;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A vertical LinearLayoutManager whose scrollbar stays the same size while scrolling (#152).
 * LinearLayoutManager estimates the list's length from the rows on screen only, so with rows
 * as different as a one-line system note and a long comment with images, the scrollbar grew
 * and shrank at every scroll. This remembers every row's height once it has been laid out and
 * estimates only rows never seen, from the average of those that were.
 */
public class StableScrollbarLayoutManager extends LinearLayoutManager {
    /** Tells which rows take no space before they're laid out, e.g. collapsed thread replies. */
    public interface CollapsedRows {
        boolean isRowCollapsed(int position);
    }

    private final SparseIntArray mHeights = new SparseIntArray();
    private long mMeasuredTotal;
    private int mMeasuredCount;
    @Nullable private CollapsedRows mCollapsedRows;

    public StableScrollbarLayoutManager(Context context) {
        super(context);
    }

    /** Rows scrolled off that a list of comments keeps as they are (#256). */
    private static final int KEPT_ROWS = 10;

    /**
     * THE list setup for every screen of GitLab comments: issue and MR discussions, commit
     * comments and reviews (#258). Comments shown in the web view keep their bottom in place
     * at the top edge, recent rows come back without loading again, and the scrollbar keeps
     * its size. Call from createLayoutManager and onRecyclerViewInflated respectively.
     */
    public static StableScrollbarLayoutManager forComments(Context context,
            @Nullable CollapsedRows collapsedRows) {
        StableScrollbarLayoutManager layoutManager = new StableScrollbarLayoutManager(context);
        layoutManager.setCollapsedRows(collapsedRows);
        return layoutManager;
    }

    /** See {@link #forComments}. */
    public static void setUpCommentList(RecyclerView list) {
        list.setItemViewCacheSize(KEPT_ROWS);
    }

    public void setCollapsedRows(@Nullable CollapsedRows collapsedRows) {
        mCollapsedRows = collapsedRows;
    }

    /** Whether the row was just tapped, as HttpImageGetter marks it (#256). */
    private static boolean wasTapped(View row) {
        Object until = row.getTag(com.gl4a.R.id.row_tapped_until);
        return until instanceof Long && android.os.SystemClock.uptimeMillis() < (Long) until;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        View before = getChildCount() > 0 ? getChildAt(0) : null;
        int beforePos = before != null ? getPosition(before) : -1;
        int beforeTop = before != null ? before.getTop() : 0;
        int beforeH = before != null ? before.getHeight() : 0;
        super.onLayoutChildren(recycler, state);
        View after = getChildCount() > 0 ? getChildAt(0) : null;
        int afterPos = after != null ? getPosition(after) : -1;
        // The row at the top edge, partly above it, changed height without being tapped (its
        // content loaded or came back, e.g. a comment's web view): the list keeps that row's
        // top in place, so scrolling up jumped from the bottom being looked at to the
        // comment's top. Scroll by the change in this same layout, so its bottom stays put. A
        // section expanded by tap opens downwards as before (#256).
        if (after != null && !state.isPreLayout() && afterPos == beforePos
                && after.getTop() == beforeTop && beforeTop < getPaddingTop()
                && after.getHeight() != beforeH && !wasTapped(after)) {
            int delta = after.getHeight() - beforeH;
            // Shrinking moves it down: never past the top edge
            delta = Math.max(delta, beforeTop - getPaddingTop());
            if (delta != 0) {
                scrollVerticallyBy(delta, recycler, state);
            }
        }
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        recordHeights();
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler,
            RecyclerView.State state) {
        int scrolled = super.scrollVerticallyBy(dy, recycler, state);
        recordHeights();
        return scrolled;
    }

    // Rows moved or were replaced: the remembered heights no longer match their positions
    @Override
    public void onItemsAdded(RecyclerView recyclerView, int positionStart, int itemCount) {
        clearHeights();
    }

    @Override
    public void onItemsRemoved(RecyclerView recyclerView, int positionStart, int itemCount) {
        clearHeights();
    }

    @Override
    public void onItemsMoved(RecyclerView recyclerView, int from, int to, int itemCount) {
        clearHeights();
    }

    @Override
    public void onItemsChanged(RecyclerView recyclerView) {
        clearHeights();
    }

    @Override
    public void onAdapterChanged(@Nullable RecyclerView.Adapter oldAdapter,
            @Nullable RecyclerView.Adapter newAdapter) {
        super.onAdapterChanged(oldAdapter, newAdapter);
        clearHeights();
    }

    @Override
    public int computeVerticalScrollRange(RecyclerView.State state) {
        int count = state.getItemCount();
        if (getChildCount() == 0 || count == 0) {
            return super.computeVerticalScrollRange(state);
        }
        return (int) Math.min(Integer.MAX_VALUE, heightOfRows(0, count));
    }

    @Override
    public int computeVerticalScrollOffset(RecyclerView.State state) {
        View first = getChildAt(0);
        if (first == null || state.getItemCount() == 0) {
            return super.computeVerticalScrollOffset(state);
        }
        int position = getPosition(first);
        long above = heightOfRows(0, position);
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0, above + getPaddingTop() - getDecoratedTop(first)));
    }

    @Override
    public int computeVerticalScrollExtent(RecyclerView.State state) {
        if (getChildCount() == 0 || state.getItemCount() == 0) {
            return super.computeVerticalScrollExtent(state);
        }
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }

    private void recordHeights() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null) continue;
            int position = getPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) child.getLayoutParams();
            int height = getDecoratedMeasuredHeight(child) + lp.topMargin + lp.bottomMargin;
            int previous = mHeights.get(position, -1);
            if (previous == height) continue;
            mHeights.put(position, height);
            // The estimate for unseen rows averages visible rows only: hidden ones are 0
            if (previous > 0) {
                mMeasuredTotal -= previous;
                mMeasuredCount--;
            }
            if (height > 0) {
                mMeasuredTotal += height;
                mMeasuredCount++;
            }
        }
    }

    private void clearHeights() {
        mHeights.clear();
        mMeasuredTotal = 0;
        mMeasuredCount = 0;
    }

    /** Total height of rows [start, end): remembered heights, or an estimate for the rest. */
    private long heightOfRows(int start, int end) {
        long estimate = mMeasuredCount > 0 ? mMeasuredTotal / mMeasuredCount : 0;
        long total = 0;
        for (int position = start; position < end; position++) {
            int height = mHeights.get(position, -1);
            if (height >= 0) {
                total += height;
            } else if (mCollapsedRows == null || !mCollapsedRows.isRowCollapsed(position)) {
                total += estimate;
            }
        }
        return total;
    }
}
