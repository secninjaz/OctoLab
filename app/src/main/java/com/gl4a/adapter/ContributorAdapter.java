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
import com.gl4a.utils.AvatarHandler;

public class ContributorAdapter extends RootAdapter<GitLabContributor, ContributorAdapter.ViewHolder>
        implements com.gl4a.utils.GitAuthorLookups.Listener {
    // Emails of the rows on screen, so lookups skip rows scrolled away (#322)
    private final java.util.Set<String> mShownEmails = new java.util.HashSet<>();
    private volatile long mProjectId;

    public ContributorAdapter(Context context) {
        super(context);
    }

    /** The project, for looking up contributors' accounts through their commits (#322). */
    public void setProjectId(long projectId) {
        mProjectId = projectId;
    }

    @Override
    public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.row_gravatar_twoline, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabContributor contributor) {
        // The same author avatar as commits: the GitLab account behind the email (#239)
        AvatarHandler.assignGitAuthorAvatar(holder.ivGravatar, contributor.name(),
                contributor.email());
        // Not known yet: its account found as a tap finds it, one at a time (#322)
        String email = contributor.email();
        if (email != null) {
            holder.email = email;
            synchronized (mShownEmails) {
                mShownEmails.add(email);
            }
            com.gl4a.utils.GitAuthorLookups.request(email, mProjectId, this);
        }
        holder.tvTitle.setText(contributor.name() != null ? contributor.name() : "");
        int contributions = contributor.commits();
        holder.tvExtra.setText(mContext.getResources().getQuantityString(R.plurals.contributor_extra_data,
                contributions, contributions));
    }

    @Override
    public void onViewRecycled(RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof ViewHolder && ((ViewHolder) holder).email != null) {
            synchronized (mShownEmails) {
                mShownEmails.remove(((ViewHolder) holder).email);
            }
        }
    }

    @Override
    public boolean isStillShown(String email) {
        synchronized (mShownEmails) {
            return mShownEmails.contains(email);
        }
    }

    @Override
    public void onFound(String email, com.gl4a.gitlab.model.GitLabUser user) {
        for (int i = 0; i < getCount(); i++) {
            if (email.equalsIgnoreCase(getItem(i).email())) {
                notifyItemChanged(getAdapterPositionForIndex(i));
            }
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private String email;
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