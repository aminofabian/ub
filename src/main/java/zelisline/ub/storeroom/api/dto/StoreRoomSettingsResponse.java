package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Store-room connection state plus a small census of the register, so the dashboard
 * can render the prompt, the "linked to inventory" banner, and the "N still to link"
 * nudge without a second round trip.
 *
 * @param mode              {@code "standalone"}, {@code "connected"}, or {@code null}
 *                          when the merchant has not chosen yet
 * @param connectedAt       when the business first connected, if ever
 * @param approvalThreshold ask before more than this leaves stock; {@code null} = never ask
 * @param itemCount         total store-room rows
 * @param linkedCount       rows tied to a catalogue product
 * @param unlinkedCount     rows with no catalogue product
 * @param linkedNow         how many rows this very request auto-linked by barcode
 *                          (always 0 on reads)
 */
public record StoreRoomSettingsResponse(
        String mode,
        Instant connectedAt,
        BigDecimal approvalThreshold,
        int itemCount,
        int linkedCount,
        int unlinkedCount,
        int linkedNow
) {
}
