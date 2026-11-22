package zelisline.ub.marketplace.api.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A marketplace buyer session. {@code token} is present only in the reply that opens it. {@code
 * shops} are the shops this phone number is staff of: the shops it may place orders for.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuyerSessionResponse(String token, Instant expiresAt, List<Shop> shops) {

    public record Shop(String businessId, String name) {
    }
}
