package com.gl4a.fragment;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.EditEpicCommentActivity;
import com.gl4a.activities.EpicActivity;
import com.gl4a.activities.IssueActivity;
import com.gl4a.adapter.RepositoryIssueAdapter;
import com.gl4a.gitlab.model.GitLabAwardEmoji;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabDiscussion;
import com.gl4a.gitlab.model.GitLabEpicDetailsResponse;
import com.gl4a.gitlab.model.GitLabGraphQLUser;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabLabel;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabReaction;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabEpicService;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.MaskedNames;
import com.gl4a.utils.NoteReactionsLoader;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.ReactionBar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Response;

/**
 * An epic's page (#312): the issue page, with what GitLab keeps for epics at the group. Its
 * description, labels, dates and child items above, its comments and reactions below.
 */
public class EpicFragment extends IssueFragmentBase {
    public static EpicFragment newInstance(GitLabIssue epic,
            IntentUtils.InitialCommentMarker initialComment) {
        EpicFragment f = new EpicFragment();
        f.setArguments(buildArgs(null, null, epic, false, initialComment));
        return f;
    }

    private GitLabEpicService service(boolean bypassCache) {
        return ServiceFactory.get(GitLabEpicService.class, bypassCache);
    }

    private String groupPath() {
        String path = mIssue.groupPath();
        return path != null ? path : "";
    }

    @Override
    protected void bindSpecialViews(View headerView) {
        headerView.findViewById(R.id.tv_pull).setVisibility(View.GONE);
        bindDates(headerView);
        // One query for the epic's details and its children's label colours, shared
        Single<GitLabEpicDetailsResponse> details = queryDetails().cache();
        loadDetails(headerView, details);
        loadChildren(headerView, details);
    }

    private void bindDates(View headerView) {
        List<String> parts = new ArrayList<>();
        if (mIssue.startDate != null) {
            parts.add(getString(R.string.epic_start_date, formatDate(mIssue.startDate)));
        }
        if (mIssue.dueDate != null) {
            parts.add(getString(R.string.epic_due_date, formatDate(mIssue.dueDate)));
        }
        headerView.findViewById(R.id.dates_container)
                .setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) headerView.findViewById(R.id.tv_dates))
                .setText(android.text.TextUtils.join(" · ", parts));
    }

    private CharSequence formatDate(String date) {
        java.util.Date parsed = StringUtils.parseIsoDate(date);
        return parsed != null ? android.text.format.DateUtils.formatDateTime(getActivity(),
                parsed.getTime(), android.text.format.DateUtils.FORMAT_SHOW_DATE
                        | android.text.format.DateUtils.FORMAT_SHOW_YEAR
                        | android.text.format.DateUtils.FORMAT_UTC) : date;
    }

    private static final String DETAILS_QUERY =
            "query($path: ID!, $iid: String!) { group(fullPath: $path) { workItem(iid: $iid) {"
            + " widgets {"
            + " ... on WorkItemWidgetLabels { labels { nodes { title color textColor description } } }"
            + " ... on WorkItemWidgetAssignees { assignees { nodes { id username name avatarUrl } } }"
            + " ... on WorkItemWidgetHierarchy { parent { iid title webUrl }"
            + " rolledUpCountsByType { workItemType { name } countsByState { opened closed all } }"
            + " children(first: 100) { nodes { widgets {"
            + " ... on WorkItemWidgetLabels { labels { nodes { title color textColor } } } } } } }"
            + " } } } }";

    /**
     * What REST leaves out of an epic, in one GraphQL query of its work item: its labels with
     * their colours (REST names them only), assignees, parent, GitLab's child counts and the
     * children's label colours.
     */
    private Single<GitLabEpicDetailsResponse> queryDetails() {
        String path = mIssue.groupPath();
        if (path == null) {
            return Single.error(new IllegalStateException("Epic without a group path"));
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("path", path);
        variables.put("iid", String.valueOf(mIssue.number()));
        Map<String, Object> body = new HashMap<>();
        body.put("query", DETAILS_QUERY);
        body.put("variables", variables);
        return ServiceFactory.getGraphQL(com.gl4a.gitlab.service.GitLabGraphQLService.class)
                .getEpicDetails(body)
                .map(ApiHelpers::throwOnFailure);
    }

    private void loadDetails(View headerView, Single<GitLabEpicDetailsResponse> query) {
        query.compose(RxUtils::doInBackground)
                .subscribe(details -> {
                    if (!isAdded() || details.data == null || details.data.group == null
                            || details.data.group.workItem == null
                            || details.data.group.workItem.widgets == null) {
                        return;
                    }
                    for (GitLabEpicDetailsResponse.Widget widget
                            : details.data.group.workItem.widgets) {
                        if (widget == null) continue;
                        if (widget.labels != null && widget.labels.nodes != null) {
                            List<GitLabLabel> labels = new ArrayList<>();
                            for (GitLabEpicDetailsResponse.Label label : widget.labels.nodes) {
                                labels.add(label.toGitLabLabel());
                            }
                            fillLabels(labels);
                        }
                        if (widget.assignees != null && widget.assignees.nodes != null) {
                            List<GitLabUser> assignees = new ArrayList<>();
                            for (GitLabGraphQLUser user : widget.assignees.nodes) {
                                assignees.add(user.toGitLabUser());
                            }
                            bindAssignees(assignees);
                        }
                        bindParent(headerView, widget.parent);
                        if (widget.rolledUpCountsByType != null) {
                            bindChildCounts(headerView, widget.rolledUpCountsByType);
                        }
                    }
                }, error -> android.util.Log.d(com.gl4a.Gl4Application.LOG_TAG,
                        "Epic details unavailable", error));
    }

    private void bindParent(View headerView,
            @Nullable GitLabEpicDetailsResponse.Parent parent) {
        if (parent == null || parent.webUrl == null) return;
        View container = headerView.findViewById(R.id.parent_container);
        ((TextView) headerView.findViewById(R.id.tv_parent)).setText(parent.title);
        container.setOnClickListener(v -> IntentUtils.openLinkInternallyOrExternally(
                getActivity(), android.net.Uri.parse(parent.webUrl)));
        container.setVisibility(View.VISIBLE);
    }

    /** "5 epics · 5 issues · 2 of 10 closed" and its progress bar, GitLab's numbers. */
    private void bindChildCounts(View headerView,
            List<GitLabEpicDetailsResponse.TypeCount> counts) {
        int epics = 0, issues = 0, closed = 0, all = 0;
        for (GitLabEpicDetailsResponse.TypeCount count : counts) {
            if (count == null || count.countsByState == null || count.workItemType == null) {
                continue;
            }
            if ("Epic".equals(count.workItemType.name)) {
                epics += count.countsByState.all;
            } else if ("Issue".equals(count.workItemType.name)) {
                issues += count.countsByState.all;
            } else {
                continue;
            }
            closed += count.countsByState.closed;
            all += count.countsByState.all;
        }
        if (all == 0) return;
        List<String> parts = new ArrayList<>();
        if (epics > 0) {
            parts.add(getResources().getQuantityString(R.plurals.epic_child_epics, epics, epics));
        }
        if (issues > 0) {
            parts.add(getResources().getQuantityString(R.plurals.epic_child_issues, issues,
                    issues));
        }
        parts.add(getString(R.string.epic_children_closed, closed, all));
        ((TextView) headerView.findViewById(R.id.tv_children_counts))
                .setText(android.text.TextUtils.join(" · ", parts));
        android.widget.ProgressBar progress = headerView.findViewById(R.id.pb_children);
        progress.setMax(all);
        progress.setProgress(closed);
        headerView.findViewById(R.id.children_container).setVisibility(View.VISIBLE);
    }

    /**
     * The children's labels by name, with the colours GitLab's REST list of them leaves out
     * (the epic's child-issue list ignores with_labels_details).
     */
    private static Map<String, GitLabEpicDetailsResponse.Label> childLabels(
            GitLabEpicDetailsResponse details) {
        Map<String, GitLabEpicDetailsResponse.Label> labels = new HashMap<>();
        if (details.data == null || details.data.group == null
                || details.data.group.workItem == null
                || details.data.group.workItem.widgets == null) {
            return labels;
        }
        for (GitLabEpicDetailsResponse.Widget widget : details.data.group.workItem.widgets) {
            if (widget == null || widget.children == null || widget.children.nodes == null) {
                continue;
            }
            for (GitLabEpicDetailsResponse.WorkItem child : widget.children.nodes) {
                if (child == null || child.widgets == null) continue;
                for (GitLabEpicDetailsResponse.Widget childWidget : child.widgets) {
                    if (childWidget == null || childWidget.labels == null
                            || childWidget.labels.nodes == null) {
                        continue;
                    }
                    for (GitLabEpicDetailsResponse.Label label : childWidget.labels.nodes) {
                        if (label != null && label.title != null) labels.put(label.title, label);
                    }
                }
            }
        }
        return labels;
    }

    /** Child epics first, then issues, as GitLab web lists them, in the issue list's rows. */
    private void loadChildren(View headerView, Single<GitLabEpicDetailsResponse> details) {
        ViewGroup list = headerView.findViewById(R.id.children_list);
        // A spinner where the child items will be, while they and the epic's details (parent,
        // counts, labels) load (#321)
        com.gl4a.utils.InlineLoading.showIn(list);
        GitLabEpicService service = service(false);
        service.getChildEpics(mIssue.groupId, mIssue.number())
                .map(ApiHelpers::throwOnFailure)
                .zipWith(service.getChildIssues(mIssue.groupId, mIssue.number(), 1, 100)
                        .map(ApiHelpers::throwOnFailure), (epics, issues) -> {
                    List<GitLabIssue> all = new ArrayList<>(epics);
                    all.addAll(issues);
                    return all;
                })
                .zipWith(details.map(EpicFragment::childLabels)
                        .onErrorReturnItem(new HashMap<>()), (children, labels) -> {
                    for (GitLabIssue child : children) {
                        for (GitLabLabel label : child.labels()) {
                            GitLabEpicDetailsResponse.Label known = labels.get(label.name);
                            if (label.color == null && known != null) {
                                label.color = known.color;
                                label.textColor = known.textColor;
                            }
                        }
                    }
                    return children;
                })
                .compose(RxUtils::doInBackground)
                .subscribe(children -> {
                    if (!isAdded()) return;
                    list.removeAllViews();
                    if (children.isEmpty()) {
                        list.setVisibility(View.GONE);
                        return;
                    }
                    RepositoryIssueAdapter adapter = new RepositoryIssueAdapter(getActivity());
                    adapter.setHomePath(mIssue.groupPath());
                    adapter.addAll(children);
                    list.removeAllViews();
                    for (int i = 0; i < children.size(); i++) {
                        list.addView(makeChildRow(adapter, list, i, children.get(i)));
                    }
                    list.setVisibility(View.VISIBLE);
                    headerView.findViewById(R.id.children_container).setVisibility(View.VISIBLE);
                }, error -> {
                    list.removeAllViews();
                    list.setVisibility(View.GONE);
                    android.util.Log.d(com.gl4a.Gl4Application.LOG_TAG,
                            "Epic children unavailable", error);
                });
    }

    private View makeChildRow(RepositoryIssueAdapter adapter, ViewGroup parent, int position,
            GitLabIssue child) {
        RecyclerView.ViewHolder holder =
                adapter.onCreateViewHolder(parent, adapter.getItemViewType(position));
        adapter.onBindViewHolder(holder, position);
        View row = holder.itemView;
        if ("closed".equals(child.state())) {
            // The lists are split by state; here a closed child says so, in the closed colour
            TextView number = row.findViewById(R.id.tv_number);
            android.text.SpannableStringBuilder text =
                    new android.text.SpannableStringBuilder(number.getText()).append(" · ");
            int start = text.length();
            text.append(getString(R.string.state_closed_suffix));
            text.setSpan(new android.text.style.ForegroundColorSpan(UiUtils.resolveColor(
                    getActivity(), R.attr.colorIssueClosed)), start, text.length(), 0);
            number.setText(text);
        }
        row.setOnClickListener(v -> {
            Intent intent = child.isEpic()
                    ? EpicActivity.makeIntent(getActivity(), child.groupPath(), child.number())
                    : IssueActivity.makeIntent(getActivity(), child, child.projectId);
            if (intent != null) startActivity(intent);
        });
        return row;
    }

    @Override
    protected void assignHighlightColor() {
        if ("closed".equals(mIssue.state())) {
            setHighlightColors(R.attr.colorIssueClosed, R.attr.colorIssueClosedDark);
        } else {
            setHighlightColors(R.attr.colorIssueOpen, R.attr.colorIssueOpenDark);
        }
    }

    @Override
    protected Single<List<TimelineItem>> onCreateDataSingle(boolean bypassCache) {
        GitLabEpicService service = service(bypassCache);
        long groupId = mIssue.groupId;
        long epicId = mIssue.id();
        return ApiHelpers.PageIterator
                .<GitLabDiscussion>toSingle(page -> service.getDiscussions(groupId, epicId,
                        (int) page, 100)
                        .map(response -> {
                            if (!response.isSuccessful() || response.body() == null) {
                                return Response.<GitLabPage<GitLabDiscussion>>error(
                                        response.errorBody(), response.raw());
                            }
                            return Response.success(ApiHelpers.toPage(response));
                        }))
                .map(GitLabDiscussion::flatten)
                // GitLab keeps an epic's comments on its work item, which GraphQL reads as an
                // issue: reactions and stored renderings in one query, as on issues (#162)
                .zipWith(NoteReactionsLoader.load(false, mIssue.workItemId, bypassCache),
                        NoteReactionsLoader::apply)
                .map(MaskedNames::unmask)
                .compose(RxUtils.<GitLabComment, TimelineItem>mapList(
                        c -> new TimelineItem.TimelineComment(c)))
                .subscribeOn(Schedulers.io());
    }

    @Override
    protected String getWorkItemContainer() {
        return "group";
    }

    @Nullable
    @Override
    protected String getWorkItemContainerPath() {
        return mIssue.groupPath();
    }

    @Override
    protected String getDescriptionHtmlQuery() {
        return "query($path: ID!, $iid: ID!) { project: group(fullPath: $path) {"
                + " item: epic(iid: $iid) { descriptionHtml } } }";
    }

    @Nullable
    @Override
    protected String getDescriptionHtmlPath() {
        return mIssue.groupPath();
    }

    @Override
    public Single<?> onEditorDoSend(String comment) {
        Map<String, Object> request = new HashMap<>();
        request.put("body", comment);
        if (mReplyToThread != null) {
            return service(false).addDiscussionNote(mIssue.groupId, mIssue.id(),
                    mReplyToThread.discussionId(), request)
                    .map(ApiHelpers::throwOnFailure);
        }
        return service(false).createComment(mIssue.groupId, mIssue.id(), request)
                .map(ApiHelpers::throwOnFailure);
    }

    @Override
    public void editComment(GitLabComment comment) {
        @AttrRes int highlightColorAttr = "closed".equals(mIssue.state())
                ? R.attr.colorIssueClosed : R.attr.colorIssueOpen;
        mEditLauncher.launch(EditEpicCommentActivity.makeIntent(getActivity(), mIssue.groupId,
                mIssue.id(), comment.id(), comment.body(), highlightColorAttr));
    }

    @Override
    protected Single<Response<Void>> doDeleteComment(GitLabComment comment) {
        return service(false).deleteComment(mIssue.groupId, mIssue.id(), comment.id());
    }

    @Override
    public void resolveThread(GitLabComment comment, boolean resolve) {
        // Epic threads can't be resolved; the menu doesn't offer it
    }

    @Override
    public String getShareSubject(GitLabComment comment) {
        return getString(R.string.share_epic_comment_subject, mIssue.number(), groupPath());
    }

    @Override
    public int getCommentEditorHintResId() {
        return getCommentHint(R.string.epic_comment_hint);
    }

    // Reactions: on the epic by its number, on its comments by theirs

    @Override
    protected Single<Response<List<GitLabAwardEmoji>>> loadBodyAwardEmojis(boolean bypassCache) {
        return service(bypassCache).getAwardEmojis(mIssue.groupId, mIssue.number(), 1, 100);
    }

    @Override
    public Single<GitLabReaction> addReaction(ReactionBar.Item item, String content) {
        return service(false).addAwardEmoji(mIssue.groupId, mIssue.number(), emojiBody(content))
                .map(EpicFragment::toReaction)
                .compose(RxUtils::doInBackground);
    }

    @Override
    public Single<Boolean> deleteReaction(ReactionBar.Item item, long reactionId) {
        return service(false).deleteAwardEmoji(mIssue.groupId, mIssue.number(), reactionId)
                .map(r -> r.isSuccessful() || r.code() == 404)
                .compose(RxUtils::doInBackground);
    }

    @Override
    public Single<List<GitLabReaction>> loadReactionDetails(GitLabComment comment,
            boolean bypassCache) {
        return service(bypassCache).getNoteAwardEmojis(mIssue.groupId, mIssue.number(),
                        comment.id(), 1, 50)
                .map(EpicFragment::toReactions)
                .compose(RxUtils::doInBackground);
    }

    @Override
    public Single<GitLabReaction> addReaction(GitLabComment comment, String content) {
        return service(false).addNoteAwardEmoji(mIssue.groupId, mIssue.number(), comment.id(),
                        emojiBody(content))
                .map(EpicFragment::toReaction)
                .compose(RxUtils::doInBackground);
    }

    @Override
    public Single<Boolean> deleteReaction(GitLabComment comment, long reactionId) {
        return service(false).deleteNoteAwardEmoji(mIssue.groupId, mIssue.number(),
                        comment.id(), reactionId)
                .map(r -> r.isSuccessful() || r.code() == 404)
                .compose(RxUtils::doInBackground);
    }

    private static Map<String, Object> emojiBody(String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", mapContentToEmojiName(content));
        return body;
    }

    private static GitLabReaction toReaction(Response<GitLabAwardEmoji> response) {
        if (!response.isSuccessful() || response.body() == null) {
            throw new RuntimeException("Failed to add reaction: HTTP " + response.code());
        }
        GitLabAwardEmoji emoji = response.body();
        GitLabReaction r = new GitLabReaction();
        r.id = emoji.id;
        r.name = emoji.name;
        r.user = emoji.user;
        return r;
    }

    private static List<GitLabReaction> toReactions(Response<List<GitLabAwardEmoji>> response) {
        List<GitLabReaction> result = new ArrayList<>();
        if (response.isSuccessful() && response.body() != null) {
            for (GitLabAwardEmoji e : response.body()) {
                GitLabReaction r = new GitLabReaction();
                r.id = e.id;
                r.name = e.name;
                r.user = e.user;
                r.createdAt = e.createdAt;
                result.add(r);
            }
        }
        return result;
    }
}
