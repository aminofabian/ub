package zelisline.ub.integrations.whatsapp.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.platform.application.PlatformIntegrationSettingsService;

/**
 * Runtime on/off for the WhatsApp channel, overridable from the Super Admin console.
 *
 * <p>Each switch resolves <b>DB-first</b>: a value set in the console (stored on
 * {@code platform_integration_settings.whatsapp_channel_enabled / whatsapp_outbox_enabled}) wins;
 * when unset (NULL) the deployment flag applies ({@code app.integrations.whatsapp.enabled} /
 * {@code app.integrations.whatsapp.outbox.enabled}). This lets an operator turn the channel on or
 * off without a redeploy. See {@code docs/scopes/whatsapp-crm/KEYS.md}.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppChannelSettingsService {

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @Value("${app.integrations.whatsapp.enabled:false}")
    private boolean envInboundEnabled;

    @Value("${app.integrations.whatsapp.outbox.enabled:false}")
    private boolean envOutboxEnabled;

    /** Inbound ingest + delivery status + auto-reply. */
    @Transactional(readOnly = true)
    public boolean inboundEnabled() {
        Boolean override = platformIntegrationSettingsService.resolveWhatsAppChannel().inboundEnabled();
        return override != null ? override : envInboundEnabled;
    }

    /** Outbound drain — the actual Meta sends. */
    @Transactional(readOnly = true)
    public boolean outboxEnabled() {
        Boolean override = platformIntegrationSettingsService.resolveWhatsAppChannel().outboundEnabled();
        return override != null ? override : envOutboxEnabled;
    }

    @Transactional(readOnly = true)
    public Flags flags() {
        var row = platformIntegrationSettingsService.resolveWhatsAppChannel();
        return new Flags(
                row.inboundEnabled() != null ? row.inboundEnabled() : envInboundEnabled,
                row.outboundEnabled() != null ? row.outboundEnabled() : envOutboxEnabled,
                envInboundEnabled,
                envOutboxEnabled);
    }

    /** {@code null} leaves a switch inheriting the deployment flag. */
    @Transactional
    public Flags update(Boolean inboundEnabled, Boolean outboundEnabled) {
        platformIntegrationSettingsService.updateWhatsAppChannel(inboundEnabled, outboundEnabled);
        return flags();
    }

    /** Resolved switch state plus the deployment defaults it may be inheriting. */
    public record Flags(
            boolean inboundEnabled,
            boolean outboundEnabled,
            boolean inboundEnvDefault,
            boolean outboundEnvDefault
    ) {
    }
}
