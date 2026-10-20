package com.gl4a.utils;

import android.net.Uri;

/**
 * Tapping images in Markdown files (#281), e.g. a README in the Files section or on a project's
 * About tab, as in issues, merge requests and comments.
 *
 * GitLab's own HTML (descriptions, comments) wraps each image in a link, which the web views send
 * to {@link IntentUtils#openLinkInternallyOrExternally}: that's what opens it in-app. Markdown
 * files are rendered in the app, with no such link, and their relative images are embedded, so
 * there was nothing to tap. {@link #LINK_SCRIPT} wraps every image that isn't already in a link
 * in one to its address, so a tap takes exactly the route an image in a comment takes; images
 * already in a link are left alone. An embedded image keeps its file's address in
 * {@code data-original}, opening the file as the Files section does.
 */
public final class MarkdownImages {
    private MarkdownImages() {
    }

    /** Wraps each image not in a link in a link to it (its data-original, else its src). */
    public static final String LINK_SCRIPT = "(function(){"
            + "var imgs=document.querySelectorAll('img');"
            + "for(var i=0;i<imgs.length;i++){"
            + "var img=imgs[i];"
            + "if(img.closest('a'))continue;"
            + "var u=img.getAttribute('data-original')||img.getAttribute('src');"
            + "if(!u||u.indexOf('data:')===0)continue;"
            + "var a=document.createElement('a');a.href=u;"
            + "img.parentNode.insertBefore(a,img);a.appendChild(img);"
            + "}})();\n";

    /** A repository file's page, as the Files section opens it. */
    public static String fileUrl(String owner, String repo, String ref, String path) {
        if (path.startsWith("./")) path = path.substring(2);
        if (path.startsWith("/")) path = path.substring(1);
        String instance = com.gl4a.Gl4Application.get().getInstanceUrl();
        return instance + "/" + owner + "/" + repo + "/-/blob/" + Uri.encode(ref, "/")
                + "/" + Uri.encode(path, "/");
    }

    /** An embedded image, as Markdown's ![alt](src) renders it, keeping its file's address. */
    public static String embeddedImage(String dataUri, String alt, String originalUrl) {
        return "<img src=\"" + dataUri + "\" alt=\"" + attr(alt) + "\" data-original=\""
                + attr(originalUrl) + "\">";
    }

    /** The attribute keeping an embedded HTML image's file address. */
    public static String originalAttribute(String originalUrl) {
        return " data-original=\"" + attr(originalUrl) + "\"";
    }

    private static String attr(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
