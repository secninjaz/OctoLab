package com.gl4a.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gl4a.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE changelog, as What's new shows it (#347): CHANGELOG.md, packed into the app as an asset
 * by app/build.gradle, parsed into versions, newest first, with their sections and items.
 * Each item keeps only the references of the build's site, as scripts/changelog_refs.py does
 * for release notes: community builds the GitHub links, internal builds the "#123" numbers.
 */
public final class Changelog {
    public static final String ASSET = "CHANGELOG.md";
    public static final String UNRELEASED = "Unreleased";

    public static final class Section {
        public final String title;
        /** Markdown, references already filtered. */
        public final List<String> items = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }
    }

    public static final class Version {
        /** "1.6.2", or {@link #UNRELEASED}. */
        public final String name;
        /** "2026-11-19" as written, or null. */
        @Nullable public final String date;
        public final List<Section> sections = new ArrayList<>();

        Version(String name, @Nullable String date) {
            this.name = name;
            this.date = date;
        }

        public boolean isUnreleased() {
            return UNRELEASED.equalsIgnoreCase(name);
        }

        boolean isEmpty() {
            for (Section section : sections) {
                if (!section.items.isEmpty()) return false;
            }
            return true;
        }

        /** The date as "19 Nov 2026", or null. The app is in English only. */
        @Nullable
        public String formattedDate() {
            if (date == null) return null;
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                in.setLenient(false);
                return new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(in.parse(date));
            } catch (ParseException e) {
                return date;
            }
        }
    }

    // "## [1.6.2] - 2026-11-19", "## [Unreleased]"
    private static final Pattern VERSION =
            Pattern.compile("^## \\[([^\\]]+)\\](?:\\s*-\\s*(\\S+))?\\s*$");
    // "### Added"
    private static final Pattern SECTION = Pattern.compile("^### (.+?)\\s*$");

    // As in scripts/changelog_refs.py: a parenthesis whose parts are all references,
    // "(#330)", "(#330, [GitHub #3](…))"
    private static final Pattern REFS = Pattern.compile(" ?\\(((?:#\\d+|\\[GitHub #\\d+\\]\\([^)\\s]+\\))"
            + "(?:, (?:#\\d+|\\[GitHub #\\d+\\]\\([^)\\s]+\\)))*)\\)");
    private static final Pattern GITHUB_REF =
            Pattern.compile("^\\[GitHub #\\d+\\]\\(https://github\\.com/[^)\\s]+/issues/\\d+\\)$");
    private static final Pattern INTERNAL_REF = Pattern.compile("^#\\d+$");

    private Changelog() {
    }

    /**
     * The app's changelog for this build: its references, and Unreleased in debug builds only.
     * Empty if the asset can't be read.
     */
    @NonNull
    public static List<Version> load(Context context) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(ASSET), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return parse(text.toString(), BuildConfig.SHOW_INTERNAL_REFS, BuildConfig.DEBUG);
    }

    /** Versions in the file's order (newest first); versions without items are left out. */
    @NonNull
    public static List<Version> parse(String text, boolean internalRefs, boolean withUnreleased) {
        List<Version> versions = new ArrayList<>();
        Version version = null;
        Section section = null;
        for (String line : text.split("\n")) {
            line = line.replace("\r", "");
            Matcher m = VERSION.matcher(line);
            if (m.matches()) {
                version = new Version(m.group(1).trim(), m.group(2));
                section = null;
                if (!version.isUnreleased() || withUnreleased) {
                    versions.add(version);
                }
                continue;
            }
            if (version == null) continue;
            m = SECTION.matcher(line);
            if (m.matches()) {
                section = new Section(m.group(1));
                version.sections.add(section);
                continue;
            }
            if (section == null) continue;
            if (line.startsWith("- ") || line.startsWith("* ")) {
                section.items.add(line.substring(2).trim());
            } else if (!line.trim().isEmpty() && !section.items.isEmpty()) {
                // A wrapped item: part of the one before
                int last = section.items.size() - 1;
                section.items.set(last, section.items.get(last) + " " + line.trim());
            }
        }

        List<Version> result = new ArrayList<>();
        for (Version v : versions) {
            for (Section s : v.sections) {
                for (int i = 0; i < s.items.size(); i++) {
                    s.items.set(i, filterRefs(s.items.get(i), internalRefs));
                }
            }
            if (!v.isEmpty()) result.add(v);
        }
        return result;
    }

    /**
     * Only the references of the build's site: "#330" internally, "[GitHub #3](…)" otherwise.
     * A parenthesis left empty goes, with the space before it; others stay as they are.
     */
    @NonNull
    public static String filterRefs(String item, boolean internalRefs) {
        Pattern wanted = internalRefs ? INTERNAL_REF : GITHUB_REF;
        Matcher m = REFS.matcher(item);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            List<String> parts = new ArrayList<>();
            for (String part : m.group(1).split(", ")) {
                if (wanted.matcher(part).matches()) parts.add(part);
            }
            String replacement = "";
            if (!parts.isEmpty()) {
                replacement = (m.group(0).startsWith(" ") ? " " : "")
                        + "(" + android.text.TextUtils.join(", ", parts) + ")";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
