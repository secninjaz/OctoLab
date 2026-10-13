package com.gl4a.activities;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.utils.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import io.reactivex.Single;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Shows a file attached to an issue, merge request or comment (a GitLab upload link) in-app,
 * so it never needs a browser login. Images load in the WebView, whose request proxy adds
 * the account token; videos and audio play in {@link MediaViewerActivity}; text files are
 * shown as text; other files are downloaded with the token and handed to an installed app.
 */
public class AttachmentViewerActivity extends WebViewerActivity {
    public static Intent makeIntent(Context context, Uri uri) {
        return makeIntent(context, uri, uri.getLastPathSegment());
    }

    /**
     * For links whose last segment isn't the file name, e.g. the API's raw repository files
     * (".../files/docs%2Freport.pdf/raw?ref=main"), so the type and saved name are right (#245).
     */
    public static Intent makeIntent(Context context, Uri uri, String fileName) {
        String mimeType = FileUtils.getMimeTypeFor(fileName);
        if (mimeType != null && (mimeType.startsWith("video/") || mimeType.startsWith("audio/"))) {
            return MediaViewerActivity.makeIntent(context, uri, fileName);
        }
        return new Intent(context, AttachmentViewerActivity.class)
                .putExtra("url", uri.toString())
                .putExtra("file_name", fileName);
    }

    /** Larger text files are handed to another app instead of being shown here. */
    private static final int MAX_TEXT_BYTES = 1024 * 1024;

    private String mUrl;
    private String mFileName;
    private String mMimeType;
    private String mText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isImage()) {
            onDataReady();
        } else {
            loadFile();
        }
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mUrl = extras.getString("url");
        mFileName = extras.getString("file_name", Uri.parse(mUrl).getLastPathSegment());
        mMimeType = FileUtils.getMimeTypeFor(mFileName);
    }

    private boolean isImage() {
        return mMimeType != null && mMimeType.startsWith("image/");
    }

    /** Only files known to be text: an unknown type (e.g. .AppImage) is opened, not shown. */
    private boolean isText() {
        return mMimeType != null && !FileUtils.isBinaryFormat(mFileName);
    }

    private void saveToDownloads() {
        // The uploads API link for attachments, like download(); other links as they are
        String url = mUrl.replaceAll("/-/project/(\\d+)/uploads/", "/api/v4/projects/$1/uploads/");
        // Closed once queued; on Android 9 and older that may follow a permission prompt
        com.gl4a.utils.DownloadUtils.enqueueAuthenticatedDownload(this, url, mFileName, mMimeType,
                this::finish);
    }

    private void loadFile() {
        Single.fromCallable(this::download)
                .compose(makeLoaderSingle(0, false))
                .subscribe(file -> {
                    if (isText() && file.length() <= MAX_TEXT_BYTES) {
                        mText = new String(readAll(file), "UTF-8");
                        onDataReady();
                    } else {
                        openWithApp(file);
                    }
                }, this::handleLoadFailure);
    }

    /** Downloads the file into the cache, via the uploads API first, then its web URL. */
    private File download() throws IOException {
        String apiUrl = mUrl.replaceAll("/-/project/(\\d+)/uploads/", "/api/v4/projects/$1/uploads/");
        IOException failure = null;
        for (String url : apiUrl.equals(mUrl) ? new String[] { mUrl } : new String[] { apiUrl, mUrl }) {
            try {
                return downloadFrom(url);
            } catch (IOException e) {
                failure = e;
            }
        }
        throw failure;
    }

    private File downloadFrom(String url) throws IOException {
        String tok = Gl4Application.get().getAuthToken();
        Request request = new Request.Builder()
                .url(url)
                .header("PRIVATE-TOKEN", tok != null ? tok : "")
                .build();
        try (Response resp = ServiceFactory.getImageHttpClient().newCall(request).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException(resp.code() + " " + resp.message());
            }
            File dir = new File(getCacheDir(), "attachments");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Can't create " + dir);
            }
            File file = new File(dir, mFileName);
            long total = resp.body().contentLength();
            try (InputStream in = resp.body().byteStream();
                    OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                long done = 0, shownAt = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    // Large files take a while: show how far it is, not just a moving bar (#245)
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - shownAt > 300) {
                        shownAt = now;
                        showProgress(done, total);
                    }
                }
            }
            return file;
        }
    }

    private static byte[] readAll(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int offset = 0;
            int read;
            while (offset < bytes.length && (read = in.read(bytes, offset, bytes.length - offset)) != -1) {
                offset += read;
            }
        }
        return bytes;
    }

    /**
     * Every file the same way, as the system decides (#245): "Open with" the apps that can open
     * it; if none can (.deb, .AppImage…), the share sheet, which offers "Save to Files", Drive
     * and the like; only if there's nothing at all, a download to Downloads.
     */
    private void openWithApp(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        String type = mMimeType != null ? mMimeType : "application/octet-stream";
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(type)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent target = hasHandler(view) ? view : hasHandler(send) ? send : null;
        if (target == null) {
            saveToDownloads();
            return;
        }
        try {
            startActivity(Intent.createChooser(target, mFileName));
        } catch (ActivityNotFoundException e) {
            saveToDownloads();
            return;
        }
        finish();
    }

    private boolean hasHandler(Intent intent) {
        return !getPackageManager().queryIntentActivities(intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
    }

    /** "Downloading 45 MB of 173 MB" under the file name while it downloads. */
    private void showProgress(long done, long total) {
        runOnUiThread(() -> {
            if (getSupportActionBar() == null) return;
            String doneText = android.text.format.Formatter.formatShortFileSize(this, done);
            getSupportActionBar().setSubtitle(total > 0
                    ? getString(R.string.download_progress, doneText,
                            android.text.format.Formatter.formatShortFileSize(this, total))
                    : getString(R.string.download_progress_unknown, doneText));
        });
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return mFileName;
    }

    @Override
    protected boolean canSwipeToRefresh() {
        // images are loaded by the WebView itself, text once on open
        return false;
    }

    @Override
    protected String generateHtml(String cssTheme, boolean addTitleHeader) {
        String src = TextUtils.htmlEncode(mUrl);
        String html;
        if (mText != null) {
            html = "<pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"
                    + TextUtils.htmlEncode(mText) + "</pre>";
        } else {
            html = "<img src=\"" + src + "\" style=\"max-width:100%;height:auto;\">";
        }
        return wrapWithMarkdownStyling(html, cssTheme, addTitleHeader ? getDocumentTitle() : null);
    }

    @Override
    protected String getDocumentTitle() {
        return mFileName;
    }
}
