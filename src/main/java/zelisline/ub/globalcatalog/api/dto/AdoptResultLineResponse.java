package zelisline.ub.globalcatalog.api.dto;

public record AdoptResultLineResponse(
        String globalProductId,
        String status,
        String itemId,
        String sku,
        String message
) {
}
