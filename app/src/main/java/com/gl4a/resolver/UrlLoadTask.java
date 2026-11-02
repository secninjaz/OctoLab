package com.gl4a.resolver;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import android.util.Log;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.UiUtils;

import java.util.Optional;

import io.reactivex.Single;

public abstract class UrlLoadTask extends AsyncTask<Void, Void, Optional<Intent>> {
    protected final FragmentActivity mActivity;
    private ProgressDialogFragment mProgressDialog;
    private final Uri mUrlToResolve;
    private int mIntentFlags;
    private Runnable mCompletionCallback;
    private boolean mUseCustomTabForUnresolvedUri = false;
    private int mCustomTabHeaderColor;

    public UrlLoadTask(FragmentActivity activity, Uri urlToResolve) {
        super();
        mActivity = activity;
        mUrlToResolve = urlToResolve;
    }

    public void setIntentFlags(int flags) {
        mIntentFlags = flags;
    }

    public void setOpenUnresolvedUriInCustomTab(int headerColor) {
        mUseCustomTabForUnresolvedUri = true;
        mCustomTabHeaderColor = headerColor;
    }

    /**
     * Must be called BEFORE executing the task, otherwise the callback might not get executed.
     */
    public void setCompletionCallback(Runnable callback) {
        mCompletionCallback = callback;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        mProgressDialog = new ProgressDialogFragment();
        mProgressDialog.show(mActivity.getSupportFragmentManager(), "progress");
    }

    // GitLab refused what the link needs for the user's role (#304): said so, no browser
    private Throwable mRefusal;

    @Override
    protected Optional<Intent> doInBackground(Void... params) {
        try {
            return getSingle().blockingGet();
        } catch (Exception e) {
            Log.e(Gl4Application.LOG_TAG, "Failure during intent resolving", e);
            if (com.gl4a.utils.ProjectAccess.isRefused(e)) mRefusal = e;
            return Optional.empty();
        }
    }

    @Override
    protected void onPostExecute(Optional<Intent> result) {
        if (mActivity.isFinishing()) {
            return;
        }

        if (result.isPresent()) {
            mActivity.startActivity(result.get().setFlags(mIntentFlags));
        } else if (mRefusal != null) {
            // The browser can't show it either: say why instead (#304)
            android.widget.Toast.makeText(mActivity, com.gl4a.utils.ProjectAccess.refusedMessage(
                    mActivity, mRefusal), android.widget.Toast.LENGTH_LONG).show();
        } else if (mUseCustomTabForUnresolvedUri) {
            IntentUtils.openInCustomTabOrBrowser(mActivity, mUrlToResolve, mCustomTabHeaderColor);
        } else {
            IntentUtils.launchBrowser(mActivity, mUrlToResolve, mIntentFlags);
        }

        if (mProgressDialog != null && mProgressDialog.isAdded()) {
            mProgressDialog.dismissAllowingStateLoss();
        }

        if (mCompletionCallback != null) {
            mCompletionCallback.run();
        }
    }

    protected abstract Single<Optional<Intent>> getSingle();

    public static class ProgressDialogFragment extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return UiUtils.createProgressDialog(getActivity(), R.string.loading_msg);
        }
    }
}
