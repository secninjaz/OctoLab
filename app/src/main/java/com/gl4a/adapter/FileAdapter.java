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
package com.gl4a.adapter;
import com.gl4a.gitlab.model.GitLabTreeItem;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.utils.FileUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class FileAdapter extends RootAdapter<GitLabTreeItem, FileAdapter.ViewHolder> {
    private Set<String> mSubModuleNames = Collections.emptySet();
    private Map<String, Long> mFileSizes = new HashMap<>();

    public void updateFileSizes(Map<String, Long> sizes) {
        mFileSizes = sizes;
        notifyDataSetChanged();
    }

    public FileAdapter(Context context) {
        super(context);
    }

    public void setSubModuleNames(Set<String> subModules) {
        mSubModuleNames = subModules;
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.row_file_manager, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabTreeItem content) {
        String name = content.name();
        boolean isSubModule = mSubModuleNames.contains(name);

        holder.icon.setBackgroundResource(getIconId(content.type(), name));
        holder.fileName.setText(name);

        // Sizes fetched via HEAD /repository/files/:path (X-Gitlab-Size header).
        // The tree API has no size field — sizes arrive asynchronously via updateFileSizes().
        Long size = mFileSizes.get(content.path());
        if (!isSubModule && "blob".equals(content.type()) && size != null) {
            holder.fileSize.setText(Formatter.formatShortFileSize(mContext, size));
            holder.fileSize.setVisibility(View.VISIBLE);
        } else {
            holder.fileSize.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean hasDividers() {
        return false;
    }

    private int getIconId(String type, String fileName) {
        if (mSubModuleNames != null && mSubModuleNames.contains(fileName)) {
            return R.drawable.submodule;
        } else if ("tree".equals(type)) {
            return R.drawable.folder;
        } else if ("blob".equals(type) && FileUtils.isImage(fileName)) {
            return R.drawable.content_picture;
        } else {
            return R.drawable.file;
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private ViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.iv_icon);
            fileName = view.findViewById(R.id.tv_text);
            fileSize = view.findViewById(R.id.tv_size);
        }

        private final ImageView icon;
        private final TextView fileName;
        private final TextView fileSize;
    }
}