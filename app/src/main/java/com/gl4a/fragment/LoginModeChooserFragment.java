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
    private Button mOkButton;
    private ParentCallback mCallback;

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
            // glsoat-XXXX (service account), or legacy alphanumeric. Require ≥8 non-whitespace chars.
            private final Pattern TOKEN_PATTERN = Pattern.compile("\\S{8,}");
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
        };
        // Pre-fill with current instance URL
        android.widget.EditText urlEdit = view.findViewById(R.id.instance_url);
        if (urlEdit != null) urlEdit.setText(Gl4Application.get().getInstanceUrl());

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
        mCallback.onLoginCanceled();
    }

    @Override
    public void onClick(View v) {
        // Save instance URL — if the field is cleared, reset to the default (gitlab.com).
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
            handleTokenCheck(makeTokenCheckSingle(mToken.getText()));
        }
    }

    private void handleTokenCheck(Single<Pair<String, GitLabUser>> checkSingle) {
        checkSingle.subscribe(pair -> {
            mCallback.onLoginFinished(pair.first, pair.second);
            dismissAllowingStateLoss();
        }, error -> {
            mCallback.onLoginFailed(error);
            dismissAllowingStateLoss();
        });
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
