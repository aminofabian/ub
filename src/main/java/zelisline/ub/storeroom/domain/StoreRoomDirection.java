package zelisline.ub.storeroom.domain;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Which way stock moved through the store room door. */
public enum StoreRoomDirection {

    IN("in"),
    OUT("out");

    private final String wireValue;

    StoreRoomDirection(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /** @throws ResponseStatusException 400 when {@code raw} is not `in` or `out`. */
    public static StoreRoomDirection fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Direction is required");
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (StoreRoomDirection direction : values()) {
            if (direction.wireValue.equals(needle)) {
                return direction;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown direction: " + raw);
    }
}
