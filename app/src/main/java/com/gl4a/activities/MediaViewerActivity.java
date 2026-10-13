package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.MediaController;
import android.widget.VideoView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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
        return makeIntent(context, uri, null);
    }

    /**
     * @param fileName the title; the address's last part when null. A repository file opens
     *                 through GitLab's file download address, which ends in "raw" (#261).
     */
    public static Intent makeIntent(Context context, Uri uri, @Nullable String fileName) {
        return new Intent(context, MediaViewerActivity.class)
                .putExtra("url", uri.toString())
                .putExtra("file_name", fileName);
    }

    private static final String STATE_POSITION = "position";
    private static final String STATE_FULL_SCREEN = "full_screen";

    private VideoView mVideoView;
    private View mProgress;
    private String[] mUrls;
    private int mUrlIndex;
    private int mResumePosition;
    // Full screen (#259): chosen with the button, or entered by turning a horizontal video to
    // landscape (then left again by turning back to portrait)
    private boolean mFullScreen;
    private boolean mFullScreenByRotation;
    private boolean mHorizontalVideo;
    private ImageButton mFullScreenButton;
    private OnBackPressedCallback mExitFullScreenOnBack;

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
            mFullScreen = savedInstanceState.getBoolean(STATE_FULL_SCREEN);
        }

        mVideoView = findViewById(R.id.video_view);
        View root = findViewById(R.id.media_root);
        // The full-screen button sits in the playback controls, bottom right, so it shows and
        // hides with them; anchored to the whole screen so the controls span it (#259)
        mFullScreenButton = new ImageButton(this);
        mFullScreenButton.setBackgroundResource(android.R.color.transparent);
        mFullScreenButton.setVisibility(View.GONE);
        mFullScreenButton.setOnClickListener(v -> {
            mFullScreenByRotation = false;
            setFullScreen(!mFullScreen);
        });
        MediaController controller = new MediaController(this) {
            @Override
            public void setAnchorView(View view) {
                super.setAnchorView(view);
                if (mFullScreenButton.getParent() == null) {
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
                    addView(mFullScreenButton, lp);
                }
            }
        };
        controller.setAnchorView(root);
        mVideoView.setMediaController(controller);
        // A tap on the video or the black around it shows or hides the controls. The video
        // view's own toggle ran on touch down and the screen's on release, so a tap showed
        // and hid them at once (#259)
        View.OnClickListener toggleControls = v -> {
            if (controller.isShowing()) {
                controller.hide();
            } else {
                controller.show();
            }
        };
        root.setOnClickListener(toggleControls);
        mVideoView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                v.performClick();
                toggleControls.onClick(v);
            }
            return true;
        });
        mExitFullScreenOnBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                mFullScreenByRotation = false;
                setFullScreen(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, mExitFullScreenOnBack);
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
        // Audio has no picture: no full-screen button
        boolean hasPicture = mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0;
        // Square counts as vertical: landscape doesn't make it full screen
        mHorizontalVideo = mp.getVideoWidth() > mp.getVideoHeight();
        mFullScreenButton.setVisibility(hasPicture ? View.VISIBLE : View.GONE);
        if (hasPicture) {
            applyOrientation(getResources().getConfiguration().orientation);
            setFullScreen(mFullScreen);
        }
        if (mResumePosition > 0) {
            mVideoView.seekTo(mResumePosition);
        }
        mVideoView.start();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientation(newConfig.orientation);
    }

    /**
     * A horizontal video goes full screen in landscape, and leaves it in portrait when that's
     * how it got there; vertical and square videos just stay fitted (#259).
     */
    private void applyOrientation(int orientation) {
        if (!mHorizontalVideo) {
            return;
        }
        if (orientation == Configuration.ORIENTATION_LANDSCAPE && !mFullScreen) {
            mFullScreenByRotation = true;
            setFullScreen(true);
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT && mFullScreenByRotation) {
            mFullScreenByRotation = false;
            setFullScreen(false);
        }
    }

    /** Full screen hides the app bar and the system bars; a swipe shows the bars briefly. */
    private void setFullScreen(boolean fullScreen) {
        mFullScreen = fullScreen;
        setAppBarShown(!fullScreen);
        // Drawn behind where the bars were, so the whole screen is the video's; otherwise their
        // space stayed as light bands above and below it
        WindowCompat.setDecorFitsSystemWindows(getWindow(), !fullScreen);
        WindowInsetsControllerCompat bars =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (fullScreen) {
            bars.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars());
        }
        // In full screen the video may also use the camera cutout's edge of the screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = fullScreen
                    ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            getWindow().setAttributes(attrs);
        }
        mFullScreenButton.setImageResource(fullScreen
                ? R.drawable.fullscreen_exit : R.drawable.fullscreen);
        mFullScreenButton.setContentDescription(getString(fullScreen
                ? R.string.media_exit_full_screen : R.string.media_full_screen));
        mExitFullScreenOnBack.setEnabled(fullScreen);
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
        outState.putBoolean(STATE_FULL_SCREEN, mFullScreen && !mFullScreenByRotation);
    }

    @Override
    protected void onDestroy() {
        mVideoView.stopPlayback();
        super.onDestroy();
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        String fileName = getIntent().getStringExtra("file_name");
        return fileName != null
                ? fileName : Uri.parse(getIntent().getStringExtra("url")).getLastPathSegment();
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return false;
    }
}
