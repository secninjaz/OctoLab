package com.gl4a.adapter;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabRelease;
import com.gl4a.utils.StringUtils;

public class ReleaseAdapter extends RootAdapter<GitLabRelease, ReleaseAdapter.ViewHolder> {
    public ReleaseAdapter(Context context) {
        super(context);
    }

    @Override
    public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.row_release, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabRelease release) {
        String name = release.name();
        if (TextUtils.isEmpty(name)) {
            name = release.tagName();
        }
        holder.tvTitle.setText(name);
        holder.tvType.setText(formatReleaseType(release));
        holder.tvCreatedAt.setText(formatReleaseDate(release));
    }

    private CharSequence formatReleaseDate(GitLabRelease release) {
        String dateToShow;
        int dateStringResId;
        if (release.publishedAt() != null) {
            dateToShow = release.publishedAt();
            dateStringResId = R.string.released_at;
        } else {
            dateToShow = release.createdAt;
            dateStringResId = R.string.download_created;
        }
        return mContext.getString(dateStringResId,
                StringUtils.formatRelativeTime(mContext, dateToShow, true));
    }

    private String formatReleaseType(GitLabRelease release) {
        if (release.isDraft()) {
            return mContext.getString(R.string.release_type_draft);
        }
        if (release.isPrerelease()) {
            return mContext.getString(R.string.release_type_prerelease);
        }
        return mContext.getString(R.string.release_type_final);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private ViewHolder(View view) {
            super(view);
            tvTitle = view.findViewById(R.id.tv_title);
            tvType = view.findViewById(R.id.tv_type);
            tvCreatedAt  = view.findViewById(R.id.tv_created_at);
        }

        private final TextView tvTitle;
        private final TextView tvType;
        private final TextView tvCreatedAt;
    }
}
