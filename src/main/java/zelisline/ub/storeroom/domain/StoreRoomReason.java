package zelisline.ub.storeroom.domain;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.inventory.WastageReason;

/**
 * Why something left (or entered) the store room.
 *
 * <p>Two classes, and the split decides whether stock moves:
 *
 * <ul>
 *   <li><b>Class A — moved, still ours</b> ({@link StoreRoomStockEffect#NONE}):
 *       restocking the shelf, prep, moving to a counter. The goods have not left
 *       the shop, so the ledger is untouched and the movement is pure narrative.</li>
 *   <li><b>Class B — gone</b> ({@link StoreRoomStockEffect#DECREASE}): spoilage,
 *       expiry, breakage, theft, staff use. These decrement stock.</li>
 * </ul>
 *
 * <p>Class B reasons that describe physical loss of goods also map onto the
 * existing {@link WastageReason}, so the store room reuses the wastage write path
 * (FEFO allocation + shrinkage journal) instead of inventing a parallel taxonomy.
 */
public enum StoreRoomReason {

    // ---- Class A: moved, still ours -------------------------------------
    RESTOCK_TO_SHELF("restock_to_shelf", StoreRoomDirection.OUT, StoreRoomStockEffect.NONE, null, false),
    KITCHEN_PREP("kitchen_prep", StoreRoomDirection.OUT, StoreRoomStockEffect.NONE, null, false),
    COUNTER_TRANSFER("counter_transfer", StoreRoomDirection.OUT, StoreRoomStockEffect.NONE, null, false),

    // ---- Class B: gone (physical loss → wastage path) -------------------
    SPOILAGE("spoilage", StoreRoomDirection.OUT, StoreRoomStockEffect.DECREASE, WastageReason.SPOILAGE, true),
    EXPIRED("expired", StoreRoomDirection.OUT, StoreRoomStockEffect.DECREASE, WastageReason.EXPIRED, true),
    BREAKAGE("breakage", StoreRoomDirection.OUT, StoreRoomStockEffect.DECREASE, WastageReason.BREAKAGE, true),
    CUSTOMER_RETURN("customer_return", StoreRoomDirection.OUT, StoreRoomStockEffect.DECREASE, WastageReason.CUSTOMER_RETURN, true),

    // ---- Class B: gone (shrinkage / correction → adjustment path) -------
    THEFT("theft", StoreRoomDirection.OUT, StoreRoomStockEffect.DECREASE, WastageReason.THEFT, false),
    STAFF_USE("staff_use", StoreRoomDirection.OUT, StoreRoomStockEffect.DECREASE, WastageReason.PERSONAL_USE, false),
    COUNT_CORRECTION("count_correction", StoreRoomDirection.OUT, StoreRoomStockEffect.DECREASE, WastageReason.COUNTING_ERROR, false),
    OTHER("other", StoreRoomDirection.OUT, StoreRoomStockEffect.DECREASE, WastageReason.OTHER, false),

    // ---- Put-in: a memo under the one-pool model (see scope §5) ---------
    RECEIVED_INTO_ROOM("received_into_room", StoreRoomDirection.IN, StoreRoomStockEffect.NONE, null, false);

    private final String wireValue;
    private final StoreRoomDirection direction;
    private final StoreRoomStockEffect stockEffect;
    private final WastageReason wastageReason;
    private final boolean wastagePath;

    StoreRoomReason(
            String wireValue,
            StoreRoomDirection direction,
            StoreRoomStockEffect stockEffect,
            WastageReason wastageReason,
            boolean wastagePath
    ) {
        this.wireValue = wireValue;
        this.direction = direction;
        this.stockEffect = stockEffect;
        this.wastageReason = wastageReason;
        this.wastagePath = wastagePath;
    }

    public String wireValue() {
        return wireValue;
    }

    public StoreRoomDirection direction() {
        return direction;
    }

    public StoreRoomStockEffect stockEffect() {
        return stockEffect;
    }

    /** The wastage taxonomy entry this reason writes, or {@code null} when not a wastage. */
    public WastageReason wastageReason() {
        return wastageReason;
    }

    /** True when the write should go through the FEFO wastage path. */
    public boolean usesWastagePath() {
        return wastagePath;
    }

    /** Human sentence used in ledger notes/reasons, so the ledger reads sensibly. */
    public String ledgerLabel() {
        return "Store room — " + name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** @throws ResponseStatusException 400 when {@code raw} is not a known reason. */
    public static StoreRoomReason fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reason is required");
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (StoreRoomReason reason : values()) {
            if (reason.wireValue.equals(needle)) {
                return reason;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown store room reason: " + raw);
    }
}
