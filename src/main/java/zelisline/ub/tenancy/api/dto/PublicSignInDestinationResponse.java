package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One place an apex visitor can continue after identifying with email or phone.
 *
 * <p>{@code door} tells the frontend which login surface to open on the shop
 * host (or the platform supplier portal): {@code STAFF}, {@code SHOPPER}, or
 * {@code SUPPLIER}. Shop rows carry {@code slug} / {@code primaryHost}; the
 * supplier portal row may omit both and is opened on the apex origin.
 *
 * @param slug        tenant URL slug, or {@code null} for platform portals
 * @param name        display name (shop or portal)
 * @param logoUrl     branding logo when set
 * @param primaryHost tenant primary host when mapped
 * @param door        {@code STAFF}, {@code SHOPPER}, or {@code SUPPLIER}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicSignInDestinationResponse(
        String slug,
        String name,
        String logoUrl,
        String primaryHost,
        String door
) {
    public static final String DOOR_STAFF = "STAFF";
    public static final String DOOR_SHOPPER = "SHOPPER";
    public static final String DOOR_SUPPLIER = "SUPPLIER";
}
