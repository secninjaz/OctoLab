package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.StringRes;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.widget.EditorBottomSheet;

import io.reactivex.Single;

/**
 * Stub for GitLab merge request review creation.
 * GitHub SDK review types (CreateReview, SubmitReview, PullRequestReviewService)
 * have been removed; GitLab review API integration is pending.
 */
public class CreateReviewActivity extends AppCompatActivity implements
        EditorBottomSheet.Callback {
    private static final String EXTRA_OWNER = "owner";
    private static final String EXTRA_REPO = "repo";
    private static final String EXTRA_PR_NUMBER = "pr_number";
    private static final String EXTRA_DRAFT_PR = "draft_pr";

    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            int pullRequestNumber, boolean isDraftPR) {
        return new Intent(context, CreateReviewActivity.class)
                .putExtra(EXTRA_OWNER, repoOwner)
                .putExtra(EXTRA_REPO, repoName)
                .putExtra(EXTRA_PR_NUMBER, pullRequestNumber)
                .putExtra(EXTRA_DRAFT_PR, isDraftPR);
    }

    private ArrayAdapter<String> mReviewEventAdapter;
    private CoordinatorLayout mRootLayout;
    private EditorBottomSheet mEditorSheet;
    private Spinner mReviewEventSpinner;

    private String mRepoOwner;
    private String mRepoName;
    private int mPullRequestNumber;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.BottomSheetTheme);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.comment_editor);

        Bundle extras = getIntent().getExtras();
        mRepoOwner = extras.getString(EXTRA_OWNER);
        mRepoName = extras.getString(EXTRA_REPO);
        mPullRequestNumber = extras.getInt(EXTRA_PR_NUMBER);

        mRootLayout = findViewById(R.id.coordinator_layout);
        mEditorSheet = findViewById(R.id.bottom_sheet);

        View header = getLayoutInflater().inflate(R.layout.create_review_header, null);
        mEditorSheet.addHeaderView(header);
        mEditorSheet.setAllowEmpty(true);
        boolean isDraftPR = extras.getBoolean(EXTRA_DRAFT_PR);
        if (isDraftPR) {
            mEditorSheet.setHighlightColor(R.attr.colorPullRequestDraft);
        }

        mReviewEventAdapter = new ArrayAdapter<>(this, R.layout.spinner_item);
        mReviewEventAdapter.add(getString(R.string.pull_request_review_event_comment));
        mReviewEventAdapter.add(getString(R.string.pull_request_review_event_approve));
        mReviewEventAdapter.add(getString(R.string.pull_request_review_event_request_changes));

        mReviewEventSpinner = header.findViewById(R.id.pull_request_review_event);
        mReviewEventSpinner.setAdapter(mReviewEventAdapter);

        TextView titleView = header.findViewById(R.id.review_dialog_title);
        titleView.setText(getString(R.string.pull_request_review_dialog_title, mPullRequestNumber));

        mEditorSheet.setCallback(this);
        setResult(RESULT_CANCELED);
    }

    @Override
    public int getCommentEditorHintResId() {
        return 0;
    }

    @Override
    public Single<?> onEditorDoSend(String body) {
        // TODO: Implement GitLab merge request review submission via GitLabMergeRequestService
        return Single.just(body);
    }

    @Override
    public void onEditorTextSent() {
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public int getEditorErrorMessageResId() {
        return R.string.review_create_error;
    }

    @Override
    public FragmentActivity getActivity() {
        return this;
    }

    @Override
    public CoordinatorLayout getRootLayout() {
        return mRootLayout;
    }
}
