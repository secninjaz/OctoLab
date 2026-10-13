package com.gl4a.adapter;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabSearchResult;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Context;
import android.graphics.Typeface;
import androidx.recyclerview.widget.RecyclerView;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.gl4a.R;
import com.gl4a.fragment.SearchFragment;
import com.gl4a.utils.ApiHelpers;

import java.util.List;

public class SearchAdapter extends RootAdapter<Object, RecyclerView.ViewHolder> {
    public interface Callback {
        void onSearchFragmentClick(GitLabSearchResult.GitLabBlob result, int matchIndex);
    }

    /**
     * A section of the All search, e.g. "Projects", before its first few results; tapping it
     * shows all results of its kind (#262).
     */
    public static final class Section {
        public final int searchType;
        @StringRes final int title;

        public Section(int searchType, @StringRes int title) {
            this.searchType = searchType;
            this.title = title;
        }
    }

    // Each kind of row in its own range, so one list can mix them (the All search, #262)
    private static final int KIND_USER = 10000;
    private static final int KIND_REPO = 20000;
    private static final int KIND_CODE = 30000;
    private static final int KIND_GROUP = 40000;
    private static final int KIND_ISSUE = 50000;
    private static final int KIND_SECTION = 60000;

    private UserAdapter mUserAdapter;
    private RepositoryAdapter mRepoAdapter;
    private CodeSearchAdapter mCodeAdapter;
    private IssueAdapter mIssueAdapter;
    private int mMode;

    public SearchAdapter(Context context, Callback callback) {
        super(context);
        mUserAdapter = new UserAdapter(context);
        mRepoAdapter = new RepositoryAdapter(context);
        mCodeAdapter = new CodeSearchAdapter(context, callback);
        mIssueAdapter = new IssueAdapter(context);
        mMode = SearchFragment.SEARCH_TYPE_REPO;
    }

    public void setMode(int mode) {
        mMode = mode;
        clear();
    }

    @Override
    public int getItemViewType(Object item) {
        if (item instanceof Section) {
            return KIND_SECTION;
        } else if (item instanceof GitLabUser) {
            return mUserAdapter.getItemViewType((GitLabUser) item) + KIND_USER;
        } else if (item instanceof GitLabProject) {
            return mRepoAdapter.getItemViewType((GitLabProject) item) + KIND_REPO;
        } else if (item instanceof GitLabGroup) {
            return KIND_GROUP;
        } else if (item instanceof GitLabIssue) {
            return mIssueAdapter.getItemViewType((GitLabIssue) item) + KIND_ISSUE;
        } else {
            return mCodeAdapter.getItemViewType((GitLabSearchResult.GitLabBlob) item) + KIND_CODE;
        }
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(LayoutInflater inflater,
            ViewGroup parent, int viewType) {
        int kind = viewType / 10000 * 10000;
        int subType = viewType % 10000;
        switch (kind) {
            case KIND_SECTION:
                return new SectionViewHolder(
                        inflater.inflate(R.layout.row_search_section, parent, false));
            case KIND_GROUP:
                // The one group row of the app's group lists (#172)
                return new GroupViewHolder(
                        inflater.inflate(R.layout.row_group_tree_item, parent, false));
            case KIND_USER:
                return mUserAdapter.onCreateViewHolder(inflater, parent, subType);
            case KIND_REPO:
                return mRepoAdapter.onCreateViewHolder(inflater, parent, subType);
            case KIND_ISSUE:
                return mIssueAdapter.onCreateViewHolder(inflater, parent, subType);
            default:
                return mCodeAdapter.onCreateViewHolder(inflater, parent, subType);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, Object item) {
        if (item instanceof Section) {
            ((SectionViewHolder) holder).title.setText(((Section) item).title);
        } else if (item instanceof GitLabUser) {
            mUserAdapter.onBindViewHolder((UserAdapter.ViewHolder) holder, (GitLabUser) item);
        } else if (item instanceof GitLabProject) {
            mRepoAdapter.onBindViewHolder((RepositoryAdapter.ViewHolder) holder, (GitLabProject) item);
        } else if (item instanceof GitLabGroup) {
            GroupTreeRows.bind(holder.itemView, (GitLabGroup) item, null, 0, false,
                    (String) null);
        } else if (item instanceof GitLabIssue) {
            mIssueAdapter.onBindViewHolder((IssueAdapter.ViewHolder) holder, (GitLabIssue) item);
        } else {
            mCodeAdapter.onBindViewHolder((CodeSearchAdapter.ViewHolder) holder, (GitLabSearchResult.GitLabBlob) item);
        }
    }

    private static class SectionViewHolder extends RecyclerView.ViewHolder {
        final TextView title;

        SectionViewHolder(View view) {
            super(view);
            title = view.findViewById(R.id.tv_title);
        }
    }

    private static class GroupViewHolder extends RecyclerView.ViewHolder {
        GroupViewHolder(View view) {
            super(view);
        }
    }

    public static class CodeSearchAdapter extends RootAdapter<GitLabSearchResult.GitLabBlob, CodeSearchAdapter.ViewHolder> {
        private final Callback mCallback;

        public CodeSearchAdapter(Context context, Callback callback) {
            super(context);
            mCallback = callback;
        }

        @Override
        public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent,
                int viewType) {
            View v = inflater.inflate(R.layout.row_code_search, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, GitLabSearchResult.GitLabBlob result) {
            GitLabProject repo = result.repository();
            holder.tvTitle.setText(result.path());
            holder.tvRepo.setText(ApiHelpers.formatRepoName(mContext, repo));

            // GitLab search blobs don't provide text match highlights (GitHub-specific feature)
            List<Object> matches = result.textMatches();
            if (matches != null && !matches.isEmpty()) {
                LayoutInflater inflater = LayoutInflater.from(mContext);

                for (int i = 0; i < matches.size(); i++) {
                    SpannableStringBuilder builder = new SpannableStringBuilder();

                    View row = holder.matchesContainer.getChildAt(i);
                    if (row == null) {
                        row = inflater.inflate(R.layout.row_search_match,
                                holder.matchesContainer, false);
                        holder.matchesContainer.addView(row);
                    }

                    TextView tvMatch = row.findViewById(R.id.tv_match);
                    tvMatch.setOnClickListener(this);
                    tvMatch.setText(builder);
                    tvMatch.setTag(result);
                    tvMatch.setTag(R.id.search_match_index, i);
                    tvMatch.setTypeface(Typeface.MONOSPACE);
                    row.setVisibility(View.VISIBLE);
                }
                for (int i = matches.size(); i < holder.matchesContainer.getChildCount(); i++) {
                    holder.matchesContainer.getChildAt(i).setVisibility(View.GONE);
                }
                holder.matchesContainer.setVisibility(View.VISIBLE);
            } else {
                holder.matchesContainer.setVisibility(View.GONE);
            }
        }

        @Override
        public void onClick(View view) {
            if (view.getId() == R.id.tv_match) {
                GitLabSearchResult.GitLabBlob searchResult = (GitLabSearchResult.GitLabBlob) view.getTag();
                mCallback.onSearchFragmentClick(searchResult,
                        (int) view.getTag(R.id.search_match_index));
                return;
            }

            super.onClick(view);
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            private ViewHolder(View view) {
                super(view);
                tvTitle = view.findViewById(R.id.tv_title);
                tvRepo = view.findViewById(R.id.tv_repo);
                matchesContainer = view.findViewById(R.id.matches_container);
            }

            private final TextView tvTitle;
            private final TextView tvRepo;
            private final ViewGroup matchesContainer;
        }
    }
}
