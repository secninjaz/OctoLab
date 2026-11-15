/*
 * Copyright 2011 Azwan Adli Abdullah — ported to GitLab by SecNinjaz OctoLab team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Pair;
import androidx.annotation.Nullable;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabSnippet;
import com.gl4a.gitlab.service.GitLabSnippetService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.DownloadUtils;
import com.gl4a.utils.FileUtils;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.StringUtils;

import java.io.FileNotFoundException;

import io.reactivex.Single;

/**
 * Viewer for a single GitLab Snippet file (equivalent of GitHub Gist file viewer).
 */
public class GistViewerActivity extends WebViewerActivity {
    public static Intent makeIntent(Context context, String id, String fileName) {
        return new Intent(context, GistViewerActivity.class)
                .putExtra("id", id)
                .putExtra("file", fileName);
    }

    private static final int ID_LOADER_SNIPPET = 0;

    private String mFileName;
    private String mSnippetId;
    // The project of a project snippet, "group/project"; null for a personal snippet (#324)
    @Nullable
    private String mProjectPath;
    private GitLabSnippet.SnippetFile mSnippetFile;
    private String mSnippetOwner;
    // Loaded raw content for the snippet file
    private String mRawContent;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSnippet(false);
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return mFileName;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mFileName = extras.getString("file");
        mSnippetId = extras.getString("id");
        mProjectPath = extras.getString("project");
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return true;
    }

    @Override
    public void onRefresh() {
        setContentShown(false);
        mSnippetFile = null;
        mRawContent = null;
        loadSnippet(true);
        super.onRefresh();
    }

    @Override
    protected String generateHtml(String cssTheme, boolean addTitleHeader) {
        String content = mRawContent != null ? mRawContent : "";
        String filename = mSnippetFile != null ? mSnippetFile.filename() : mFileName;
        if (FileUtils.isMarkdown(filename)) {
            String base64Data = StringUtils.toBase64(content);
            return generateMarkdownHtml(base64Data, null, null, null, null, cssTheme, addTitleHeader);
        } else {
            return generateCodeHtml(content, mFileName, -1, -1, cssTheme, addTitleHeader);
        }
    }

    @Override
    protected String getDocumentTitle() {
        return getString(R.string.gist_print_document_title, mFileName, mSnippetId, mSnippetOwner);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.file_viewer_menu, menu);

        menu.removeItem(R.id.share);
        String filename = mSnippetFile != null ? mSnippetFile.filename() : mFileName;
        if (mSnippetFile == null || FileUtils.isMarkdown(filename)) {
            menu.removeItem(R.id.wrap);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    protected Intent navigateUp() {
        long snippetIdLong2;
        try { snippetIdLong2 = Long.parseLong(mSnippetId); } catch (NumberFormatException e) { snippetIdLong2 = 0; }
        return mProjectPath != null
                ? GistActivity.makeIntent(this, mProjectPath, snippetIdLong2)
                : GistActivity.makeIntent(this, snippetIdLong2);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.browser:
                if (mSnippetFile != null && mSnippetFile.rawUrl() != null) {
                    IntentUtils.launchBrowser(this, Uri.parse(mSnippetFile.rawUrl()));
                }
                return true;
            case R.id.download:
                if (mSnippetFile != null) {
                    DownloadUtils.enqueueDownloadWithPermissionCheck(this, mSnippetFile.rawUrl(),
                            mSnippetFile.type(), mSnippetFile.filename(), null);
                }
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadSnippet(boolean force) {
        GitLabSnippetService service = ServiceFactory.get(GitLabSnippetService.class, force);
        long snippetIdLong;
        try { snippetIdLong = Long.parseLong(mSnippetId); } catch (NumberFormatException e) { snippetIdLong = 0; }
        final long finalId = snippetIdLong;
        final String project = mProjectPath != null ? Uri.encode(mProjectPath) : null;
        // The snippet and then its file's content, both in the background: the content was
        // asked for on the main thread, where Android refuses network calls, so every snippet
        // file showed blank (#324)
        GistActivity.loadSnippet(service, mProjectPath, finalId)
                .map(ApiHelpers::throwOnFailure)
                .flatMap(result -> {
                    GitLabSnippet.SnippetFile file =
                            result.files() != null ? result.files().get(mFileName) : null;
                    if (file == null) {
                        // File not found in snippet map — could be stale cache or name mismatch
                        return Single.<Pair<GitLabSnippet, String>>error(
                                new FileNotFoundException("File not found: " + mFileName));
                    }
                    return rawContent(service, project, finalId, result, file)
                            .map(raw -> Pair.create(result, raw));
                })
                .compose(makeLoaderSingle(ID_LOADER_SNIPPET, force))
                .subscribe(loaded -> {
                    mSnippetOwner = ApiHelpers.getUserLogin(GistViewerActivity.this,
                            loaded.first.owner());
                    mSnippetFile = loaded.first.files().get(mFileName);
                    mRawContent = loaded.second;
                    onDataReady();
                }, this::handleLoadFailure);
    }

    /** The file's content, or "" when GitLab doesn't give it. */
    private static Single<String> rawContent(GitLabSnippetService service,
            @Nullable String project, long id, GitLabSnippet snippet,
            GitLabSnippet.SnippetFile file) {
        Single<retrofit2.Response<okhttp3.ResponseBody>> raw = null;
        // For multi-file snippets (GitLab 14.0+), GET /snippets/:id/raw returns only the first
        // file: use the per-file endpoint, from the file's raw_url,
        // .../snippets/:id/files/:ref/:encoded_path/raw
        String rawUrl = file.rawUrl();
        boolean isMultiFile = snippet.files != null && snippet.files.size() > 1;
        if (isMultiFile && rawUrl != null && rawUrl.contains("/files/")) {
            String afterFiles = rawUrl.substring(rawUrl.indexOf("/files/") + 7);
            int slashAfterRef = afterFiles.indexOf('/');
            if (slashAfterRef > 0) {
                String ref = afterFiles.substring(0, slashAfterRef);
                String encodedPath = afterFiles.substring(slashAfterRef + 1);
                if (encodedPath.endsWith("/raw")) {
                    encodedPath = encodedPath.substring(0, encodedPath.length() - 4);
                }
                raw = project != null
                        ? service.getProjectSnippetFileRaw(project, id, ref, encodedPath)
                        : service.getSnippetFileRaw(id, ref, encodedPath);
            }
        }
        // Single-file snippet, or no per-file address
        if (raw == null) {
            raw = project != null
                    ? service.getRawProjectSnippet(project, id)
                    : service.getRawSnippet(id);
        }
        return raw.map(response -> response.isSuccessful() && response.body() != null
                        ? response.body().string() : "")
                .onErrorReturnItem("");
    }
}
