package com.gl4a.gitlab.model;

/**
 * Aggregated reaction counts for a GitLab resource.
 * Maps to GitHub's Reactions object (reaction count summary).
 */
public class GitLabReactions {
    private int mPlusOne;
    private int mMinusOne;
    private int mLaugh;
    private int mHooray;
    private int mHeart;
    private int mConfused;
    private int mRocket;
    private int mEyes;
    private int mTotalCount;

    public GitLabReactions() {}

    public static Builder builder() { return new Builder(); }

    public int plusOne() { return mPlusOne; }
    public int minusOne() { return mMinusOne; }
    public int laugh() { return mLaugh; }
    public int hooray() { return mHooray; }
    public int heart() { return mHeart; }
    public int confused() { return mConfused; }
    public int rocket() { return mRocket; }
    public int eyes() { return mEyes; }
    public int totalCount() { return mTotalCount; }

    public static class Builder {
        private final GitLabReactions r = new GitLabReactions();

        public Builder plusOne(int v) { r.mPlusOne = v; r.mTotalCount += v; return this; }
        public Builder minusOne(int v) { r.mMinusOne = v; r.mTotalCount += v; return this; }
        public Builder laugh(int v) { r.mLaugh = v; r.mTotalCount += v; return this; }
        public Builder hooray(int v) { r.mHooray = v; r.mTotalCount += v; return this; }
        public Builder heart(int v) { r.mHeart = v; r.mTotalCount += v; return this; }
        public Builder confused(int v) { r.mConfused = v; r.mTotalCount += v; return this; }
        public Builder rocket(int v) { r.mRocket = v; r.mTotalCount += v; return this; }
        public Builder eyes(int v) { r.mEyes = v; r.mTotalCount += v; return this; }
        public GitLabReactions build() { return r; }
    }
}
