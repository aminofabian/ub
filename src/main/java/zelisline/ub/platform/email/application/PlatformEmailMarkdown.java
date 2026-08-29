package zelisline.ub.platform.email.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paragraphs, {@code **bold**}, and {@code [label](url)} — not a full markdown parser.
 */
public final class PlatformEmailMarkdown {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)");
    private static final Pattern BARE_URL = Pattern.compile("(?<!href=\")(?<!\\()(https?://[^\\s<]+)");

    private PlatformEmailMarkdown() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> blocks = splitParagraphs(normalized);
        StringBuilder html = new StringBuilder();
        for (String block : blocks) {
            html.append("<p style=\"margin:0 0 14px;font-family:")
                    .append(PlatformCampaignEmailRenderer.FONT_SANS)
                    .append(";font-size:15px;font-weight:400;color:")
                    .append(PlatformCampaignEmailRenderer.MUTED)
                    .append(";line-height:1.6;\">")
                    .append(inline(block))
                    .append("</p>");
        }
        return html.toString();
    }

    public static String toPlainText(String markdown) {
        if (markdown == null) {
            return "";
        }
        String text = markdown.replace("\r\n", "\n");
        text = LINK.matcher(text).replaceAll("$1 ($2)");
        text = BOLD.matcher(text).replaceAll("$1");
        return text.strip();
    }

    static String inline(String raw) {
        String escaped = escape(raw);
        Matcher links = LINK.matcher(escaped);
        StringBuffer withLinks = new StringBuffer();
        while (links.find()) {
            String label = links.group(1);
            String href = unescapeAmp(links.group(2));
            links.appendReplacement(
                    withLinks,
                    Matcher.quoteReplacement(
                            "<a href=\"" + escapeAttr(href) + "\" style=\"color:"
                                    + PlatformCampaignEmailRenderer.GREEN
                                    + ";text-decoration:underline;\">" + label + "</a>"));
        }
        links.appendTail(withLinks);

        Matcher bolds = BOLD.matcher(withLinks);
        StringBuffer withBold = new StringBuffer();
        while (bolds.find()) {
            bolds.appendReplacement(
                    withBold,
                    Matcher.quoteReplacement("<strong style=\"font-weight:600;color:"
                            + PlatformCampaignEmailRenderer.TEXT + ";\">" + bolds.group(1) + "</strong>"));
        }
        bolds.appendTail(withBold);

        Matcher urls = BARE_URL.matcher(withBold);
        StringBuffer withUrls = new StringBuffer();
        while (urls.find()) {
            String href = unescapeAmp(urls.group(1));
            urls.appendReplacement(
                    withUrls,
                    Matcher.quoteReplacement(
                            "<a href=\"" + escapeAttr(href) + "\" style=\"color:"
                                    + PlatformCampaignEmailRenderer.GREEN
                                    + ";text-decoration:underline;word-break:break-all;\">"
                                    + escape(href) + "</a>"));
        }
        urls.appendTail(withUrls);
        return withUrls.toString().replace("\n", "<br/>");
    }

    static List<String> splitParagraphs(String text) {
        String[] parts = text.split("\\n\\s*\\n");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String escapeAttr(String s) {
        return escape(s);
    }

    private static String unescapeAmp(String s) {
        return s == null ? "" : s.replace("&amp;", "&");
    }
}
