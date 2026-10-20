package com.gl4a.widget;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * THE sticky group headers of a list (#288), e.g. To-dos and Participating grouped by project:
 * the header of the group at the top stays pinned there, semi-transparent, pushed out by the
 * next one. It's a real header row made by the list's adapter, so its buttons (expand,
 * mark all read, the logo) work as on the header in the list; its bar (e.g. the project bar,
 * which expands and collapses the group in the list) goes back to the group's top instead
 * (#290). The header in the list is hidden while the pinned one covers it, so it doesn't show
 * through (#290).
 */
public final class StickyHeaders {
    /** Rows that are group headers. */
    public interface Headers {
        boolean isHeaderAt(int adapterPosition);
    }

    /** The pinned header's opacity: the rows show faintly, its text stays readable (#288). */
    private static final int ALPHA = Math.round(255 * 0.85f);

    private final RecyclerView mList;
    private final Headers mHeaders;
    private final int mBarId;
    private RecyclerView.ViewHolder mHolder;
    private int mHolderType = -1;
    // The pinned header's position in the list, -1 while none is pinned
    private int mPinned = -1;

    private StickyHeaders(RecyclerView list, Headers headers, @IdRes int barId) {
        mList = list;
        mHeaders = headers;
        mBarId = barId;
    }

    /**
     * Pins the list's group headers; the list must sit in a FrameLayout (generic_list).
     *
     * @param barId the header's view that, pinned, goes back to the group's top; 0 for none
     */
    public static void attach(RecyclerView list, Headers headers, @IdRes int barId) {
        if (!(list.getParent() instanceof FrameLayout)) return;
        StickyHeaders sticky = new StickyHeaders(list, headers, barId);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                sticky.update();
            }
        });
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(sticky::update));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void update() {
        RecyclerView.Adapter adapter = mList.getAdapter();
        RecyclerView.LayoutManager lm = mList.getLayoutManager();
        if (adapter == null || !(lm instanceof LinearLayoutManager)) {
            hide();
            return;
        }
        int first = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) {
            hide();
            return;
        }
        int header = first;
        while (header >= 0 && !mHeaders.isHeaderAt(header)) header--;
        View firstView = lm.findViewByPosition(first);
        if (header < 0 || (header == first && firstView != null && firstView.getTop() >= 0)) {
            // No group yet, or its own header is fully in view
            hide();
            return;
        }

        int type = adapter.getItemViewType(header);
        if (mHolder == null || mHolderType != type) {
            if (mHolder != null) ((ViewGroup) mList.getParent()).removeView(mHolder.itemView);
            mHolder = adapter.onCreateViewHolder(mList, type);
            mHolderType = type;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP);
            ((ViewGroup) mList.getParent()).addView(mHolder.itemView,
                    ((ViewGroup) mList.getParent()).indexOfChild(mList) + 1, lp);
            makeSemiTransparent(mHolder.itemView);
            View bar = mBarId != 0 ? mHolder.itemView.findViewById(mBarId) : null;
            if (bar != null) {
                // Back to the group's top; the group stays as it is (#290)
                bar.setOnClickListener(v -> {
                    if (mPinned >= 0) {
                        ((LinearLayoutManager) mList.getLayoutManager())
                                .scrollToPositionWithOffset(mPinned, 0);
                    }
                });
            }
        }
        adapter.onBindViewHolder(mHolder, header);
        mPinned = header;
        showListHeaders();
        View pinned = mHolder.itemView;
        pinned.setVisibility(View.VISIBLE);

        // Pushed out by the next group's header
        int height = pinned.getHeight();
        float translation = 0;
        for (int i = 0; i < mList.getChildCount(); i++) {
            View child = mList.getChildAt(i);
            int position = mList.getChildAdapterPosition(child);
            if (position > header && mHeaders.isHeaderAt(position)) {
                if (height > 0 && child.getTop() < height) {
                    translation = child.getTop() - height;
                }
                break;
            }
        }
        pinned.setTranslationY(translation);
    }

    private void hide() {
        if (mHolder != null) mHolder.itemView.setVisibility(View.GONE);
        mPinned = -1;
        showListHeaders();
    }

    /** The headers in the list, but the one the pinned header covers (#290). */
    private void showListHeaders() {
        for (int i = 0; i < mList.getChildCount(); i++) {
            View child = mList.getChildAt(i);
            int position = mList.getChildAdapterPosition(child);
            if (position != RecyclerView.NO_POSITION && mHeaders.isHeaderAt(position)) {
                child.setAlpha(position == mPinned ? 0f : 1f);
            } else if (child.getAlpha() == 0f) {
                // A covered header's row, reused for another item
                child.setAlpha(1f);
            }
        }
    }

    private static void makeSemiTransparent(View view) {
        Drawable background = view.getBackground();
        if (background != null) {
            background.mutate().setAlpha(ALPHA);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                // Only the backgrounds: the logo, text and buttons stay fully opaque
                if (child instanceof ViewGroup) {
                    makeSemiTransparent(child);
                } else if (child.getBackground() != null && !child.isClickable()) {
                    child.getBackground().mutate().setAlpha(ALPHA);
                }
            }
        }
    }
}
