package zelisline.ub.tenancy.api.dto;

import java.util.List;

/**
 * Result of changing the shop's address.
 *
 * @param host         the new primary address
 * @param previousHost the address it replaced, which still opens the shop; may be null
 * @param url          the new address with its scheme, ready to open
 * @param notified     channels the owner was told on: {@code email}, {@code whatsapp}, {@code sms}
 */
public record ShopAddressChangeResponse(
        String host,
        String previousHost,
        String url,
        List<String> notified
) {
}
