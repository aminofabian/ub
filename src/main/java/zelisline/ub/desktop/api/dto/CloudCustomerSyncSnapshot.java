package zelisline.ub.desktop.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Page of customers (with phones + live credit) changed at/after the till's
 * customer cursor.
 *
 * <p>Before this, the only way a customer reached the till was by riding along
 * with a new cloud sale — so a customer or credit edit with no accompanying
 * sale never synced. {@code nextCursor} is the newest activity on the page
 * (row, phone, or credit), so the till advances past rows whose only change was
 * a phone/credit child row.
 */
public record CloudCustomerSyncSnapshot(
        List<CloudSalesSnapshot.CloudCustomerData> customers,
        Instant nextCursor
) {}
