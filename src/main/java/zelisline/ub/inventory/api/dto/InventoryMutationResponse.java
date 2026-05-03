package zelisline.ub.inventory.api.dto;

public record InventoryMutationResponse(
        String journalEntryId,
        String stockMovementId,
        String inventoryBatchId
) {
}
