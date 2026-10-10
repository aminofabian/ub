package zelisline.ub.crm.application;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import zelisline.ub.crm.domain.CrmOutbound;
import zelisline.ub.crm.repository.CrmOutboundRepository;
import zelisline.ub.integrations.whatsapp.config.WhatsAppChannelProperties;

/**
 * Pulls due {@code crm_outbound} rows and hands each to {@link CrmOutboundTxnService}
 * (short per-row transaction + HTTP). Mirrors {@code WebhookDeliveryWorker}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrmOutboundWorker {

    private final CrmOutboundRepository outboundRepository;
    private final CrmOutboundTxnService txnService;
    private final WhatsAppChannelProperties properties;

    public void processDue() {
        int batchSize = Math.max(1, properties.outboxBatchSize());
        List<CrmOutbound> batch =
                outboundRepository.findDuePending(Instant.now(), PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        for (CrmOutbound row : batch) {
            try {
                txnService.attempt(row.getId());
            } catch (RuntimeException ex) {
                log.warn("crm outbound attempt threw id={}", row.getId(), ex);
            }
        }
    }
}
