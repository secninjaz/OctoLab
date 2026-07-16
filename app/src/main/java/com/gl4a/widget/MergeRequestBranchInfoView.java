package com.gl4a.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.gl4a.gitlab.model.GitLabMergeRequest;

/**
 * Stub widget for displaying merge request branch info (head and base branches).
 * Originally a GitHub pull-request branch info view; stubbed for GitLab port compilation.
 */
public class MergeRequestBranchInfoView extends LinearLayout {
    public MergeRequestBranchInfoView(Context context) {
        super(context);
    }

    public MergeRequestBranchInfoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MergeRequestBranchInfoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Bind the view to the given head and base branch info. */
    public void bind(GitLabMergeRequest.GitLabMRBranch head, GitLabMergeRequest.GitLabMRBranch base) {
        // stub — branch info rendering not yet implemented for GitLab
    }
}
