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
package com.gl4a.activities;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.print.PrintHelper;
import androidx.appcompat.widget.PopupMenu;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabFile;
import com.gl4a.gitlab.service.GitLabRepositoryService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.DownloadUtils;
import com.gl4a.utils.FileUtils;
import com.gl4a.utils.HtmlUtils;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.SingleFactory;
import com.gl4a.utils.StringUtils;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class FileViewerActivity extends WebViewerActivity
        implements PopupMenu.OnMenuItemClickListener {
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            String ref, String fullPath) {
        return makeIntent(context, repoOwner, repoName, ref, fullPath, -1, -1);
    }

    public static Intent makeIntentWithHighlight(Context context, String repoOwner, String repoName,
            String ref, String fullPath, int highlightStart, int highlightEnd) {
        return makeIntent(context, repoOwner, repoName, ref, fullPath, highlightStart, highlightEnd);
    }

    private static Intent makeIntent(Context context, String repoOwner, String repoName, String ref,
            String fullPath, int highlightStart, int highlightEnd) {
        return new Intent(context, FileViewerActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("path", fullPath)
                .putExtra("ref", ref)
                .putExtra("highlight_start", highlightStart)
                .putExtra("highlight_end", highlightEnd);
    }

    private String mRepoName;
    private String mRepoOwner;
    private String mPath;
    private String mRef;
    private int mHighlightStart;
    private int mHighlightEnd;
    private GitLabFile mContent;
    // For markdown files: base64-encoded content with relative images pre-embedded as data URIs.
    private String mProcessedMarkdownBase64;
    private int mLastTouchedLine = 0;
    private boolean mViewRawText;

    private static final int ID_LOADER_FILE = 0;
    private static final int MENU_ITEM_HISTORY = 10;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String filename = FileUtils.getFileName(mPath);
        if (FileUtils.isBinaryFormat(filename) && !FileUtils.isImage(filename)) {
            openUnsuitableFileAndFinish();
        } else {
            loadFile(false);
        }
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return FileUtils.getFileName(mPath);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mRepoOwner + "/" + mRepoName;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mPath = extras.getString("path");
        mRef = extras.getString("ref");
        mHighlightStart = extras.getInt("highlight_start", -1);
        mHighlightEnd = extras.getInt("highlight_end", -1);
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return true;
    }

    @Override
    public void onRefresh() {
        setContentShown(false);
        loadFile(true);
        super.onRefresh();
    }

    @Override
    protected String generateHtml(String cssTheme, boolean addTitleHeader) {
        String base64Data = mContent.content();
        if (base64Data != null && FileUtils.isImage(mPath)) {
            String title = addTitleHeader ? getDocumentTitle() : null;
            String imageUrl = "data:" + FileUtils.getMimeTypeFor(mPath) +
                    ";base64," + base64Data;
            return highlightImage(imageUrl, cssTheme, title);
        } else if (base64Data != null && FileUtils.isMarkdown(mPath) && !mViewRawText) {
            String folderPath = FileUtils.getFolderPath(mPath);
            // Use preprocessed content (images embedded as data URIs) if available.
            String markdownBase64 = mProcessedMarkdownBase64 != null
                    ? mProcessedMarkdownBase64 : base64Data;
            return generateMarkdownHtml(markdownBase64,
                    mRepoOwner, mRepoName, mRef, folderPath, cssTheme, addTitleHeader);
        } else {
            String data = base64Data != null ? StringUtils.fromBase64(base64Data) : "";
            return generateCodeHtml(data, mPath,
                    mHighlightStart, mHighlightEnd, cssTheme, addTitleHeader);
        }
    }

    @Override
    protected String getDocumentTitle() {
        @StringRes int titleResId = TextUtils.isEmpty(mRef)
                ? R.string.file_print_document_title : R.string.file_print_document_at_ref_title;
        return getString(titleResId, FileUtils.getFileName(mPath), mRepoOwner, mRepoName, mRef);
    }

    @Override
    protected boolean handlePrintRequest() {
        if (!FileUtils.isImage(mPath)) {
            return false;
        }
        String base64Data = mContent != null ? mContent.content() : null;
        if (base64Data == null) {
            return false;
        }
        byte[] decodedData = Base64.decode(base64Data, Base64.DEFAULT);
        Bitmap bitmap = BitmapFactory.decodeByteArray(decodedData, 0, decodedData.length);

        PrintHelper printHelper = new PrintHelper(this);
        printHelper.setScaleMode(PrintHelper.SCALE_MODE_FIT);
        printHelper.printBitmap(getDocumentTitle(), bitmap);
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.file_viewer_menu, menu);

        boolean isMarkdown = FileUtils.isMarkdown(mPath);
        if (FileUtils.isImage(mPath) || (isMarkdown && !mViewRawText)) {
            menu.removeItem(R.id.wrap);
        }
        if (isMarkdown) {
            MenuItem viewRawItem = menu.findItem(R.id.view_raw);
            viewRawItem.setChecked(mViewRawText);
            viewRawItem.setVisible(true);
        }

        menu.add(0, MENU_ITEM_HISTORY, Menu.NONE, R.string.history)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // GitLab blob URL: <host>/<owner>/<repo>/-/blob/<ref>/<path>
        Uri.Builder urlBuilder = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("-")
                .appendPath("blob")
                .appendPath(mRef);
        for (String element : mPath.split("\\/")) {
            urlBuilder.appendPath(element);
        }
        Uri url = urlBuilder.build();

        switch (item.getItemId()) {
            case R.id.browser:
                IntentUtils.launchBrowser(this, url);
                return true;
            case R.id.download:
                DownloadUtils.enqueueDownloadWithPermissionCheck(this, buildRawFileUrl(),
                        FileUtils.getMimeTypeFor(mPath), FileUtils.getFileName(mPath), null);
                return true;
            case R.id.share:
                IntentUtils.share(this, getString(R.string.share_file_subject,
                        FileUtils.getFileName(mPath), mRepoOwner + "/" + mRepoName), url);
                return true;
            case MENU_ITEM_HISTORY:
                startActivity(CommitHistoryActivity.makeIntent(this,
                        mRepoOwner, mRepoName, mRef, mPath,
                        mContent != null ? mContent.type() : null, false));
                return true;
            case R.id.view_raw:
                mViewRawText = !mViewRawText;
                item.setChecked(mViewRawText);
                onRefresh();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected Intent navigateUp() {
        return RepositoryActivity.makeIntent(this, mRepoOwner, mRepoName);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.share:
                if (mLastTouchedLine > 0) {
                    String subject = getString(R.string.share_line_subject, mLastTouchedLine, mPath,
                            mRepoOwner + "/" + mRepoName);
                    IntentUtils.share(this, subject, createUrl());
                }
                return true;
        }
        return false;
    }

    @Override
    protected void onLineTouched(int line, int x, int y) {
        super.onLineTouched(line, x, y);

        mLastTouchedLine = line;

        View anchor = findViewById(R.id.popup_helper);
        anchor.layout(x, y, x + 1, y + 1);
        if (!isFinishing()) {
            PopupMenu popupMenu = new PopupMenu(this, anchor);
            popupMenu.getMenuInflater().inflate(R.menu.file_line_menu, popupMenu.getMenu());
            popupMenu.show();
            popupMenu.setOnMenuItemClickListener(this);
        }
    }

    @Override
    protected boolean shouldWrapLines() {
        boolean displayingMarkdown = FileUtils.isMarkdown(mPath) && !mViewRawText;
        return !displayingMarkdown && super.shouldWrapLines();
    }

    private Uri createUrl() {
        Uri.Builder builder = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("-")
                .appendPath("blob")
                .appendPath(mRef);
        for (String element : mPath.split("\\/")) {
            builder.appendPath(element);
        }
        builder.fragment("L" + mLastTouchedLine);
        return builder.build();
    }

    private String buildRawFileUrl() {
        return IntentUtils.createRawFileUrl(mRepoOwner, mRepoName, mRef, mPath);
    }

    private void openUnsuitableFileAndFinish() {
        Uri uri = Uri.parse(buildRawFileUrl());
        String mime = FileUtils.getMimeTypeFor(FileUtils.getFileName(mPath));
        Intent intent = IntentUtils.createViewerOrBrowserIntent(this, uri, mime);
        if (intent == null) {
            handleLoadFailure(new ActivityNotFoundException());
            findViewById(R.id.retry_button).setVisibility(View.GONE);
        } else {
            startActivity(intent);
            finish();
        }
    }

    private static String highlightImage(String imageUrl, String cssTheme, String title) {
        StringBuilder content = new StringBuilder();
        content.append("<html><head>");
        HtmlUtils.writeCssInclude(content, "markdown", cssTheme);
        content.append("</head><body>");
        if (title != null) {
            content.append("<h2>").append(title).append("</h2>");
        }
        content.append("<img src='").append(imageUrl).append("' />");
        content.append("</body></html>");
        return content.toString();
    }

    private void loadFile(boolean force) {
        mProcessedMarkdownBase64 = null;
        // Resolve project ID first, then fetch file content via GitLabRepositoryService.
        SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> {
                    GitLabRepositoryService service =
                            ServiceFactory.get(GitLabRepositoryService.class, force);
                    String encodedPath = android.net.Uri.encode(mPath, "");
                    return service.getFile(projectId, encodedPath, mRef)
                            .map(ApiHelpers::throwOnFailure);
                })
                .flatMap(gitLabFile -> {
                    // Pre-embed images in markdown files on IO thread so WebView can
                    // display them without needing auth headers.
                    if (FileUtils.isMarkdown(mPath) && gitLabFile.content() != null) {
                        return Single.fromCallable(() -> {
                            String markdown = StringUtils.fromBase64(gitLabFile.content());
                            String processed = embedMarkdownImages(markdown);
                            mProcessedMarkdownBase64 = Base64.encodeToString(
                                    processed.getBytes("UTF-8"), Base64.DEFAULT);
                            return gitLabFile;
                        }).subscribeOn(Schedulers.io());
                    }
                    return Single.just(gitLabFile);
                })
                .compose(makeLoaderSingle(ID_LOADER_FILE, force))
                .subscribe(result -> {
                    mContent = result;
                    // GitLab returns empty content string when file is too large
                    boolean fileContentIsMissing = mContent.size() > 0
                            && Objects.equals(mContent.content(), "");
                    if (fileContentIsMissing) {
                        openUnsuitableFileAndFinish();
                    } else {
                        onDataReady();
                        setContentEmpty(false);
                    }
                }, error -> {
                    setContentEmpty(true);
                    setContentShown(true);
                    handleLoadFailure(error);
                });
    }

    /**
     * Finds relative image references in markdown and replaces them with base64 data URIs
     * fetched via the GitLab API with PRIVATE-TOKEN auth. Runs on IO thread.
     */
    private String embedMarkdownImages(String markdown) {
        if (markdown == null) return "";
        String tok = com.gl4a.Gl4Application.get().getAuthToken();
        String ref = mRef != null ? mRef : "main";

        // Pass 1: markdown image syntax ![alt](relative/path)
        Pattern mdImg = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
        Matcher m = mdImg.matcher(markdown);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String alt = m.group(1);
            String url = m.group(2).trim();
            if (!url.startsWith("http") && !url.startsWith("data:") && !url.startsWith("#")) {
                String dataUri = fetchDataUri(buildApiUrl(url, ref), tok);
                if (dataUri != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            "![" + alt + "](" + dataUri + ")"));
                    continue;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        String result = sb.toString();

        // Pass 2: raw HTML <img src="relative/path"> or <img src='...'>
        // These are preserved by showdown.js as-is, so must be pre-processed too.
        Pattern htmlImg = Pattern.compile(
                "(<img[^>]*\\ssrc=)[\"']([^\"']+)[\"']([^>]*>)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m2 = htmlImg.matcher(result);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            String before = m2.group(1);
            String url = m2.group(2).trim();
            String after = m2.group(3);
            if (!url.startsWith("http") && !url.startsWith("data:") && !url.startsWith("#")) {
                String dataUri = fetchDataUri(buildApiUrl(url, ref), tok);
                if (dataUri != null) {
                    m2.appendReplacement(sb2, Matcher.quoteReplacement(
                            before + "\"" + dataUri + "\"" + after));
                    continue;
                }
            }
            m2.appendReplacement(sb2, Matcher.quoteReplacement(m2.group(0)));
        }
        m2.appendTail(sb2);
        return sb2.toString();
    }

    private String buildApiUrl(String relativePath, String ref) {
        String instanceUrl = com.gl4a.Gl4Application.get().getInstanceUrl();
        // Strip leading ./
        if (relativePath.startsWith("./")) relativePath = relativePath.substring(2);
        return instanceUrl + "/api/v4/projects/"
                + android.net.Uri.encode(mRepoOwner) + "%2F"
                + android.net.Uri.encode(mRepoName)
                + "/repository/files/"
                + relativePath.replace("/", "%2F")
                + "/raw?ref=" + android.net.Uri.encode(ref);
    }

    private String fetchDataUri(String urlStr, String tok) {
        try {
            okhttp3.OkHttpClient client = com.gl4a.ServiceFactory.getImageHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(urlStr)
                    .header("PRIVATE-TOKEN", tok != null ? tok : "")
                    .build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                byte[] bytes = resp.body().bytes();
                if (bytes.length == 0) return null;
                String ct = resp.header("Content-Type", "");
                if (ct == null || ct.startsWith("application/octet-stream")
                        || ct.startsWith("text/html")) {
                    ct = java.net.URLConnection.guessContentTypeFromName(urlStr);
                }
                if (ct == null) ct = "image/png";
                int semi = ct.indexOf(';');
                if (semi > 0) ct = ct.substring(0, semi).trim();
                return "data:" + ct + ";base64,"
                        + Base64.encodeToString(bytes, Base64.NO_WRAP);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
