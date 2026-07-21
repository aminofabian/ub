package zelisline.ub.platform.application;

/**
 * Resolved Sozuri SMS settings (platform DB → optional env defaults).
 */
public record ResolvedSozuriSmsConfig(
        String provider,
        String project,
        String apiKey,
        String from,
        String type,
        String apiUrl
) {
    public boolean sozuriReady() {
        return "sozuri".equalsIgnoreCase(provider)
                && project != null && !project.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }
}
