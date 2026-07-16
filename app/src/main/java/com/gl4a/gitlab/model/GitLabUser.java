package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;
import com.squareup.moshi.Json;

public class GitLabUser implements Parcelable {
    @Json(name = "id") public long id;
    @Json(name = "username") public String username;
    @Json(name = "name") public String name;
    @Json(name = "avatar_url") public String avatarUrl;
    @Json(name = "web_url") public String webUrl;
    @Json(name = "bio") public String bio;
    @Json(name = "location") public String location;
    @Json(name = "public_email") public String email;
    @Json(name = "website_url") public String blog;
    @Json(name = "company") public String company;
    // followers_count / following_count are only present on the authenticated /user endpoint,
    // not on /users list items — those will always deserialize to 0.
    @Json(name = "followers_count") public int followers;
    @Json(name = "following_count") public int following;
    // GitLab API v4 has no "public_repos" field; public_projects_count is the closest equivalent
    // and is only returned by the extended /user endpoint.
    @Json(name = "public_projects_count") public int publicRepos;
    @Json(name = "state") public String state;
    @Json(name = "is_admin") public boolean isAdmin;
    @Json(name = "bot") public boolean bot;
    @Json(name = "created_at") public String createdAt;

    // Match GitHub SDK method signatures
    public long id() { return id; }
    public String login() { return username; }
    public String name() { return name; }
    public String avatarUrl() { return avatarUrl; }
    public String htmlUrl() { return webUrl; }
    public String bio() { return bio; }
    public String location() { return location; }
    public String email() { return email; }
    public String blog() { return blog; }
    public String company() { return company; }
    public Integer followers() { return followers; }
    public Integer following() { return following; }
    public Integer publicRepos() { return publicRepos; }
    public String type() { return "User"; }

    public static GitLabUser create(String username, long id) {
        GitLabUser u = new GitLabUser();
        u.username = username;
        u.id = id;
        return u;
    }

    public GitLabUser() {}

    protected GitLabUser(Parcel in) {
        id = in.readLong();
        username = in.readString();
        name = in.readString();
        avatarUrl = in.readString();
        webUrl = in.readString();
        bio = in.readString();
        location = in.readString();
        email = in.readString();
        blog = in.readString();
        company = in.readString();
        followers = in.readInt();
        following = in.readInt();
        publicRepos = in.readInt();
        state = in.readString();
        isAdmin = in.readByte() != 0;
        createdAt = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(username);
        dest.writeString(name);
        dest.writeString(avatarUrl);
        dest.writeString(webUrl);
        dest.writeString(bio);
        dest.writeString(location);
        dest.writeString(email);
        dest.writeString(blog);
        dest.writeString(company);
        dest.writeInt(followers);
        dest.writeInt(following);
        dest.writeInt(publicRepos);
        dest.writeString(state);
        dest.writeByte((byte) (isAdmin ? 1 : 0));
        dest.writeString(createdAt);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabUser> CREATOR = new Creator<GitLabUser>() {
        @Override
        public GitLabUser createFromParcel(Parcel in) { return new GitLabUser(in); }

        @Override
        public GitLabUser[] newArray(int size) { return new GitLabUser[size]; }
    };
}
