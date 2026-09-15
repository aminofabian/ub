package zelisline.ub.storeroom.domain;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * How a store room keeps its counts.
 *
 * <p>Persisted with {@link jakarta.persistence.EnumType#STRING}, so the column holds
 * {@code STANDALONE} / {@code CONNECTED}. The API speaks lower-case ({@link #wireValue()})
 * because that is what the dashboard sends and renders.
 */
public enum StoreRoomMode {

    /**
     * Manual back-room register. Rows are free text and counts are typed in by hand —
     * nothing to do with the sellable catalogue.
     */
    STANDALONE("standalone"),

    /**
     * Rows mirror catalogue products. Counts are read from inventory, so they move on
     * their own as products sell.
     */
    CONNECTED("connected");

    private final String wireValue;

    StoreRoomMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Lower-case form used on the wire and by the dashboard. */
    public String wireValue() {
        return wireValue;
    }

    /** @throws ResponseStatusException 400 when {@code raw} is not a known mode. */
    public static StoreRoomMode fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store room mode is required");
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (StoreRoomMode mode : values()) {
            if (mode.wireValue.equals(needle)) {
                return mode;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown store room mode: " + raw);
    }
}
