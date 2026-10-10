package zelisline.ub.crm.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import zelisline.ub.crm.application.CrmOutboundWorker;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelSettingsService;

/**
 * Drains the CRM outbound outbox. The tick is gated by the runtime switch
 * ({@link WhatsAppChannelSettingsService#outboxEnabled()}) so an operator can turn sending on or
 * off from Super Admin → Platform → WhatsApp numbers without a redeploy; the bean itself can be
 * disabled outright with {@code app.integrations.whatsapp.outbox.scheduler-enabled=false}.
 * Mirrors {@code WebhookDeliveryScheduler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.integrations.whatsapp.outbox.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CrmOutboundScheduler {

    private final CrmOutboundWorker crmOutboundWorker;
    private final WhatsAppChannelSettingsService channelSettings;

    @Scheduled(
            fixedDelayString = "${app.integrations.whatsapp.outbox.fixed-delay-ms:15000}",
            initialDelayString = "${app.integrations.whatsapp.outbox.initial-delay-ms:5000}"
    )
    public void tick() {
        if (!channelSettings.outboxEnabled()) {
            return;
        }
        try {
            crmOutboundWorker.processDue();
        } catch (RuntimeException ex) {
            log.warn("CRM outbound scheduler tick failed", ex);
        }
    }
}
