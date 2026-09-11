package zelisline.ub.sales.api.dto;

public record CompanionSkuRow(
        String itemId,
        String itemName,
        String itemSku,
        long togetherCount
) {
}
