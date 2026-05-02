package zelisline.ub.identity.domain;

/**
 * Lifecycle states for a tenant user (PHASE_1_PLAN.md §2.1, {@code users.status}).
 *
 * <p>Stored in the database as the lowercase string form so we can introduce
 * additional states later without a migration. The enum is the source of truth
 * for what the application accepts.
 */
public enum UserStatus {

    ACTIVE,
    INVITED,
    SUSPENDED,
    LOCKED;

    /** Database wire form: lowercase name. */
    public String wire() {
        return name().toLowerCase();
    }

    public static UserStatus fromWire(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        return UserStatus.valueOf(value.trim().toUpperCase());
    }
}
