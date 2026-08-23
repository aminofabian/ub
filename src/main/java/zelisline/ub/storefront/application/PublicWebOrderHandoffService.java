package zelisline.ub.storefront.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Records that the shopper *opened* the WhatsApp handoff (scope §10 / §15).
 * Explicitly not a claim that a message was sent — the dashboard copy must
 * reflect that distinction.
 */
@Service
@RequiredArgsConstructor
public class PublicWebOrderHandoffService {

    private final BusinessRepository businessRepository;
    private final WebOrderRepository webOrderRepository;

    @Transactional
    public boolean recordOpened(String slug, String orderId) {
        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug.trim()).orElse(null);
        if (business == null) {
            return false;
        }
        WebOrder order = webOrderRepository
                .findByIdAndBusinessId(orderId.trim(), business.getId())
                .orElse(null);
        if (order == null) {
            return false;
        }
        Instant now = Instant.now();
        order.setHandoffOpenedAt(now);
        // "expired" is a terminal observation; reopening after expiry is still visible
        // through expires_at / the dashboard hint.
        String state = order.getHandoffState();
        if (!"expired".equals(state)) {
            order.setHandoffState(state == null || state.isBlank() ? "opened" : "reopened");
        }
        webOrderRepository.save(order);
        return true;
    }
}
