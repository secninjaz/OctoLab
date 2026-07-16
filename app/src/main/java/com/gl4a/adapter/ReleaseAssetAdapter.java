package com.gl4a.adapter;
import com.gl4a.gitlab.model.GitLabRelease;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.utils.StringUtils;

public class ReleaseAssetAdapter extends RootAdapter<GitLabRelease.Asset, ReleaseAssetAdapter.ViewHolder> {
    public ReleaseAssetAdapter(Context context) {
        super(context);
    }

    @Override
    public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.row_download, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabRelease.Asset asset) {
        holder.tvTitle.setText(asset.name());
        // GitLab assets don't expose a label field
        holder.tvDesc.setVisibility(View.GONE);
        // GitLab assets don't expose createdAt or downloadCount
        holder.tvCreatedAt.setVisibility(View.GONE);
        long size = asset.size();
        if (size > 0) {
            holder.tvSize.setVisibility(android.view.View.VISIBLE);
            holder.tvSize.setText(Formatter.formatFileSize(mContext, size));
        } else {
            holder.tvSize.setVisibility(android.view.View.GONE);
        }
        holder.tvDownloads.setVisibility(View.GONE);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private ViewHolder(View view) {
            super(view);
            tvTitle = view.findViewById(R.id.tv_title);
            tvDesc = view.findViewById(R.id.tv_desc);
            tvCreatedAt = view.findViewById(R.id.tv_created_at);
            tvSize = view.findViewById(R.id.tv_size);
            tvDownloads = view.findViewById(R.id.tv_downloads);
        }

        private final TextView tvTitle;
        private final TextView tvDesc;
        private final TextView tvSize;
        private final TextView tvDownloads;
        private final TextView tvCreatedAt;
    }
}
