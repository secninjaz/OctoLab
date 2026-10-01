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
        String mimeType = FileUtils.getMimeTypeFor(uri.getLastPathSegment());
        if (mimeType != null && (mimeType.startsWith("video/") || mimeType.startsWith("audio/"))) {
            return MediaViewerActivity.makeIntent(context, uri);
        }
        return new Intent(context, AttachmentViewerActivity.class)
                .putExtra("url", uri.toString());
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
        mFileName = Uri.parse(mUrl).getLastPathSegment();
        mMimeType = FileUtils.getMimeTypeFor(mFileName);
    }

    private boolean isImage() {
        return mMimeType != null && mMimeType.startsWith("image/");
    }

    private boolean isText() {
        return !FileUtils.isBinaryFormat(mFileName);
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
            try (InputStream in = resp.body().byteStream();
                    OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
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

    private void openWithApp(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mMimeType != null ? mMimeType : "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, mFileName));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.link_not_openable, Toast.LENGTH_SHORT).show();
        }
        finish();
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
