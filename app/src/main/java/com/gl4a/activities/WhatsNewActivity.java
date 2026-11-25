package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gl4a.BaseActivity;
import com.gl4a.BuildConfig;
import com.gl4a.R;
import com.gl4a.fragment.SettingsFragment;
import com.gl4a.utils.Changelog;
import com.gl4a.utils.HtmlUtils;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * What's new (#347): the app's changelog, newest first. The version the app is (and Unreleased
 * in debug builds) starts expanded, older ones collapsed; tapping a version's header expands or
 * collapses it. Opened from Settings → About and from the bar shown once after an update.
 */
public class WhatsNewActivity extends BaseActivity {
    /** The version What's new was last offered for, in the app's preferences (#347). */
    private static final String PREF_SEEN_VERSION = "whats_new_seen_version";
    private static final String STATE_EXPANDED = "expanded";

    public static Intent makeIntent(Context context) {
        return new Intent(context, WhatsNewActivity.class);
    }

    /**
     * Once after an update: "Updated to 1.6.3", with What's new. A fresh install only
     * remembers its version, without a bar (#347).
     */
    public static void showBarIfUpdated(BaseActivity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(SettingsFragment.PREF_NAME,
                Context.MODE_PRIVATE);
        String seen = prefs.getString(PREF_SEEN_VERSION, null);
        String current = BuildConfig.VERSION_NAME;
        if (current.equals(seen)) return;
        prefs.edit().putString(PREF_SEEN_VERSION, current).apply();
        // Nothing kept yet: an update from a version before What's new still gets the bar,
        // a fresh install doesn't
        if (seen == null && !wasUpdated(activity)) return;
        Snackbar.make(activity.getRootLayout(),
                        activity.getString(R.string.whats_new_updated_to, current),
                        Snackbar.LENGTH_LONG)
                .setAction(R.string.whats_new, v -> activity.startActivity(makeIntent(activity)))
                .show();
    }

    /** Whether this install has been updated since it was first installed. */
    private static boolean wasUpdated(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private final ArrayList<String> mExpanded = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.whats_new);

        List<Changelog.Version> versions = Changelog.load(this);
        if (savedInstanceState != null
                && savedInstanceState.getStringArrayList(STATE_EXPANDED) != null) {
            mExpanded.addAll(savedInstanceState.getStringArrayList(STATE_EXPANDED));
        } else {
            for (Changelog.Version version : versions) {
                if (version.isUnreleased() || version.name.equals(BuildConfig.VERSION_NAME)) {
                    mExpanded.add(version.name);
                }
            }
            // A test build's version is its tag, e.g. "v1.6.3-test-347", in no section: the
            // newest version opens then (#347)
            if (mExpanded.isEmpty() && !versions.isEmpty()) {
                mExpanded.add(versions.get(0).name);
            }
        }

        ViewGroup container = findViewById(R.id.versions);
        LayoutInflater inflater = getLayoutInflater();
        for (Changelog.Version version : versions) {
            container.addView(makeVersionView(inflater, container, version));
        }
        setContentEmpty(versions.isEmpty());
    }

    private View makeVersionView(LayoutInflater inflater, ViewGroup parent,
            Changelog.Version version) {
        View view = inflater.inflate(R.layout.row_whats_new_version, parent, false);
        TextView title = view.findViewById(R.id.tv_version);
        ImageView expandIcon = view.findViewById(R.id.iv_expand);
        ViewGroup sections = view.findViewById(R.id.sections);

        String date = version.formattedDate();
        String name = version.isUnreleased() ? getString(R.string.whats_new_unreleased) : version.name;
        title.setText(date != null ? getString(R.string.whats_new_version_header, name, date) : name);

        for (Changelog.Section section : version.sections) {
            if (section.items.isEmpty()) continue;
            View sectionView = inflater.inflate(R.layout.row_whats_new_section, sections, false);
            TextView sectionTitle = sectionView.findViewById(R.id.tv_section);
            TextView items = sectionView.findViewById(R.id.tv_items);
            sectionTitle.setText(section.title);
            items.setText(renderItems(section.items));
            sections.addView(sectionView);
        }

        boolean expanded = mExpanded.contains(version.name);
        sections.setVisibility(expanded ? View.VISIBLE : View.GONE);
        expandIcon.setImageResource(expanded ? R.drawable.chevron_up : R.drawable.chevron_down);
        view.findViewById(R.id.version_header).setOnClickListener(v -> {
            boolean expand = sections.getVisibility() != View.VISIBLE;
            sections.setVisibility(expand ? View.VISIBLE : View.GONE);
            expandIcon.setImageResource(expand ? R.drawable.chevron_up : R.drawable.chevron_down);
            if (expand) {
                mExpanded.add(version.name);
            } else {
                mExpanded.remove(version.name);
            }
        });
        return view;
    }

    /** A section's items as a bullet list, through the app's own markdown rendering (#347). */
    private CharSequence renderItems(List<String> items) {
        // One tight list: each item's own markdown (links, code) as inline HTML, so items
        // aren't paragraphs a blank line apart
        StringBuilder html = new StringBuilder("<ul>");
        for (String item : items) {
            String itemHtml = HtmlUtils.markdownToHtml(item).trim();
            if (itemHtml.startsWith("<p>") && itemHtml.endsWith("</p>")) {
                itemHtml = itemHtml.substring(3, itemHtml.length() - 4).trim();
            }
            html.append("<li>").append(itemHtml).append("</li>");
        }
        html.append("</ul>");
        return HtmlUtils.encode(this, html.toString(), null);
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.whats_new);
    }

    @Override
    protected boolean canSwipeToRefresh() {
        // nothing is loaded
        return false;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_EXPANDED, mExpanded);
    }
}
