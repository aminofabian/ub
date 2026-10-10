package zelisline.ub.integrations.whatsapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the WhatsApp channel adapter. Meta keys are platform-owned (super-admin);
 * these are adapter knobs only.
 *
 * <p>{@code enabled} gates the inbound hook and the outbox drain. It defaults to {@code false}
 * so {@code local}/{@code desktop} profiles and existing cloud installs are unaffected until
 * M1 is switched on explicitly.
 */
@ConfigurationProperties(prefix = "app.integrations.whatsapp")
public record WhatsAppChannelProperties(
        boolean enabled,
        String defaultGraphVersion,
        int outboxBatchSize
) {

    public WhatsAppChannelProperties {
        if (defaultGraphVersion == null || defaultGraphVersion.isBlank()) {
            defaultGraphVersion = "v25.0";
        }
        if (outboxBatchSize <= 0) {
            outboxBatchSize = 50;
        }
    }
}
