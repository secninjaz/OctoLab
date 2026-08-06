package com.gl4a.widget;
import com.gl4a.gitlab.model.GitLabReaction;
import com.gl4a.gitlab.model.GitLabReactions;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Parcelable;
import androidx.annotation.ColorInt;
import androidx.annotation.IdRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.appcompat.widget.PopupMenu;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.activities.UserActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.UiUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.reactivex.Single;

public class ReactionBar extends HorizontalScrollView implements View.OnClickListener {
    public interface Item {
        Object getCacheKey();
    }
    public interface Callback {
        boolean canAddReaction();
        Single<List<GitLabReaction>> loadReactionDetails(Item item, boolean bypassCache);
        Single<GitLabReaction> addReaction(Item item, String content);
        Single<Boolean> deleteReaction(Item item, long reactionId);
    }

    private static final @IdRes int[] REACTION_VIEW_IDS = {
        R.id.plus_one, R.id.minus_one, R.id.laugh,
        R.id.hooray, R.id.heart, R.id.confused,
        R.id.rocket, R.id.eyes
    };
    private static final String[] REACTION_CONTENTS = {
        GitLabReaction.CONTENT_PLUS_ONE, GitLabReaction.CONTENT_MINUS_ONE,
        GitLabReaction.CONTENT_LAUGH, GitLabReaction.CONTENT_HOORAY,
        GitLabReaction.CONTENT_HEART, GitLabReaction.CONTENT_CONFUSED,
        GitLabReaction.CONTENT_ROCKET, GitLabReaction.CONTENT_EYES
    };

    private final TextView mPlusOneView;
    private final TextView mMinusOneView;
    private final TextView mLaughView;
    private final TextView mHoorayView;
    private final TextView mConfusedView;
    private final TextView mHeartView;
    private final TextView mRocketView;
    private final TextView mEyesView;
    private final View mReactButton;

    private Callback mCallback;
    private Item mReferenceItem;
    private ReactionUserPopup mPopup;

    private ReactionDetailsCache mDetailsCache;
    private MenuPopupHelper mAddReactionPopup;
    private @MenuRes int mAddReactionMenuResId = R.menu.reaction_menu;
    private AddReactionMenuHelper mAddReactionMenuHelper;
    private java.util.Set<String> mViewerReactedContents;

    public ReactionBar(Context context) {
        this(context, null);
    }

    public ReactionBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ReactionBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        inflate(context, R.layout.reaction_bar, this);

        mPlusOneView = findViewById(R.id.plus_one);
        mMinusOneView = findViewById(R.id.minus_one);
        mLaughView = findViewById(R.id.laugh);
        mHoorayView = findViewById(R.id.hooray);
        mConfusedView = findViewById(R.id.confused);
        mHeartView = findViewById(R.id.heart);
        mRocketView = findViewById(R.id.rocket);
        mEyesView = findViewById(R.id.eyes);
        mReactButton = findViewById(R.id.react);

        setReactions(null);
    }

    public void setReactions(GitLabReactions reactions) {
        if (mPopup != null) {
            mPopup.update();
        }
        if (mAddReactionMenuHelper != null) {
            mAddReactionMenuHelper.updateMenuItems();
        }
        if (reactions != null && reactions.totalCount() > 0) {
            updateView(mPlusOneView, reactions.plusOne());
            updateView(mMinusOneView, reactions.minusOne());
            updateView(mLaughView, reactions.laugh());
            updateView(mHoorayView, reactions.hooray());
            updateView(mConfusedView, reactions.confused());
            updateView(mHeartView, reactions.heart());
            updateView(mRocketView, reactions.rocket());
            updateView(mEyesView, reactions.eyes());
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }
        applyChipActiveStates();
    }

    /** Sets which emoji contents the current viewer has reacted with and updates chip tinting. */
    public void setViewerReactedContents(@Nullable java.util.Set<String> contents) {
        mViewerReactedContents = contents;
        applyChipActiveStates();
    }

    private void applyChipActiveStates() {
        // Synchronous when already laid out (normal scroll/rebind path) to avoid async
        // races during fast RecyclerView recycling. Deferred only before first layout.
        if (isLaidOut()) {
            refreshChipActiveStates();
        } else {
            post(this::refreshChipActiveStates);
        }
    }

    /**
     * Re-reads the viewer's reacted emoji from the details cache and refreshes chip tinting.
     * Call this after a reaction add/remove to keep chip colours in sync without a full reload.
     */
    public void refreshViewerStateFromCache() {
        if (mDetailsCache == null || mReferenceItem == null) return;
        java.util.List<GitLabReaction> details = mDetailsCache.getReactions(mReferenceItem);
        if (details == null) return;
        String ownLogin = Gl4Application.get().getAuthLogin();
        java.util.Set<String> viewerReacted = new java.util.HashSet<>();
        for (GitLabReaction r : details) {
            if (ApiHelpers.loginEquals(r.user(), ownLogin)) viewerReacted.add(r.content());
        }
        setViewerReactedContents(viewerReacted);
    }

    private void refreshChipActiveStates() {
        if (getVisibility() != View.VISIBLE) return;
        @ColorInt int accentColor =
                UiUtils.resolveColor(getContext(), androidx.appcompat.R.attr.colorAccent);
        @ColorInt int defaultColor =
                UiUtils.resolveColor(getContext(), android.R.attr.textColorSecondary);
        for (int i = 0; i < REACTION_VIEW_IDS.length; i++) {
            TextView chip = (TextView) findViewById(REACTION_VIEW_IDS[i]);
            if (chip.getVisibility() != View.VISIBLE) continue;
            boolean active = mViewerReactedContents != null
                    && mViewerReactedContents.contains(REACTION_CONTENTS[i]);
            @ColorInt int tint = active ? accentColor : defaultColor;
            chip.setTextColor(tint);
            // Tint the drawableLeft icon directly; mutate() isolates shared drawable state.
            for (Drawable d : chip.getCompoundDrawables()) {
                if (d != null) d.mutate().setTint(tint);
            }
        }
    }

    public void setDetailsCache(ReactionDetailsCache cache) {
        mDetailsCache = cache;
    }

    public void setCallback(Callback callback, Item item) {
        mCallback = callback;
        mReferenceItem = item;

        for (int id : REACTION_VIEW_IDS) {
            findViewById(id).setOnClickListener(callback != null ? this : null);
        }
        boolean isUserLoggedIn = Gl4Application.get().isAuthorized();
        mReactButton.setVisibility(isUserLoggedIn && callback != null && callback.canAddReaction()
                ? View.VISIBLE : View.GONE);
        mReactButton.setOnClickListener(callback != null ? this : null);
    }

    public void setAddReactionPopupMenu(@MenuRes int menuResId) {
        mAddReactionMenuResId = menuResId;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mPopup != null) {
            mPopup.dismiss();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        if (mPopup != null) {
            mPopup.dismiss();
        }
        return super.onSaveInstanceState();
    }

    @Override
    public void onClick(View view) {
        if (mPopup != null && mPopup.isShowing()) {
            mPopup.dismiss();
            return;
        }
        if (view == mReactButton) {
            if (mAddReactionPopup == null) {
                PopupMenu popup = new PopupMenu(getContext(), mReactButton);
                popup.inflate(mAddReactionMenuResId);
                popup.setOnMenuItemClickListener(item -> mAddReactionMenuHelper.onItemClick(item));
                mAddReactionMenuHelper = new AddReactionMenuHelper(getContext(), popup.getMenu(),
                        mCallback, mReferenceItem, mDetailsCache);

                mAddReactionPopup = new MenuPopupHelper(getContext(), (MenuBuilder) popup.getMenu(), mReactButton);
                mAddReactionPopup.setForceShowIcon(true);
            }
            mAddReactionMenuHelper.startLoadingIfNeeded();
            mAddReactionPopup.show();
            return;
        }
        for (int i = 0; i < REACTION_VIEW_IDS.length; i++) {
            if (view.getId() == REACTION_VIEW_IDS[i]) {
                if (mPopup == null) {
                    mPopup = new ReactionUserPopup(getContext(),
                            mCallback, mReferenceItem, mDetailsCache);
                }
                mPopup.setAnchorView(view);
                mPopup.show(REACTION_CONTENTS[i]);
            }
        }
    }

    private void updateView(TextView view, int count) {
        if (count > 0) {
            view.setText(String.valueOf(count));
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    private static String getReactionContentByViewId(@IdRes int reactionViewId) {
        for (int i = 0; i < REACTION_VIEW_IDS.length; i++) {
            if (REACTION_VIEW_IDS[i] == reactionViewId) {
                return REACTION_CONTENTS[i];
            }
        }
        return null;
    }

    private static class ReactionUserPopup extends ListPopupWindow {
        private final Callback mCallback;
        private final Item mItem;
        private List<GitLabReaction> mLastKnownDetails;
        private final ReactionDetailsCache mDetailsCache;
        private final ReactionUserAdapter mAdapter;
        private String mContent;

        public ReactionUserPopup(@NonNull Context context, Callback callback,
                Item item, ReactionDetailsCache detailsCache) {
            super(context);

            mCallback = callback;
            mItem = item;
            mDetailsCache = detailsCache;
            mAdapter = new ReactionUserAdapter(context, this);
            setContentWidth(
                    context.getResources()
                            .getDimensionPixelSize(R.dimen.reaction_details_popup_width));
            setAdapter(mAdapter);
        }

        public void update() {
            List<GitLabReaction> details = mDetailsCache.getReactions(mItem);
            if (details != null) {
                populateAdapter(details);
            }
        }

        public void show(String content) {
            if (!TextUtils.equals(content, mContent)) {
                mAdapter.setReactions(null);
                mContent = content;
            }
            show();

            List<GitLabReaction> details = mDetailsCache.getReactions(mItem);
            if (details != null) {
                populateAdapter(details);
            } else {
                fetchReactions(mCallback, mItem, mDetailsCache)
                        .subscribe(this::populateAdapter, error -> {
                            Log.d(Gl4Application.LOG_TAG, "Fetching reactions failed", error);
                            dismiss();
                        });
            }
        }

        public void toggleOwnReaction(GitLabReaction currentReaction) {
            Long id = currentReaction != null ? currentReaction.id() : null;
            toggleReaction(mContent, id, mLastKnownDetails, mCallback, mItem, mDetailsCache)
                    .subscribe(result -> dismiss(), error -> {
                        Log.d(Gl4Application.LOG_TAG, "Toggling reaction failed", error);
                        dismiss();
                    });
        }

        public boolean canAddReaction() {
            return mCallback.canAddReaction();
        }

        private void populateAdapter(List<GitLabReaction> details) {
            List<GitLabReaction> reactions = new ArrayList<>();
            for (GitLabReaction reaction : details) {
                if (TextUtils.equals(mContent, reaction.content())) {
                    reactions.add(reaction);
                }
            }
            mLastKnownDetails = details;
            mAdapter.setReactions(reactions);
        }
    }

    private static class ReactionUserAdapter extends BaseAdapter implements View.OnClickListener {
        private final Context mContext;
        private final ReactionUserPopup mParent;
        private final LayoutInflater mInflater;
        private List<GitLabUser> mUsers;
        private GitLabReaction mOwnReaction;

        public ReactionUserAdapter(Context context, ReactionUserPopup popup) {
            mContext = context;
            mParent = popup;
            mInflater = LayoutInflater.from(context);
        }

        public void setReactions(List<GitLabReaction> reactions) {
            mOwnReaction = null;
            if (reactions != null) {
                GitLabUser ownUser = Gl4Application.get().getCurrentAccountInfoForAvatar();
                String ownLogin = ownUser != null ? ownUser.login() : null;

                mUsers = new ArrayList<>();
                for (GitLabReaction reaction : reactions) {
                    if (ApiHelpers.loginEquals(reaction.user(), ownLogin)) {
                        mOwnReaction = reaction;
                    } else {
                        mUsers.add(reaction.user());
                    }
                }
                if (ownUser != null) {
                    if (mParent.canAddReaction()) {
                        if (!mUsers.isEmpty()) {
                            mUsers.add(null);
                        }
                        mUsers.add(ownUser);
                    } else if (mOwnReaction != null) {
                        mUsers.add(0, ownUser);
                    }
                }
            } else {
                mUsers = null;
            }

            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mUsers != null ? mUsers.size() : 1;
        }

        @Override
        public int getItemViewType(int position) {
            if (mUsers == null) {
                return 1;
            }
            return getItem(position) == null ? 2 : 0;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public Object getItem(int position) {
            return mUsers != null ? mUsers.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int viewType = getItemViewType(position);
            @LayoutRes int layoutResId =
                    viewType == 0 ? R.layout.row_reaction_details :
                    viewType == 1 ? R.layout.reaction_details_progress :
                    R.layout.reaction_details_divider;

            if (convertView == null) {
                convertView = mInflater.inflate(layoutResId, parent, false);
            }

            if (viewType == 0) {
                ImageView avatar = convertView.findViewById(R.id.avatar);
                TextView name = convertView.findViewById(R.id.name);
                String ownLogin = Gl4Application.get().getAuthLogin();
                GitLabUser user = mUsers.get(position);

                AvatarHandler.assignAvatar(avatar, user);
                convertView.setOnClickListener(this);

                if (ApiHelpers.loginEquals(user, ownLogin) && mParent.canAddReaction()) {
                    avatar.setAlpha(mOwnReaction != null ? 1.0f : 0.4f);
                    name.setText(mOwnReaction != null
                            ? R.string.remove_reaction : R.string.add_reaction);
                    convertView.setTag(mOwnReaction);
                } else {
                    avatar.setAlpha(1.0f);
                    name.setText(ApiHelpers.getUserLoginWithType(mContext, user));
                    convertView.setTag(user);
                }
            }

            return convertView;
        }

        @Override
        public void onClick(View view) {
            if (view.getTag() instanceof GitLabUser) {
                GitLabUser user = (GitLabUser) view.getTag();
                mParent.dismiss();
                mContext.startActivity(UserActivity.makeIntent(mContext, user));
            } else {
                // own entry
                mParent.toggleOwnReaction(mOwnReaction);
            }
        }
    }

    private static Single<List<GitLabReaction>> fetchReactions(Callback callback, Item item,
            ReactionDetailsCache cache) {
        return callback.loadReactionDetails(item, false)
                .compose(RxUtils::doInBackground)
                .compose(RxUtils.sortList((lhs, rhs) -> {
                    int result = lhs.content().compareTo(rhs.content());
                    if (result == 0) {
                        // createdAt() is nullable — guard before comparing
                        java.util.Date l = lhs.createdAt(), r = rhs.createdAt();
                        if (l != null && r != null) result = r.compareTo(l);
                    }
                    return result;
                }))
                .doOnSuccess(reactions -> cache.putReactions(item, reactions));
    }

    private static Single<Optional<GitLabReaction>> toggleReaction(String content, Long id,
            List<GitLabReaction> existingDetails, Callback callback, Item item,
            ReactionDetailsCache cache) {
        final Single<Optional<GitLabReaction>> resultSingle;

        if (id == null) {
            resultSingle = callback.addReaction(item, content)
                    .map(Optional::of);
        } else {
            resultSingle = callback.deleteReaction(item, id)
                    .map(response -> Optional.empty());
        }

        return resultSingle
                .compose(RxUtils::doInBackground)
                .doOnSuccess(reactionOpt -> {
                    if (reactionOpt.isPresent()) {
                        existingDetails.add(reactionOpt.get());
                    } else {
                        for (int i = 0; i < existingDetails.size(); i++) {
                            GitLabReaction reaction = existingDetails.get(i);
                            if (Long.valueOf(reaction.id()).equals(id)) {
                                existingDetails.remove(i);
                                break;
                            }
                        }
                    }
                    cache.putReactions(item, existingDetails);
                });
    }

    public static class AddReactionMenuHelper {
        private final Context mContext;
        private MenuItem mLoadingItem;
        private final List<MenuItem> mReactionMenuItems = new ArrayList<>();
        private final ReactionDetailsCache mDetailsCache;
        private final Map<String, Long> mUserOwnReactions = new HashMap<>();
        private final Callback mCallback;
        private final Item mItem;
        private boolean mLoading;

        public AddReactionMenuHelper(@NonNull Context context, Menu menu,
                Callback callback, Item item, ReactionDetailsCache detailsCache) {
            mContext = context;
            mCallback = callback;
            mItem = item;
            mDetailsCache = detailsCache;

            initializeMenuItems(menu);
        }

        private void initializeMenuItems(Menu menu) {
            mLoadingItem = menu.findItem(R.id.loading);

            for (int reactionViewId : REACTION_VIEW_IDS) {
                var reactionMenuItem = menu.findItem(reactionViewId);
                if (reactionMenuItem != null) {
                    mReactionMenuItems.add(reactionMenuItem);
                    Drawable icon = DrawableCompat.wrap(reactionMenuItem.getIcon().mutate());
                    DrawableCompat.setTintMode(icon, PorterDuff.Mode.SRC_ATOP);
                    reactionMenuItem.setIcon(icon);
                }
            }
        }

        public boolean onItemClick(MenuItem clickedItem) {
            for (MenuItem item : mReactionMenuItems) {
                if (clickedItem == item) {
                    String reactionContent = getReactionContentByViewId(item.getItemId());
                    Long userReactionId = mUserOwnReactions.get(reactionContent);
                    addOrRemoveReaction(reactionContent, userReactionId);
                    return true;
                }
            }
            return false;
        }

        public void updateMenuItems() {
            mUserOwnReactions.clear();
            List<GitLabReaction> reactions = mDetailsCache.getReactions(mItem);
            if (reactions == null) {
                setReactionMenuItemsVisible(false);
                return;
            }

            String ownLogin = Gl4Application.get().getAuthLogin();
            reactions.stream()
                    .filter(reaction -> ApiHelpers.loginEquals(reaction.user(), ownLogin))
                    .forEach(reaction -> mUserOwnReactions.put(reaction.content(), reaction.id()));
            setReactionMenuItemsVisible(true);
        }

        public void startLoadingIfNeeded() {
            if (mDetailsCache.hasEntryFor(mItem)) {
                updateMenuItems();
            } else if (!mLoading) {
                fetchReactions(mCallback, mItem, mDetailsCache)
                        .doOnSubscribe(disposable -> mLoading = true)
                        .doOnSuccess(result -> mLoading = false)
                        .doOnError(error -> mLoading = false)
                        .subscribe(reactions -> updateMenuItems(), error -> {
                            Log.d(Gl4Application.LOG_TAG, "Fetching reactions failed", error);
                            updateMenuItems();
                        });
            }
        }

        private void setReactionMenuItemsVisible(boolean visible) {
            mLoadingItem.setVisible(!visible);
            // Apply icon tints BEFORE making items visible so the ListView never
            // renders a frame with items shown but icons uninitialized.
            if (visible) updateCheckedStates();
            for (MenuItem item : mReactionMenuItems) {
                item.setVisible(visible);
            }
            if (!visible) updateCheckedStates();
        }

        private void updateCheckedStates() {
            for (MenuItem item : mReactionMenuItems) {
                var reactionContent = getReactionContentByViewId(item.getItemId());
                item.setChecked(mUserOwnReactions.containsKey(reactionContent));
            }
            updateDrawableColors();
        }

        private void updateDrawableColors() {
            @ColorInt int accentColor = UiUtils.resolveColor(mContext, androidx.appcompat.R.attr.colorAccent);
            @ColorInt int secondaryColor = UiUtils.resolveColor(mContext,
                    android.R.attr.textColorSecondary);
            for (MenuItem item : mReactionMenuItems) {
                DrawableCompat.setTint(item.getIcon(),
                        item.isChecked() ? accentColor : secondaryColor);
            }
        }

        private void addOrRemoveReaction(final String content, final Long id) {
            var currentReactions = new ArrayList<>(mDetailsCache.getReactions(mItem));
            toggleReaction(content, id, currentReactions, mCallback, mItem, mDetailsCache)
                    .subscribe(addedReaction -> {
                        if (addedReaction.isPresent()) {
                            mUserOwnReactions.put(content, addedReaction.get().id());
                        } else {
                            mUserOwnReactions.remove(content);
                        }
                    }, error -> Log.d(Gl4Application.LOG_TAG, "Changing reaction failed", error));
        }
    }

    public static class ReactionDetailsCache {
        public interface Listener {
            void onReactionsUpdated(Item item, GitLabReactions reactions);
        }

        private final Listener mListener;
        private boolean mDestroyed;
        private final HashMap<Object, List<GitLabReaction>> mMap = new HashMap<>();

        public ReactionDetailsCache(Listener listener) {
            super();
            mListener = listener;
        }

        public void destroy() {
            mDestroyed = true;
        }

        public void clear() {
            mMap.clear();
        }

        public boolean hasEntryFor(Item item) {
            return mMap.containsKey(item.getCacheKey());
        }

        public List<GitLabReaction> getReactions(Item item) {
            return mMap.get(item.getCacheKey());
        }

        public void putReactions(Item item, List<GitLabReaction> value) {
            Object key = item.getCacheKey();
            List<GitLabReaction> result = mMap.put(key, new ArrayList<>(value));
            if (result != null && !mDestroyed) {
                mListener.onReactionsUpdated(item, buildReactions(value));
            }
        }

        private GitLabReactions buildReactions(List<GitLabReaction> reactions) {
            int plusOne = 0, minusOne = 0, confused = 0, heart = 0;
            int hooray = 0, laugh = 0, rocket = 0, eyes = 0;
            for (GitLabReaction reaction : reactions) {
                switch (reaction.content()) {
                    case GitLabReaction.CONTENT_PLUS_ONE: ++plusOne; break;
                    case GitLabReaction.CONTENT_MINUS_ONE: ++minusOne; break;
                    case GitLabReaction.CONTENT_CONFUSED: ++confused; break;
                    case GitLabReaction.CONTENT_HEART: ++heart; break;
                    case GitLabReaction.CONTENT_HOORAY: ++hooray; break;
                    case GitLabReaction.CONTENT_LAUGH: ++laugh; break;
                    case GitLabReaction.CONTENT_ROCKET: ++rocket; break;
                    case GitLabReaction.CONTENT_EYES: ++eyes; break;
                }
            }
            return GitLabReactions.builder()
                    .plusOne(plusOne)
                    .minusOne(minusOne)
                    .confused(confused)
                    .heart(heart)
                    .hooray(hooray)
                    .laugh(laugh)
                    .rocket(rocket)
                    .eyes(eyes)
                    .build();
        }
    }
}
