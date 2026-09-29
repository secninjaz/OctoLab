package com.gl4a.fragment;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.GitLabLoginActivity;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.regex.Pattern;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import io.reactivex.Single;

public class LoginModeChooserFragment extends DialogFragment implements
        RadioGroup.OnCheckedChangeListener, View.OnClickListener {

    public interface ParentCallback {
        void onLoginStartOauth();
        void onLoginFinished(String token, GitLabUser user);
        void onLoginFailed(Throwable error);
        void onLoginCanceled();
    }

    public static LoginModeChooserFragment newInstance() {
        return new LoginModeChooserFragment();
    }

    // GitLab OAuth scopes
    public static final String SCOPES = "api read_user read_repository write_repository openid";

    private RadioGroup mModeGroup;
    private View mOauthContainer;
    private View mTokenContainer;
    private View mProgressContainer;
    private WrappedEditor mToken;
    private WrappedEditor mInstanceUrl;
    private android.widget.TextView mCreateTokenLink;
    private Button mOkButton;
    private ParentCallback mCallback;
    // Instance URL before this dialog changed it, restored if the login fails (#168).
    private String mPreviousInstanceUrl;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (!(context instanceof ParentCallback)) {
            throw new IllegalArgumentException("Activity must implement ParentCallback");
        }
        mCallback = (ParentCallback) context;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View view = inflater.inflate(R.layout.login_dialog, null);

        mModeGroup = view.findViewById(R.id.login_mode);
        mModeGroup.setOnCheckedChangeListener(this);

        mOauthContainer = view.findViewById(R.id.oauth_container);
        mTokenContainer = view.findViewById(R.id.token_container);
        mProgressContainer = view.findViewById(R.id.progress_container);

        mToken = new WrappedEditor(view, R.id.token, R.id.token_wrapper) {
            // GitLab tokens: glpat-XXXX (PAT), glgat-XXXX (group), gldt-XXXX (deploy),
            // glsoat-XXXX (service account), or legacy 20-char alphanumeric. All use only
            // letters, digits, '_', '-' and '.', and are at least 20 characters (#168).
            private final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-]{20,}");
            @Override
            protected int getTextErrorResId(Editable s) {
                int resId = super.getTextErrorResId(s);
                if (resId == 0 && !TOKEN_PATTERN.matcher(s.toString().trim()).matches()) {
                    resId = R.string.credentials_error_invalid_token;
                }
                return resId;
            }
        };

        // Instance URL field for self-hosted GitLab
        mInstanceUrl = new WrappedEditor(view, R.id.instance_url, R.id.instance_url_wrapper) {
            @Override
            protected int getTextErrorResId(Editable s) {
                // Empty is OK — defaults to gitlab.com
                return 0;
            }

            @Override
            public void afterTextChanged(Editable s) {
                super.afterTextChanged(s);
                updateCreateTokenLink();
            }
        };
        mCreateTokenLink = view.findViewById(R.id.create_token_link);
        mCreateTokenLink.setOnClickListener(v -> IntentUtils.openInCustomTabOrBrowser(
                requireActivity(), Uri.parse(enteredInstanceUrl()
                        + "/-/user_settings/personal_access_tokens")
                        .buildUpon()
                        .appendQueryParameter("name", "OctoLab")
                        .appendQueryParameter("scopes", "api,read_user")
                        .build()));
        // Pre-fill with current instance URL
        android.widget.EditText urlEdit = view.findViewById(R.id.instance_url);
        if (urlEdit != null) urlEdit.setText(Gl4Application.get().getInstanceUrl());
        updateCreateTokenLink();

        mModeGroup.check(R.id.token_button);

        return new AlertDialog.Builder(getActivity())
                .setView(view)
                .setPositiveButton(R.string.login, null)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel())
                .create();
    }

    @Override
    public void onResume() {
        super.onResume();
        final AlertDialog d = (AlertDialog) getDialog();
        mOkButton = d != null ? d.getButton(DialogInterface.BUTTON_POSITIVE) : null;
        if (mOkButton != null) {
            mOkButton.setOnClickListener(this);
            updateOkButtonState();
        }
    }

    @Override
    public void onCheckedChanged(RadioGroup group, @IdRes int checkedButtonId) {
        updateContainerVisibility(false);
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        restoreInstanceUrl();
        mCallback.onLoginCanceled();
    }

    @Override
    public void onClick(View v) {
        // Save instance URL — if the field is cleared, reset to the default (gitlab.com).
        // Remember the current one: the global instance URL is also what the active account's
        // requests use, so it must go back if this login fails (#168).
        if (mPreviousInstanceUrl == null) {
            mPreviousInstanceUrl = Gl4Application.get().getInstanceUrl();
        }
        if (mInstanceUrl != null) {
            String url = mInstanceUrl.getText();
            if (!TextUtils.isEmpty(url)) {
                Gl4Application.get().setInstanceUrl(url.trim());
            } else {
                // Fix: allow the user to clear a previously-stored custom URL by emptying the
                // field. Without this guard there is no UI way to return to gitlab.com once a
                // self-hosted URL has been saved.
                Gl4Application.get().setInstanceUrl(Gl4Application.DEFAULT_INSTANCE);
            }
        }
        updateContainerVisibility(true);
        if (mModeGroup.getCheckedRadioButtonId() == R.id.oauth_button) {
            mCallback.onLoginStartOauth();
            dismissAllowingStateLoss();
        } else {
            // Deferred so a malformed instance URL (Retrofit rejects the base URL) is reported
            // like any other login error instead of crashing (#168).
            handleTokenCheck(Single.defer(() -> makeTokenCheckSingle(mToken.getText())));
        }
    }

    private void handleTokenCheck(Single<Pair<String, GitLabUser>> checkSingle) {
        checkSingle.subscribe(pair -> {
            mPreviousInstanceUrl = null;
            mCallback.onLoginFinished(pair.first, pair.second);
            dismissAllowingStateLoss();
        }, error -> {
            // Keep the dialog open and say what went wrong, instead of closing it silently.
            String instance = Gl4Application.get().getInstanceUrl();
            restoreInstanceUrl();
            updateContainerVisibility(false);
            mToken.showError(describeLoginError(error, instance));
        });
    }

    /** Instance URL as currently typed (gitlab.com if empty), without trailing slashes. */
    private String enteredInstanceUrl() {
        String url = mInstanceUrl != null ? mInstanceUrl.getText() : null;
        if (TextUtils.isEmpty(url)) url = Gl4Application.DEFAULT_INSTANCE;
        if (!url.contains("://")) url = "https://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** Keeps the "Create a token on <host>" link in step with the instance URL field (#169). */
    private void updateCreateTokenLink() {
        if (mCreateTokenLink == null) return;
        String host = Uri.parse(enteredInstanceUrl()).getHost();
        mCreateTokenLink.setText(getString(R.string.login_create_token,
                host != null ? host : enteredInstanceUrl()));
    }

    private void restoreInstanceUrl() {
        if (mPreviousInstanceUrl != null) {
            Gl4Application.get().setInstanceUrl(mPreviousInstanceUrl);
            mPreviousInstanceUrl = null;
        }
    }

    private String describeLoginError(Throwable error, String instance) {
        String host = Uri.parse(instance).getHost();
        if (host == null) host = instance;
        if (error instanceof com.gl4a.ApiRequestException) {
            int status = ((com.gl4a.ApiRequestException) error).getStatus();
            if (status == 401) return getString(R.string.login_error_token_rejected, host);
            if (status == 403) return getString(R.string.login_error_token_scope);
            return getString(R.string.login_error_http, host, status);
        }
        if (error instanceof java.io.IOException) {
            return getString(R.string.login_error_unreachable, host);
        }
        String message = error.getMessage();
        return getString(R.string.login_error_generic,
                message != null ? message : error.getClass().getSimpleName());
    }

    private void updateContainerVisibility(boolean busy) {
        @IdRes int checked = mModeGroup.getCheckedRadioButtonId();
        if (mOauthContainer != null)
            mOauthContainer.setVisibility(checked == R.id.oauth_button && !busy ? View.VISIBLE : View.GONE);
        if (mTokenContainer != null)
            mTokenContainer.setVisibility(checked == R.id.token_button && !busy ? View.VISIBLE : View.GONE);
        if (mProgressContainer != null)
            mProgressContainer.setVisibility(busy ? View.VISIBLE : View.GONE);
        updateOkButtonState();
    }

    private void updateOkButtonState() {
        boolean enable;
        if (mProgressContainer != null && mProgressContainer.getVisibility() == View.VISIBLE) {
            enable = false;
        } else if (mModeGroup.getCheckedRadioButtonId() == R.id.token_button) {
            enable = mToken == null || !mToken.hasError();
        } else {
            enable = true;
        }
        if (mOkButton != null) mOkButton.setEnabled(enable);
    }

    private Single<Pair<String, GitLabUser>> makeTokenCheckSingle(String token) {
        GitLabUserService userService = ServiceFactory.get(
                GitLabUserService.class, true, null, token, null);
        Single<GitLabUser> userSingle = userService.getCurrentUser()
                .map(ApiHelpers::throwOnFailure)
                .compose(RxUtils::doInBackground);
        return Single.zip(Single.just(token), userSingle, Pair::create);
    }

    private class WrappedEditor implements TextWatcher {
        private final TextInputEditText mEditor;
        private final TextInputLayout mWrapper;

        public WrappedEditor(View parent, @IdRes int editorResId, @IdRes int wrapperResId) {
            mEditor = parent.findViewById(editorResId);
            mWrapper = parent.findViewById(wrapperResId);
            if (mEditor != null) {
                mEditor.addTextChangedListener(this);
                afterTextChanged(mEditor.getText());
            }
        }

        public String getText() {
            Editable e = mEditor != null ? mEditor.getText() : null;
            return e != null ? e.toString().trim() : null;
        }

        public boolean hasError() {
            return mWrapper != null && mWrapper.isErrorEnabled();
        }

        /** Shows an error that clears once the text is edited. */
        public void showError(CharSequence error) {
            if (mWrapper != null) {
                mWrapper.setError(error);
            }
            updateOkButtonState();
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            int errorResId = getTextErrorResId(s);
            if (mWrapper != null) {
                if (errorResId != 0) {
                    mWrapper.setError(getString(errorResId));
                } else {
                    mWrapper.setErrorEnabled(false);
                }
            }
            updateOkButtonState();
        }

        protected int getTextErrorResId(Editable s) {
            if (TextUtils.isEmpty(s)) return R.string.credentials_error_empty;
            return 0;
        }
    }
}
