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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;
import androidx.appcompat.widget.PopupMenu;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.fragment.ConfirmationDialogFragment;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.FileUtils;
import com.gl4a.utils.HtmlUtils;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * Base diff viewer activity. The generic type C is the comment type used for diff annotations.
 * For GitLab, both commit diffs and MR diffs use {@link GitLabComment}.
 */
public abstract class DiffViewerActivity<C extends GitLabComment> extends WebViewerActivity
        implements ConfirmationDialogFragment.Callback {

    protected static <C extends GitLabComment> Intent fillInIntent(Intent baseIntent,
            String repoOwner, String repoName, String commitSha, String path, String diff,
            List<C> comments, int initialLine, int highlightStartLine, int highlightEndLine,
            boolean highlightisRight, IntentUtils.InitialCommentMarker initialComment) {
        Intent intent = baseIntent.putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("sha", commitSha)
                .putExtra("path", path)
                .putExtra("diff", diff)
                .putExtra("initial_line", initialLine)
                .putExtra("highlight_start", highlightStartLine)
                .putExtra("highlight_end", highlightEndLine)
                .putExtra("highlight_right", highlightisRight)
                .putExtra("initial_comment", initialComment);
        if (comments != null) {
            IntentUtils.putCompressedExtra(intent, "comments", comments);
        }
        return intent;
    }

    private static final String COMMENT_ADD_URI_FORMAT =
            "comment://add?position=%d&l=%d&r=%d&isRightLine=%b";
    private static final String COMMENT_EDIT_URI_FORMAT =
            "comment://edit?position=%d&l=%d&r=%d&isRightLine=%b&id=%d";

    protected final ActivityResultLauncher<Intent> mEditLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> refresh())
    );

    private static final int ID_LOADER_COMMENTS = 0;

    protected String mRepoOwner;
    protected String mRepoName;
    protected String mPath;
    protected String mSha;
    private int mInitialLine;
    private int mHighlightStartLine;
    private int mHighlightEndLine;
    private boolean mHighlightIsRight;
    private IntentUtils.InitialCommentMarker mInitialComment;

    protected static class CommentWrapper {
        public GitLabComment comment;
        public CommentWrapper(GitLabComment comment) {
            this.comment = comment;
        }
        public Object getCacheKey() {
            return comment.id();
        }
    }

    private String mDiff;
    private String[] mDiffLines;
    private final SparseArray<List<GitLabComment>> mCommentsByPosition = new SparseArray<>();
    private final LongSparseArray<CommentWrapper> mWrappedComments = new LongSparseArray<>();

    private static final int MENU_ITEM_VIEW = 10;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadComments(true, false);
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
        mSha = extras.getString("sha");
        mDiff = extras.getString("diff");
        mInitialLine = extras.getInt("initial_line", -1);
        mHighlightStartLine = extras.getInt("highlight_start", -1);
        mHighlightEndLine = extras.getInt("highlight_end", -1);
        mHighlightIsRight = extras.getBoolean("highlight_right", false);
        mInitialComment = extras.getParcelable("initial_comment");
        extras.remove("initial_comment");
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return !getIntent().hasExtra("comments");
    }

    @Override
    public void onRefresh() {
        setContentShown(false);
        loadComments(true, true);
        super.onRefresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.file_viewer_menu, menu);

        String viewAtTitle = getString(R.string.object_view_file_at, mSha.substring(0, 7));
        menu.add(0, MENU_ITEM_VIEW, Menu.NONE, viewAtTitle)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.removeItem(R.id.download);

        return super.onCreateOptionsMenu(menu);
    }

    private void replaceOutdatedComment(GitLabComment updatedComment) {
        List<GitLabComment> comments = mCommentsByPosition.get(
                updatedComment.position() != null ? updatedComment.position() : 0);
        if (comments == null) return;
        int outdatedCommentIndex = -1;
        for (int i = 0; i < comments.size(); i++) {
            if (comments.get(i).id() == updatedComment.id()) {
                outdatedCommentIndex = i;
                break;
            }
        }
        if (outdatedCommentIndex != -1) {
            comments.set(outdatedCommentIndex, updatedComment);
        }
    }

    @Override
    protected String generateHtml(String cssTheme, boolean addTitleHeader) {
        StringBuilder content = new StringBuilder();
        boolean authorized = Gl4Application.get().isAuthorized();
        String title = addTitleHeader ? getDocumentTitle() : null;

        content.append("<html><head><title>");
        if (title != null) {
            content.append(title);
        }
        content.append("</title>");
        HtmlUtils.writeCssInclude(content, "diff", cssTheme);
        HtmlUtils.writeScriptInclude(content, "codeutils");
        content.append("</head><body");

        int highlightInsertPos = content.length();
        content.append(">");
        if (title != null) {
            content.append("<h2>").append(title).append("</h2>");
        }
        content.append("<pre>");

        mDiffLines = mDiff != null ? mDiff.split("\n") : new String[0];

        int highlightStartLine = -1, highlightEndLine = -1;
        int leftDiffPosition = -1, rightDiffPosition = -1;

        for (int i = 0; i < mDiffLines.length; i++) {
            String line = mDiffLines[i];
            String cssClass = null;
            if (line.startsWith("@@")) {
                int[] lineNumbers = StringUtils.extractDiffHunkLineNumbers(line);
                if (lineNumbers != null) {
                    leftDiffPosition = lineNumbers[0];
                    rightDiffPosition = lineNumbers[1];
                }
                cssClass = "change";
            } else if (line.startsWith("+")) {
                ++rightDiffPosition;
                cssClass = "add";
            } else if (line.startsWith("-")) {
                ++leftDiffPosition;
                cssClass = "remove";
            } else {
                ++leftDiffPosition;
                ++rightDiffPosition;
            }

            int pos = mHighlightIsRight ? rightDiffPosition : leftDiffPosition;
            if (pos != -1 && pos == mHighlightStartLine && highlightStartLine == -1) {
                highlightStartLine = i;
            }
            if (pos != -1 && pos == mHighlightEndLine && highlightEndLine == -1) {
                highlightEndLine = i;
            }

            content.append("<div id=\"line").append(i).append("\"");
            if (cssClass != null) {
                content.append("class=\"").append(cssClass).append("\"");
            }
            if (authorized) {
                String uri = String.format(Locale.US, COMMENT_ADD_URI_FORMAT,
                        i, leftDiffPosition, rightDiffPosition, line.startsWith("+"));
                content.append(" onclick=\"javascript:location.href='");
                content.append(uri).append("'\"");
            }
            content.append(">").append(TextUtils.htmlEncode(line)).append("</div>");

            List<GitLabComment> comments = mCommentsByPosition.get(i);
            if (comments != null) {
                for (GitLabComment comment : comments) {
                    long id = comment.id();
                    mWrappedComments.put(id, new CommentWrapper(comment));
                    content.append("<div ").append("id=\"comment").append(id).append("\"");
                    content.append(" class=\"comment");
                    if (mInitialComment != null && mInitialComment.matches(id, null)) {
                        content.append(" highlighted");
                    }
                    content.append("\"");
                    if (authorized) {
                        String uri = String.format(Locale.US, COMMENT_EDIT_URI_FORMAT,
                                i, leftDiffPosition, rightDiffPosition, line.startsWith("+"), id);
                        content.append(" onclick=\"javascript:location.href='");
                        content.append(uri).append("'\"");
                    }
                    content.append("><div class=\"change\">");
                    content.append(getString(R.string.commit_comment_header,
                            "<b>" + ApiHelpers.getUserLogin(this, comment.user()) + "</b>",
                            StringUtils.formatRelativeTime(DiffViewerActivity.this,
                                    comment.createdAt(), true)));
                    content.append("</div>").append(comment.bodyHtml());
                    content.append("</div>");
                }
            }
        }

        if (mInitialLine > 0) {
            content.insert(highlightInsertPos, " onload='scrollToElement(\"line"
                    + mInitialLine + "\")' onresize='scrollToHighlight();'");
        } else if (mInitialComment != null) {
            content.insert(highlightInsertPos, " onload='scrollToElement(\"comment"
                    + mInitialComment.commentId + "\")' onresize='scrollToHighlight();'");
        } else if (highlightStartLine != -1 && highlightEndLine != -1) {
            content.insert(highlightInsertPos, " onload='highlightDiffLines("
                    + highlightStartLine + "," + highlightEndLine
                    + ")' onresize='scrollToHighlight();'");
        }

        content.append("</pre></body></html>");
        return content.toString();
    }

    @Override
    protected String getDocumentTitle() {
        return getString(R.string.diff_print_document_title,
                FileUtils.getFileName(mPath), mSha.substring(0, 7), mRepoOwner, mRepoName);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Uri url = createUrl("", 0L);

        switch (item.getItemId()) {
            case R.id.browser:
                IntentUtils.launchBrowser(this, url);
                return true;
            case R.id.share:
                IntentUtils.share(this, getString(R.string.share_commit_subject,
                        mSha.substring(0, 7), mRepoOwner + "/" + mRepoName), url);
                return true;
            case MENU_ITEM_VIEW:
                startActivity(FileViewerActivity.makeIntent(this, mRepoOwner, mRepoName, mSha, mPath));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onConfirmed(String tag, Parcelable data) {
        long id = ((Bundle) data).getLong("id");
        deleteComment(id);
    }

    private void addCommentsToMap(List<C> comments) {
        mCommentsByPosition.clear();
        for (GitLabComment comment : comments) {
            if (!TextUtils.equals(comment.path(), mPath)) {
                continue;
            }
            Integer position = comment.position();
            if (position == null) continue;
            List<GitLabComment> commentsByPos = mCommentsByPosition.get(position);
            if (commentsByPos == null) {
                commentsByPos = new ArrayList<>();
                mCommentsByPosition.put(position, commentsByPos);
            }
            commentsByPos.add(comment);
        }
    }

    @Override
    protected void handleUrlLoad(Uri uri) {
        if (!uri.getScheme().equals("comment")) {
            super.handleUrlLoad(uri);
            return;
        }

        int line = Integer.parseInt(uri.getQueryParameter("position"));
        int leftLine = Integer.parseInt(uri.getQueryParameter("l"));
        int rightLine = Integer.parseInt(uri.getQueryParameter("r"));
        boolean isRightLine = Boolean.parseBoolean(uri.getQueryParameter("isRightLine"));
        String lineText = mDiffLines[line];
        String idParam = uri.getQueryParameter("id");
        long id = idParam != null ? Long.parseLong(idParam) : 0L;

        CommentActionPopup p = new CommentActionPopup(id, line, lineText, leftLine, rightLine,
                mLastTouchDown.x, mLastTouchDown.y, isRightLine);
        p.show();
    }

    private void refresh() {
        getIntent().removeExtra("comments");
        setResult(RESULT_OK);
        mWrappedComments.clear();
        loadComments(false, true);
        setContentShown(false);
    }

    protected abstract Single<List<C>> getCommentsSingle(boolean bypassCache);
    protected abstract void openCommentDialog(long id, long replyToId, String line,
            int position, int leftLine, int rightLine, GitLabComment commitComment);
    protected abstract Single<Response<Void>> deleteCommentSingle(long id);
    protected abstract boolean canReply();
    protected abstract Uri createUrl(String lineId, long replyId);

    private String createLineLinkId(int line, boolean isRight) {
        return (isRight ? "R" : "L") + line;
    }

    private void deleteComment(long id) {
        deleteCommentSingle(id)
                .map(ApiHelpers::mapToBooleanOrThrowOnFailure)
                .compose(RxUtils.wrapForBackgroundTask(this, R.string.deleting_msg,
                        getString(R.string.error_delete_commit_comment)))
                .subscribe(result -> refresh(),
                        error -> handleActionFailure("Comment deletion failed", error));
    }

    private void loadComments(boolean useIntentExtraIfPresent, boolean force) {
        List<C> intentComments = useIntentExtraIfPresent
                ? IntentUtils.getCompressedExtra(getIntent(), "comments") : null;
        Single<List<C>> commentsSingle = intentComments != null
                ? Single.just(intentComments)
                : getCommentsSingle(force).compose(makeLoaderSingle(ID_LOADER_COMMENTS, force));

        commentsSingle.subscribe(result -> {
            addCommentsToMap(result);
            onDataReady();
        }, this::handleLoadFailure);
    }

    private class CommentActionPopup extends PopupMenu implements
            PopupMenu.OnMenuItemClickListener {
        private final long mId;
        private final int mPosition;
        private final int mLeftLine;
        private final int mRightLine;
        private final String mLineText;
        private final boolean mIsRightLine;

        public CommentActionPopup(long id, int position, String lineText,
                int leftLine, int rightLine, int x, int y, boolean isRightLine) {
            super(DiffViewerActivity.this, findViewById(R.id.popup_helper));

            mId = id;
            mPosition = position;
            mLeftLine = leftLine;
            mRightLine = rightLine;
            mLineText = lineText;
            mIsRightLine = isRightLine;

            Menu menu = getMenu();
            CommentWrapper comment = mWrappedComments.get(mId);
            String ownLogin = Gl4Application.get().getAuthLogin();

            getMenuInflater().inflate(R.menu.commit_comment_actions, menu);
            if (id == 0 || !canReply()) {
                menu.removeItem(R.id.reply);
            }
            if (id == 0 || !ApiHelpers.loginEquals(comment.comment.user(), ownLogin)) {
                menu.removeItem(R.id.edit);
                menu.removeItem(R.id.delete);
            }
            // GitLab does not have inline reactions on diff comments in the same form
            menu.removeItem(R.id.react);

            View anchor = findViewById(R.id.popup_helper);
            anchor.layout(x, y, x + 1, y + 1);

            setOnMenuItemClickListener(this);
        }

        @Override
        public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case R.id.delete: {
                    Bundle data = new Bundle();
                    data.putLong("id", mId);

                    ConfirmationDialogFragment.show(DiffViewerActivity.this,
                            R.string.delete_comment_message, R.string.delete, data,
                            "deleteconfirm");
                    break;
                }
                case R.id.reply:
                    openCommentDialog(0L, mId, mLineText, mPosition, mLeftLine, mRightLine, null);
                    break;
                case R.id.edit:
                    CommentWrapper wrapper = mWrappedComments.get(mId);
                    GitLabComment comment = wrapper != null ? wrapper.comment : null;
                    openCommentDialog(mId, 0L, mLineText, mPosition, mLeftLine, mRightLine, comment);
                    break;
                case R.id.add_comment:
                    openCommentDialog(0L, 0L, mLineText, mPosition, mLeftLine, mRightLine, null);
                    break;
                case R.id.share:
                    Uri url = createUrl(createLineLinkId(mIsRightLine ? mRightLine : mLeftLine,
                            mIsRightLine), mId);
                    String subject = getString(R.string.share_commit_subject, mSha.substring(0, 7),
                            mRepoOwner + "/" + mRepoName);
                    IntentUtils.share(DiffViewerActivity.this, subject, url);
                    break;
            }
            return true;
        }
    }
}
