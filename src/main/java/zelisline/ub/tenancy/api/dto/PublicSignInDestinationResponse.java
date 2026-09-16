package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One place an apex visitor can continue after identifying with email or phone.
 *
 * <p>{@code door} tells the frontend which login surface to open on the shop
 * host (or the platform supplier portal): {@code STAFF}, {@code SHOPPER},
 * {@code SUPPLIER}, {@code SUPPLIER_CLAIM}, {@code STAFF_UNVERIFIED}, or
 * {@code SHOPPER_UNVERIFIED}. Shop rows carry
 * {@code slug} / {@code primaryHost}; supplier rows may omit both and are
 * opened on the apex origin. Unverified doors mean the membership is still
 * {@code INVITED} — open verify-email on the shop host, not password login.
 *
 * @param slug        tenant URL slug, or {@code null} for platform portals
 * @param name        display name (shop or portal)
 * @param logoUrl     branding logo when set
 * @param primaryHost tenant primary host when mapped
 * @param door        {@code STAFF}, {@code SHOPPER}, {@code SUPPLIER},
 *                    {@code SUPPLIER_CLAIM}, {@code STAFF_UNVERIFIED}, or
 *                    {@code SHOPPER_UNVERIFIED}
 * @param hint        one line describing what opening this pass asks for
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicSignInDestinationResponse(
        String slug,
        String name,
        String logoUrl,
        String primaryHost,
        String door,
        String hint
) {
    public static final String DOOR_STAFF = "STAFF";
    public static final String DOOR_SHOPPER = "SHOPPER";
    public static final String DOOR_SUPPLIER = "SUPPLIER";
    public static final String DOOR_SUPPLIER_CLAIM = "SUPPLIER_CLAIM";
    /** Owner/staff membership waiting on email verification. */
    public static final String DOOR_STAFF_UNVERIFIED = "STAFF_UNVERIFIED";
    /** Buyer membership waiting on email verification. */
    public static final String DOOR_SHOPPER_UNVERIFIED = "SHOPPER_UNVERIFIED";
}
