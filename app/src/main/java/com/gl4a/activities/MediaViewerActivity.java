package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.MediaController;
import android.widget.VideoView;
import androidx.annotation.Nullable;

import com.gl4a.BaseActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays a video or audio attachment in-app. The platform player streams it with range
 * requests and the account token, so nothing is downloaded whole and no browser login is
 * needed. A WebView &lt;video&gt; didn't play: its requests went through the app's proxy,
 * which can't serve media reliably.
 */
public class MediaViewerActivity extends BaseActivity implements
        MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener {
    public static Intent makeIntent(Context context, Uri uri) {
        return new Intent(context, MediaViewerActivity.class)
                .putExtra("url", uri.toString());
    }

    private static final String STATE_POSITION = "position";

    private VideoView mVideoView;
    private View mProgress;
    private String[] mUrls;
    private int mUrlIndex;
    private int mResumePosition;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.media_viewer);

        String url = getIntent().getStringExtra("url");
        // The uploads API first, like images; the web URL still works for public uploads
        // when the API refuses the request.
        String apiUrl = url.replaceAll("/-/project/(\\d+)/uploads/", "/api/v4/projects/$1/uploads/");
        mUrls = apiUrl.equals(url) ? new String[] { url } : new String[] { apiUrl, url };
        if (savedInstanceState != null) {
            mResumePosition = savedInstanceState.getInt(STATE_POSITION);
        }

        mVideoView = findViewById(R.id.video_view);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(mVideoView);
        mVideoView.setMediaController(controller);
        mVideoView.setOnPreparedListener(this);
        mVideoView.setOnErrorListener(this);

        // Not setContentShown(false): that hides the VideoView, whose surface is then never
        // created, so the video never opens and the loading bar runs forever.
        mProgress = findViewById(R.id.media_progress);
        play();
    }

    private void play() {
        Uri uri = Uri.parse(mUrls[mUrlIndex]);
        Map<String, String> headers = new HashMap<>();
        String instanceHost = Uri.parse(Gl4Application.get().getInstanceUrl()).getHost();
        String tok = Gl4Application.get().getAuthToken();
        if (tok != null && instanceHost != null && instanceHost.equalsIgnoreCase(uri.getHost())) {
            headers.put("PRIVATE-TOKEN", tok);
        }
        mVideoView.setVideoURI(uri, headers);
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mProgress.setVisibility(View.GONE);
        if (mResumePosition > 0) {
            mVideoView.seekTo(mResumePosition);
        }
        mVideoView.start();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        if (mUrlIndex + 1 < mUrls.length) {
            mUrlIndex++;
            play();
        } else {
            mProgress.setVisibility(View.GONE);
            handleLoadFailure(new IOException(getString(R.string.media_not_playable)));
        }
        return true;
    }

    @Override
    protected void onPause() {
        if (mVideoView.isPlaying()) {
            mResumePosition = mVideoView.getCurrentPosition();
            mVideoView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_POSITION, mVideoView.isPlaying()
                ? mVideoView.getCurrentPosition() : mResumePosition);
    }

    @Override
    protected void onDestroy() {
        mVideoView.stopPlayback();
        super.onDestroy();
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return Uri.parse(getIntent().getStringExtra("url")).getLastPathSegment();
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return false;
    }
}
