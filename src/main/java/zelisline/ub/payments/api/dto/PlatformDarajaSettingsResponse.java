package zelisline.ub.payments.api.dto;

import java.time.Instant;

public record PlatformDarajaSettingsResponse(
        boolean enabled,
        String environment,
        String shortcodeType,
        String shortcode,
        boolean hasCredentials,
        String consumerKeyHint,
        /** True when initiator name/password are set — enables Daraja B2B custody settle. */
        boolean disburseConfigured,
        String initiatorName,
        String b2bShortcode,
        /** {@code shortcode}, {@code till}, or {@code paybill}. */
        String platformDestinationType,
        String platformDestinationTill,
        String platformDestinationPaybill,
        String platformDestinationAccount,
        Instant updatedAt
) {
}
