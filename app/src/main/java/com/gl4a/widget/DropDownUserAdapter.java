package com.gl4a.widget;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Context;
import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DropDownUserAdapter extends BaseAdapter implements Filterable {

    private final Object mLock = new Object();

    private final Context mContext;
    private List<GitLabUser> mUsers;
    private final ArrayList<GitLabUser> mOriginalUsers;
    private final LayoutInflater mInflater;
    private ArrayFilter mFilter;

    public DropDownUserAdapter(Context context) {
        mContext = context;
        mUsers = new ArrayList<>();
        mOriginalUsers = new ArrayList<>();
        mInflater = LayoutInflater.from(context);
    }

    public void replace(Set<GitLabUser> newUsers) {
        synchronized (mLock) {
            String ourLogin = Gl4Application.get().getAuthLogin();
            mOriginalUsers.clear();
            for (GitLabUser user : newUsers) {
                if (!TextUtils.equals(ourLogin, user.login())) {
                    mOriginalUsers.add(user);
                }
            }

            Collections.sort(mOriginalUsers, (first, second) -> {
                final String firstUsername = ApiHelpers.getUserLogin(mContext, first);
                final String secondUsername = ApiHelpers.getUserLogin(mContext, second);

                return firstUsername.compareToIgnoreCase(secondUsername);
            });
        }

        notifyDataSetChanged();
    }

    public Set<GitLabUser> getUnfilteredUsers() {
        return new HashSet<>(mOriginalUsers);
    }

    @Override
    public int getCount() {
        return mUsers.size();
    }

    @Override
    public GitLabUser getItem(int position) {
        return mUsers.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final View view;

        if (convertView == null) {
            view = mInflater.inflate(R.layout.row_dropdown_user, parent, false);
            view.setTag(new ViewHolder(view));
        } else {
            view = convertView;
        }

        final GitLabUser user = getItem(position);
        final ViewHolder holder = (ViewHolder) view.getTag();

        holder.tvUser.setText(ApiHelpers.getUserLogin(mContext, user));
        AvatarHandler.assignAvatar(holder.ivUser, user);

        return view;
    }

    @NonNull
    @Override
    public Filter getFilter() {
        if (mFilter == null) {
            mFilter = new ArrayFilter();
        }
        return mFilter;
    }

    private class ArrayFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();

            if (TextUtils.isEmpty(constraint)) {
                final ArrayList<GitLabUser> list;
                synchronized (mLock) {
                    list = new ArrayList<>(mOriginalUsers);
                }
                results.values = list;
                results.count = list.size();
            } else if (!constraint.toString().startsWith("@")) {
                results.values = new ArrayList<>();
                results.count = 0;
            } else {
                final String constraintString =
                        constraint.toString().substring(1).toLowerCase();

                final ArrayList<GitLabUser> values;
                synchronized (mLock) {
                    values = new ArrayList<>(mOriginalUsers);
                }

                final int count = values.size();
                final ArrayList<GitLabUser> newValues = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    final GitLabUser user = values.get(i);
                    final String value = ApiHelpers.getUserLogin(mContext, user);
                    final String valueText = value.toLowerCase();

                    if (valueText.startsWith(constraintString)) {
                        newValues.add(user);
                    }
                }

                results.values = newValues;
                results.count = newValues.size();
            }

            return results;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            if (results.values != null) {
                mUsers = (ArrayList<GitLabUser>) results.values;
            } else {
                mUsers = new ArrayList<>();
            }
            if (results.count > 0) {
                notifyDataSetChanged();
            } else {
                notifyDataSetInvalidated();
            }
        }

        @Override
        public CharSequence convertResultToString(Object resultValue) {
            final GitLabUser user = (GitLabUser) resultValue;
            return StringUtils.formatMention(mContext, user);
        }
    }

    private static class ViewHolder {
        private ViewHolder(View view) {
            ivUser = view.findViewById(R.id.iv_user);
            tvUser = view.findViewById(R.id.tv_user);
        }

        private final ImageView ivUser;
        private final TextView tvUser;
    }
}
