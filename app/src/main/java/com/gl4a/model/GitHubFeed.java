package com.gl4a.model;

import android.os.Parcel;
import android.os.Parcelable;

public class GitHubFeed implements Parcelable {

    public GitHubFeed() {}

    protected GitHubFeed(Parcel in) {}

    @Override
    public void writeToParcel(Parcel dest, int flags) {}

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitHubFeed> CREATOR = new Creator<GitHubFeed>() {
        @Override
        public GitHubFeed createFromParcel(Parcel in) { return new GitHubFeed(in); }

        @Override
        public GitHubFeed[] newArray(int size) { return new GitHubFeed[size]; }
    };
}
