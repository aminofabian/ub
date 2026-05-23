package zelisline.ub.platform.application;

public record ResolvedCatalogAiConfig(String apiKey, String host, String url, String model) {
    public boolean configured() {
        return apiKey != null
                && !apiKey.isBlank()
                && host != null
                && !host.isBlank()
                && url != null
                && !url.isBlank()
                && model != null
                && !model.isBlank();
    }
}
