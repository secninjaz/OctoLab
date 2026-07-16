package com.gl4a.model;

import android.content.Context;

import com.gl4a.R;

import java.util.Date;

import androidx.annotation.Nullable;

/**
 * Stubbed CI status wrapper. GitLab pipeline status integration is pending.
 */
public class StatusWrapper {
    public enum State {
        Success,
        Failed,
        Unknown
    }

    private String mLabel;
    private String mDescription;
    private State mState = State.Unknown;
    private String mTargetUrl;

    public State state() { return mState; }
    public String label() { return mLabel; }
    public String description() { return mDescription; }
    public String targetUrl() { return mTargetUrl; }
}
