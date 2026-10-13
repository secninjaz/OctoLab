package com.gl4a.utils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

import com.gl4a.R;

import java.util.Locale;

/**
 * THE look of GitLab content (descriptions, comments, notes), however it's shown: as Android
 * text (HtmlUtils), in the web view used for tables (HttpImageGetter) and as diff snippets
 * (DiffSnippetLoader) all read it here, so a change made here applies to every one (#251).
 * The values are the Android text rendering's, which is the reference look.
 */
public final class MarkdownStyle {
    /** Heading sizes, h1 to h6, relative to the body text. */
    public static final float[] HEADING_SIZES = { 1.75f, 1.5f, 1.25f, 1.1f, 1f, 0.9f };
    public static final float SMALL_TEXT_SIZE = 0.8f;
    public static final float BIG_TEXT_SIZE = 1.25f;
    /** Links are coloured, never underlined. */
    public static final boolean UNDERLINE_LINKS = false;
    /** The horizontal rule's colour. */
    public static final @ColorInt int RULE_COLOR = 0x60aaaaaa;
    /** android.text.style.BulletSpan's default bullet radius (px), which list bullets use. */
    private static final int BULLET_RADIUS_PX = 4;
    /** The gap between blocks: Android text leaves one blank line, about a line's height. */
    private static final float BLOCK_GAP_EM = 1.17f;

    public final @ColorInt int text;
    public final @ColorInt int link;
    public final @ColorInt int quoteBar;
    public final @ColorInt int quoteBackground;
    public final @ColorInt int codeBackground;
    public final @ColorInt int diffAdd;
    public final @ColorInt int diffRemove;
    public final @ColorInt int diffAddNumber;
    public final @ColorInt int diffRemoveNumber;
    /** Quotes and alerts: the text's distance from the edge, and the bar's width (px). */
    public final int quoteMarginPx;
    public final int quoteBarPx;
    public final int bulletMarginPx;
    /** Body text size (px): textAppearanceSmall, which descriptions and comments use. */
    public final float bodyTextPx;
    public final float ruleHeightPx;
    private final float mDensity;
    private final float mScaledDensity;

    private MarkdownStyle(Context context) {
        Resources res = context.getResources();
        text = UiUtils.resolveColor(context, android.R.attr.textColorPrimary);
        link = UiUtils.resolveColor(context, android.R.attr.textColorLink);
        quoteBar = ContextCompat.getColor(context, R.color.quote_bar);
        quoteBackground = ContextCompat.getColor(context, R.color.quote_bg);
        codeBackground = UiUtils.resolveColor(context, R.attr.colorCodeBackground);
        diffAdd = ContextCompat.getColor(context, R.color.diff_add);
        diffRemove = ContextCompat.getColor(context, R.color.diff_remove);
        diffAddNumber = ContextCompat.getColor(context, R.color.diff_add_line_number);
        diffRemoveNumber = ContextCompat.getColor(context, R.color.diff_remove_line_number);
        quoteMarginPx = res.getDimensionPixelSize(R.dimen.reply_span_margin);
        quoteBarPx = res.getDimensionPixelSize(R.dimen.reply_span_size);
        bulletMarginPx = res.getDimensionPixelSize(R.dimen.bullet_span_margin);
        ruleHeightPx = res.getDimension(R.dimen.divider_span_height);
        mDensity = res.getDisplayMetrics().density;
        mScaledDensity = res.getDisplayMetrics().scaledDensity;
        android.util.TypedValue appearance = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textAppearanceSmall, appearance, true);
        android.content.res.TypedArray a = context.obtainStyledAttributes(appearance.resourceId,
                new int[] { android.R.attr.textSize });
        bodyTextPx = a.getDimension(0, 14 * res.getDisplayMetrics().scaledDensity);
        a.recycle();
    }

    /**
     * The leading margin of a numbered list item showing {@code chars} characters ("1. "),
     * for the Android text's NumberedItemSpan and the web view's numbered lists alike.
     */
    public static int numberedItemMarginPx(int chars, float scaledDensity) {
        return 4 + Math.round(chars * 6 * scaledDensity);
    }

    /** The style in {@code context}'s theme. */
    public static MarkdownStyle of(Context context) {
        return new MarkdownStyle(context);
    }

    /**
     * THE page GitLab content is shown in, in any web view (descriptions, comments, the editor
     * preview, #266): its HTML with the shared stylesheet, tables wrapped so that only a table
     * wider than the screen scrolls sideways (#248).
     *
     * @param extraCss rules for this place only, e.g. the preview's padding; may be empty
     */
    public static String webViewPage(Context context, String html, String extraCss) {
        String body = html.replaceAll("(?i)<table\\b", "<div class=\"table-scroll\"><table")
                .replaceAll("(?i)</table>", "</table></div>");
        return "<html><head><style>" + of(context).webViewCss() + extraCss
                + "</style></head><body>" + body + "</body></html>";
    }

    /**
     * The web view's stylesheet: the same look as the Android text, as CSS, for GitLab's HTML
     * and its class names (task lists, alerts, highlighted diffs). Only a table or code block
     * wider than the screen scrolls sideways, in its own box; the page itself never does.
     */
    public String webViewCss() {
        String textRgb = Color.red(text) + "," + Color.green(text) + "," + Color.blue(text);
        StringBuilder css = new StringBuilder()
                .append("body{font-size:").append(dp(bodyTextPx)).append("px")
                .append(";font-family:sans-serif;background:transparent;color:").append(css(text))
                .append(";margin:0;padding:0;overflow:hidden;overflow-wrap:break-word}")
                // Blocks one blank line apart, as Android text lays them out
                .append("p,ul,ol,blockquote,pre,.markdown-alert,details{margin:0 0 ")
                .append(BLOCK_GAP_EM).append("em 0}")
                .append("li>ul,li>ol{margin:").append(BLOCK_GAP_EM).append("em 0}")
                // A list item's paragraphs keep their gap, as in Android text (#254)
                .append("blockquote>:last-child,.markdown-alert>:last-child")
                .append("{margin-bottom:0}")
                .append("a,a:visited{color:").append(css(link))
                .append(";text-decoration:").append(UNDERLINE_LINKS ? "underline" : "none")
                .append("}");
        for (int i = 0; i < HEADING_SIZES.length; i++) {
            css.append("h").append(i + 1).append("{font-size:").append(HEADING_SIZES[i])
                    .append("em;font-weight:bold}");
        }
        css.append("small{font-size:").append(SMALL_TEXT_SIZE).append("em}")
                .append("big{font-size:").append(BIG_TEXT_SIZE).append("em}")
                .append("hr{border:0;height:").append(dp(ruleHeightPx)).append("px;background:")
                .append(css(RULE_COLOR)).append("}")
                // Quotes and alerts: a bar and a tint, as ReplySpan draws them
                .append("blockquote,.markdown-alert{padding:2px 0 2px ")
                .append(dp(quoteMarginPx - quoteBarPx)).append("px;border-left:")
                .append(dp(quoteBarPx)).append("px solid ").append(css(quoteBar))
                .append(";background:").append(css(quoteBackground)).append("}")
                .append(".markdown-alert-title{font-weight:bold;font-style:italic}")
                // Lists indented as BulletSpan and NumberedItemSpan indent them. Bullets are
                // drawn as BulletSpan draws them, a dot inside the indent: the browser's own
                // marker sits left of the indent and was cut off at the edge (#254)
                .append("ul{padding-left:").append(dp(2 * BULLET_RADIUS_PX + bulletMarginPx))
                .append("px;list-style:none}")
                .append("ul>li{position:relative}")
                .append("ul>li::before{content:'';position:absolute;left:-")
                .append(dp(2 * BULLET_RADIUS_PX + bulletMarginPx)).append("px;top:calc(")
                .append(BLOCK_GAP_EM / 2).append("em - ").append(dp(BULLET_RADIUS_PX))
                .append("px);width:").append(dp(2 * BULLET_RADIUS_PX)).append("px;height:")
                .append(dp(2 * BULLET_RADIUS_PX)).append("px;border-radius:50%")
                .append(";background:currentColor}")
                .append("ol{padding-left:").append(dp(numberedItemMarginPx(3, mScaledDensity)))
                .append("px}")
                // GitLab puts a loose task's text in a paragraph after its checkbox
                .append("li>input[type=checkbox]+p{display:inline}")
                // Code, inline and in blocks, on the same background, at the text's size
                .append("code,kbd,samp,pre{font-family:monospace,monospace;font-size:1em}")
                .append("code{background:").append(css(codeBackground)).append("}")
                .append("pre{background:").append(css(codeBackground))
                .append(";white-space:pre;overflow-x:auto;padding:2px 0}")
                .append("pre code{background:transparent}")
                // Diff lines in the colours diff threads use, across the whole line
                .append("pre .gi,pre .gd{display:inline-block;min-width:100%}")
                .append("pre .gi{background:").append(css(diffAdd)).append("}")
                .append("pre .gd{background:").append(css(diffRemove)).append("}")
                .append(".table-scroll{overflow-x:auto;margin-bottom:12px}")
                .append("table{border-collapse:collapse;width:100%}")
                .append("th,td{border:1px solid rgba(").append(textRgb)
                .append(",0.25);padding:6px 10px;text-align:left}")
                .append("th{background:rgba(").append(textRgb).append(",0.06);font-weight:bold}")
                .append("[align=center]{text-align:center}[align=right]{text-align:right}")
                .append("img,video{max-width:100%;height:auto}")
                .append("::-webkit-scrollbar{display:none}");
        return css.toString();
    }

    /** The diff view's added and removed line backgrounds (diff-*.css classes) (#274). */
    public String diffViewCss() {
        return ".add{background-color:" + css(diffAdd) + "}"
                + ".remove{background-color:" + css(diffRemove) + "}";
    }

    /** px → CSS px, which are dp in a web view. */
    private String dp(float px) {
        return String.format(Locale.ROOT, "%.1f", px / mDensity);
    }

    private static String css(@ColorInt int color) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", Color.red(color),
                Color.green(color), Color.blue(color), Color.alpha(color) / 255f);
    }
}
