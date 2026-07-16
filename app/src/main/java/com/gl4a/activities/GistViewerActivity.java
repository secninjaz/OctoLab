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
        return GistActivity.makeIntent(this, snippetIdLong2);
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
        service.getSnippet(finalId)
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_SNIPPET, force))
                .subscribe(result -> {
                    mSnippetOwner = ApiHelpers.getUserLogin(GistViewerActivity.this, result.owner());
                    mSnippetFile = result.files() != null ? result.files().get(mFileName) : null;
                    if (mSnippetFile == null) {
                        // File not found in snippet map — could be stale cache or name mismatch
                        handleLoadFailure(new FileNotFoundException("File not found: " + mFileName));
                        return;
                    }

                    // Fix: For multi-file snippets (GitLab 14.0+), GET /snippets/:id/raw returns
                    // only the first file.  Use the per-file endpoint when raw_url is available,
                    // which resolves to /snippets/:id/files/:ref/:path/raw.
                    // The rawUrl on SnippetFile already contains the per-file URL we can use directly.
                    boolean hasPerFileRawUrl = mSnippetFile.rawUrl() != null
                            && !mSnippetFile.rawUrl().isEmpty();
                    boolean isMultiFile = result.files != null && result.files.size() > 1;

                    if (isMultiFile && hasPerFileRawUrl) {
                        // Derive ref and encoded path from rawUrl pattern:
                        // .../snippets/:id/files/:ref/:encoded_path/raw
                        String rawUrl = mSnippetFile.rawUrl();
                        int filesIdx = rawUrl.indexOf("/files/");
                        if (filesIdx >= 0) {
                            String afterFiles = rawUrl.substring(filesIdx + 7); // strip "/files/"
                            int slashAfterRef = afterFiles.indexOf('/');
                            if (slashAfterRef > 0) {
                                String ref = afterFiles.substring(0, slashAfterRef);
                                // The rest is "encoded_path/raw" — strip trailing "/raw"
                                String encodedPathWithRaw = afterFiles.substring(slashAfterRef + 1);
                                String encodedPath = encodedPathWithRaw.endsWith("/raw")
                                        ? encodedPathWithRaw.substring(0, encodedPathWithRaw.length() - 4)
                                        : encodedPathWithRaw;
                                service.getSnippetFileRaw(finalId, ref, encodedPath)
                                        .subscribe(rawResponse -> {
                                            mRawContent = rawResponse.isSuccessful()
                                                    && rawResponse.body() != null
                                                    ? rawResponse.body().string() : "";
                                            onDataReady();
                                        }, error -> { mRawContent = ""; onDataReady(); });
                                return;
                            }
                        }
                    }

                    // Fallback: single-file snippet or no per-file URL available
                    service.getRawSnippet(finalId)
                            .subscribe(rawResponse -> {
                                mRawContent = rawResponse.isSuccessful()
                                        && rawResponse.body() != null
                                        ? rawResponse.body().string() : "";
                                onDataReady();
                            }, error -> { mRawContent = ""; onDataReady(); });
                }, this::handleLoadFailure);
    }
}
