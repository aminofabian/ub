package zelisline.ub.marketplace.api.dto;

import java.util.List;

/**
 * What an attach did. The counters add up the shelf rows that were processed; {@code notes} names
 * the rows that were not added as brand-new items (already in the shop, already linked, or skipped
 * with a reason) and is capped, with {@code notesOmitted} counting any that did not fit.
 */
public record MarketplaceAttachResponse(
        String connectionId,
        String localSupplierId,
        String marketplaceSupplierId,
        String supplierNumber,
        String supplierName,
        int linkedExisting,
        int createdItems,
        int alreadyLinked,
        int skipped,
        String status,
        List<MarketplaceAttachNote> notes,
        int notesOmitted
) {
    public int importedProductLinks() {
        return linkedExisting + createdItems;
    }
}
