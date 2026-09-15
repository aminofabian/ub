package zelisline.ub.storeroom.domain;

import java.util.Locale;

/**
 * Where a movement stands.
 *
 * <p>Only {@link #PENDING} matters for correctness: a pending movement has <b>not</b>
 * touched stock yet. It is a request, and the ledger write happens when somebody
 * approves it — so nothing may treat a pending row as stock that left.
 */
public enum StoreRoomMovementStatus {

    /** Recorded and done. Stock has moved (or never needed to). */
    APPLIED("applied"),

    /** Above the approval threshold; waiting on a decision. Stock untouched. */
    PENDING("pending"),

    /** Turned down. Stock untouched, and never will be. */
    REJECTED("rejected");

    private final String wireValue;

    StoreRoomMovementStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static StoreRoomMovementStatus fromWire(String raw) {
        String needle = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (StoreRoomMovementStatus status : values()) {
            if (status.wireValue.equals(needle)) {
                return status;
            }
        }
        return APPLIED;
    }
}
