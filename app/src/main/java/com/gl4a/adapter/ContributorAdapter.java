package com.gl4a.adapter;
import com.gl4a.gitlab.model.GitLabContributor;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.R;

public class ContributorAdapter extends RootAdapter<GitLabContributor, ContributorAdapter.ViewHolder> {
    public ContributorAdapter(Context context) {
        super(context);
    }

    @Override
    public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.row_gravatar_twoline, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabContributor contributor) {
        holder.ivGravatar.setImageDrawable(null);
        holder.tvTitle.setText(contributor.name() != null ? contributor.name() : "");
        int contributions = contributor.commits();
        holder.tvExtra.setText(mContext.getResources().getQuantityString(R.plurals.contributor_extra_data,
                contributions, contributions));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private ViewHolder(View view) {
            super(view);
            ivGravatar = view.findViewById(R.id.iv_gravatar);
            tvTitle = view.findViewById(R.id.tv_title);
            tvExtra = view.findViewById(R.id.tv_extra);
        }

        private final TextView tvTitle;
        private final ImageView ivGravatar;
        private final TextView tvExtra;
    }
}