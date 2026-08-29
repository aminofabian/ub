package zelisline.ub.tenancy.api.dto;

public record CatalogSettingsResponse(
        /**
         * When true (default), product names show exactly as entered/imported on
         * Products, Stock, and POS. When false, names are title-cased for display
         * and on save (legacy grocery-style formatting).
         */
        boolean preserveProductNameCasing
) {
    public static CatalogSettingsResponse defaults() {
        return new CatalogSettingsResponse(true);
    }
}
