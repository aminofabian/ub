package zelisline.ub.tenancy.api.dto;

/**
 * Whether a name can be used as the shop's address.
 *
 * @param name      the name as it would be saved (lower-cased, suffix stripped)
 * @param host      the full address it would give, when the name is well formed enough to show
 * @param available true when the shop can switch to it now
 * @param reason    why not, in words fit to show beside the field; null when available
 */
public record ShopAddressCheckResponse(
        String name,
        String host,
        boolean available,
        String reason
) {
}
