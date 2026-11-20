package zelisline.ub.pharmacy.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the till must do before a medicine can be sold (ADR 0033).
 *
 * <p>The shop's pharmacist or owner sets the class; the platform stores and enforces it and does
 * not claim to know the legal schedule of a product.</p>
 */
public enum DispensingClass {

    /** Nobody has confirmed a class yet. Cannot be sold until one is set. */
    UNCLASSIFIED,

    /** Over the counter: sells like any other product. */
    OTC,

    /** Needs a dispensing record before the sale completes. */
    PRESCRIPTION_ONLY,

    /** Needs a dispensing record and, later, a controlled-stock register entry. */
    CONTROLLED;

    /** True when a sale of this class needs a dispensing record. */
    public boolean needsDispensingRecord() {
        return this == PRESCRIPTION_ONLY || this == CONTROLLED;
    }

    /** True when the public storefront must not offer this class for sale. */
    public boolean blockedOnline() {
        return this != OTC;
    }

    public static DispensingClass parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dispensing class is required");
        }
        String cleaned = raw.strip().toUpperCase().replace('-', '_').replace(' ', '_');
        for (DispensingClass c : values()) {
            if (c.name().equals(cleaned)) {
                return c;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown dispensing class: " + raw);
    }
}
