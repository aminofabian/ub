package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Page of stock movements the cloud created for the shop, pulled by the till to
 * keep its own count in step with cloud-origin activity (online-storefront
 * sales, cloud purchases, stocktakes, manual adjustments). The till is the
 * stock source of truth for what it sold; it applies only movements it did not
 * originate (see {@code DesktopSyncPullService.applyCloudMovement}).
 */
public record InventoryMovementSyncSnapshot(List<MovementData> movements) {

    public record MovementData(
            String id,
            String itemId,
            String branchId,
            BigDecimal quantityDelta,
            String movementType,
            String referenceType,
            String referenceId,
            Instant createdAt
    ) {}
}
