package zelisline.ub.notifications.application;

/**
 * Staff notification mail. In-app action paths stay relative; email clients have no
 * app origin, so a path like {@code /business?pocket=1} is opened as {@code http://business/}.
 */
final class OpenInPalmartEmail {

    private OpenInPalmartEmail() {
    }

    static String absoluteUrl(String frontendBaseUrl, String actionUrl, String fallbackPath) {
        String path = hasText(actionUrl) ? actionUrl.trim() : fallbackPath;
        if (!hasText(path)) {
            path = "/";
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String base = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base.isEmpty() ? path : base + path;
    }

    static String html(String frontendBaseUrl, String title, String body, String actionUrl) {
        String heading = hasText(title) ? title : "Palmart";
        String message = body == null ? "" : body;
        String link = absoluteUrl(frontendBaseUrl, actionUrl, "/");
        return """
                <!DOCTYPE html><html><body style="font-family:sans-serif;line-height:1.5">
                <h2>%s</h2><p>%s</p><p><a href="%s">Open in Palmart</a></p>
                </body></html>
                """.formatted(escapeHtml(heading), escapeHtml(message), escapeHtml(link));
    }

    static String text(String frontendBaseUrl, String title, String body, String actionUrl) {
        String message = hasText(body)
                ? (title == null ? "" : title) + ": " + body
                : (title == null ? "" : title);
        String link = absoluteUrl(frontendBaseUrl, actionUrl, "/");
        return message + "\n\nOpen in Palmart: " + link;
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
