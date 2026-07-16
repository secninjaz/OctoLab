package com.gl4a.widget;
import com.gl4a.gitlab.model.GitLabMergeRequest;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.StringRes;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;

public class PullRequestBranchInfoView extends RelativeLayout implements View.OnClickListener {
    private final TextView mSourceBranchView;
    private final TextView mTargetBranchView;
    private final int mAccentColor;

    private GitLabMergeRequest.GitLabMRBranch mSourceMarker;
    private GitLabMergeRequest.GitLabMRBranch mTargetMarker;

    public PullRequestBranchInfoView(Context context) {
        this(context, null);
    }

    public PullRequestBranchInfoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PullRequestBranchInfoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        inflate(context, R.layout.view_pull_request_branch_info, this);

        mAccentColor = UiUtils.resolveColor(getContext(), android.R.attr.textColorLink);
        mSourceBranchView = findViewById(R.id.tv_pr_from);
        mSourceBranchView.setOnClickListener(this);
        mTargetBranchView = findViewById(R.id.tv_pr_to);
        mTargetBranchView.setOnClickListener(this);
    }

    public void bind(GitLabMergeRequest.GitLabMRBranch sourceMarker, GitLabMergeRequest.GitLabMRBranch targetMarker,
            String sourceReference) {
        mSourceMarker = sourceMarker;
        mTargetMarker = targetMarker;
        formatMarkerText(mSourceBranchView, R.string.pull_request_from, sourceMarker,
                sourceReference != null);
        formatMarkerText(mTargetBranchView, R.string.pull_request_to, targetMarker, true);
    }

    private void formatMarkerText(TextView view, @StringRes int formatResId,
            final GitLabMergeRequest.GitLabMRBranch marker, boolean makeClickable) {
        SpannableStringBuilder builder = StringUtils.applyBoldTags(getContext().getString(formatResId));
        int pos = builder.toString().indexOf("[ref]");
        if (pos >= 0) {
            String label = TextUtils.isEmpty(marker.label()) ? marker.ref() : marker.label();
            builder.replace(pos, pos + 5, label);
            if (marker.repo() != null && makeClickable) {
                builder.setSpan(
                        new ForegroundColorSpan(mAccentColor), pos, pos + label.length(), 0);
                view.setClickable(true);
            } else {
                view.setClickable(false);
            }
        }

        view.setText(builder);
    }

    @Override
    public void onClick(View v) {
        GitLabMergeRequest.GitLabMRBranch marker = v.getId() == R.id.tv_pr_from ? mSourceMarker : mTargetMarker;
        if (marker.repo() != null) {
            Intent intent = RepositoryActivity.makeIntent(getContext(), marker.repo(),
                    marker.ref());
            getContext().startActivity(intent);
        }
    }
}
