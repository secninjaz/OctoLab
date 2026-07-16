package com.gl4a.fragment;
import com.gl4a.gitlab.model.GitLabTreeItem;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ContentListCacheFragment extends Fragment {
    private final Map<String, ArrayList<GitLabTreeItem>> mContentCache =
            new LinkedHashMap<String, ArrayList<GitLabTreeItem>>() {
                private static final long serialVersionUID = -2379579224736389357L;
                private static final int MAX_CACHE_ENTRIES = 100;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ArrayList<GitLabTreeItem>> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    public void addToCache(String path, List<GitLabTreeItem> contents) {
        mContentCache.put(path, new ArrayList<>(contents));
    }

    public ArrayList<GitLabTreeItem> getFromCache(String path) {
        return mContentCache.get(path);
    }

    public void clear() {
        mContentCache.clear();
    }
}
