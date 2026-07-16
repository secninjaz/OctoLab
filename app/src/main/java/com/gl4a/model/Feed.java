/*
 * Copyright 2011 Azwan Adli Abdullah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gl4a.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import androidx.annotation.Nullable;

/**
 * Stub for Atom/RSS feed entries from GitHub blog.
 * GitLab has no equivalent blog feed — simplexml dependency was removed,
 * so this class is stubbed to keep the rest of the code compilable.
 */
public class Feed implements Parcelable {
    private String id;
    private String link;
    private String title;
    private String content;
    private String author;
    private String avatarUrl;
    private int userId;
    private String preview;

    public Feed() {}

    public void setId(String id) { this.id = id; }
    public void setTitle(String title) { this.title = title; }

    public String getId() { return id; }

    @Nullable
    public Date getPublished() { return null; }

    public String getLink() { return link; }

    @Nullable
    public String getTitle() { return title; }

    public String getContent() { return content; }

    public String getPreview() { return preview; }

    @Nullable
    public String getAuthor() { return author; }

    public int getUserId() { return userId; }

    public String getAvatarUrl() { return avatarUrl; }

    @Nullable
    public Date getUpdated() { return null; }

    /** Returns an empty list — no feed source is configured for GitLab. */
    public static List<String> getItems() {
        return new ArrayList<>();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(link);
        dest.writeString(title);
        dest.writeString(content);
        dest.writeString(author);
        dest.writeString(avatarUrl);
        dest.writeInt(userId);
        dest.writeString(preview);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Feed> CREATOR = new Creator<Feed>() {
        @Override
        public Feed createFromParcel(Parcel in) {
            Feed feed = new Feed();
            feed.id = in.readString();
            feed.link = in.readString();
            feed.title = in.readString();
            feed.content = in.readString();
            feed.author = in.readString();
            feed.avatarUrl = in.readString();
            feed.userId = in.readInt();
            feed.preview = in.readString();
            return feed;
        }

        @Override
        public Feed[] newArray(int size) {
            return new Feed[size];
        }
    };
}
