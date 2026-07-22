package zelisline.ub.globalcatalog.api.dto;

public record ReplaceCatalogResponse(
        int softDeletedCount,
        AdoptResponse adopt
) {
}
