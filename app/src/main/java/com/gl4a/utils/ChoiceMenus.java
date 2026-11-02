package com.gl4a.utils;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import androidx.core.content.ContextCompat;

import com.gl4a.Gl4Application;
import com.gl4a.R;

import java.util.Collection;

/**
 * THE way a side panel (or menu) shows the current choice of each of its sections, e.g. a
 * list's Sort and Filter (#308). The panel can only highlight one item at a time, so with two
 * sections one choice went unseen: every section's choice is shown the same way instead, in
 * the accent colour and bold, with a check mark; the rest plain.
 */
public final class ChoiceMenus {
    private ChoiceMenus() {
    }

    /** Marks {@code selectedId} among the items whose ids are in {@code section}. */
    public static void mark(Menu menu, Collection<Integer> section, int selectedId) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (item.hasSubMenu()) {
                SubMenu sub = item.getSubMenu();
                for (int j = 0; j < sub.size(); j++) mark(sub.getItem(j), section, selectedId);
            } else {
                mark(item, section, selectedId);
            }
        }
    }

    private static void mark(MenuItem item, Collection<Integer> section, int selectedId) {
        if (!section.contains(item.getItemId())) return;
        // Not the panel's own single highlight: every section shows its choice alike
        item.setCheckable(false);
        String title = item.getTitle() != null ? item.getTitle().toString() : "";
        if (title.endsWith(" ✓")) title = title.substring(0, title.length() - 2);
        if (item.getItemId() != selectedId) {
            item.setTitle(title);
            return;
        }
        SpannableString marked = new SpannableString(title + " ✓");
        marked.setSpan(new ForegroundColorSpan(ContextCompat.getColor(Gl4Application.get(),
                R.color.accent)), 0, marked.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        marked.setSpan(new StyleSpan(Typeface.BOLD), 0, marked.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        item.setTitle(marked);
    }
}
