package com.gl4a.utils;

import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;

import com.gl4a.ApiRequestException;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.gitlab.service.GitLabMergeRequestService;

import java.net.HttpURLConnection;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * THE GitLab subscription to an issue or merge request (#289, #290): subscribing and
 * unsubscribing, from a to-do's menu or the item's own screen, and that screen's menu item.
 */
public final class Subscriptions {
    private Subscriptions() {
    }

    /** A screen that learns whether the user is subscribed from what it loads anyway. */
    public interface Host {
        void onSubscriptionKnown(boolean subscribed);
    }

    /** Subscribes or unsubscribes; 304 (already so) is done too. */
    public static Single<Boolean> change(long projectId, long iid, boolean mergeRequest,
            boolean subscribe) {
        Single<Response<Void>> call;
        if (mergeRequest) {
            GitLabMergeRequestService service =
                    ServiceFactory.get(GitLabMergeRequestService.class, false);
            call = subscribe ? service.subscribe(projectId, iid)
                    : service.unsubscribe(projectId, iid);
        } else {
            GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
            call = subscribe ? service.subscribe(projectId, iid)
                    : service.unsubscribe(projectId, iid);
        }
        return call.map(response -> {
            if (response.isSuccessful()
                    || response.code() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return true;
            }
            throw new ApiRequestException(response);
        });
    }

    /**
     * The screen's Subscribe / Unsubscribe item, hidden while it isn't known. Call from
     * onCreateOptionsMenu / onPrepareOptionsMenu.
     */
    public static void prepareMenu(Menu menu, @Nullable Boolean subscribed) {
        MenuItem item = menu.findItem(R.id.subscription);
        if (item == null) return;
        item.setVisible(subscribed != null);
        if (subscribed != null) {
            item.setTitle(subscribed ? R.string.unsubscribe : R.string.subscribe);
        }
    }

    /** Told the new state once GitLab has it. */
    public interface Changed {
        void onChanged(boolean subscribed);
    }

    /**
     * Subscribes or unsubscribes when the screen's item was selected, saying so; returns
     * whether it handled the item. {@code reference} names it, e.g. "Issue #243".
     */
    public static boolean onMenuItemSelected(android.content.Context context, MenuItem item,
            long projectId, long iid, boolean mergeRequest, @Nullable Boolean subscribed,
            String reference, Changed changed) {
        if (item.getItemId() != R.id.subscription || subscribed == null || projectId <= 0) {
            return false;
        }
        boolean subscribe = !subscribed;
        change(projectId, iid, mergeRequest, subscribe)
                .compose(RxUtils::doInBackground)
                .subscribe(done -> {
                    changed.onChanged(subscribe);
                    android.widget.Toast.makeText(context, context.getString(subscribe
                            ? R.string.subscribe_success : R.string.unsubscribe_success,
                            reference), android.widget.Toast.LENGTH_SHORT).show();
                }, error -> {
                    android.util.Log.d(com.gl4a.Gl4Application.LOG_TAG,
                            "Changing the subscription to " + reference + " failed", error);
                    android.widget.Toast.makeText(context, context.getString(subscribe
                            ? R.string.subscribe_failed : R.string.unsubscribe_failed,
                            reference), android.widget.Toast.LENGTH_LONG).show();
                });
        return true;
    }
}
