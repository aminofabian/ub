package zelisline.ub.wholesale.application;

import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.application.SupportService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.wholesale.api.dto.StandingExclusionResponse;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

/**
 * Supplier appeals on a counted order (decision D9: platform support, in the existing support chat), and platform
 * support's reversible exclusion of an order from standing. Off unless the standing flag is on.
 */
@Service
@RequiredArgsConstructor
public class SupplierAppealService {

    static final int MAX_REASON_LENGTH = 1000;

    private final FeatureFlagService featureFlagService;
    private final SalesOrderRepository salesOrderRepository;
    private final SupportService supportService;
    private final ObjectMapper objectMapper;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    @Transactional
    public SupportMessageDto appeal(String businessId, String userId, String orderId, String rawReason) {
        if (killSwitch
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_STANDING)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        SalesOrder order = salesOrderRepository.findById(orderId)
                .filter(o -> businessId.equals(o.getBusinessId()) && o.getAnsweredAt() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what is wrong with this order");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reason is too long");
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("orderId", order.getId()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode the appeal payload", e);
        }
        return supportService.sendTenantAppeal(
                businessId, userId, "appeal:" + order.getId(),
                "Appeal on order " + order.getId() + ": " + reason, payload);
    }

    /**
     * Platform support only. Excluding needs a note; restoring keeps the last note and time for the record, so
     * the decision can be reversed and still explained.
     */
    @Transactional
    public StandingExclusionResponse setStandingExcluded(
            String orderId, boolean excluded, String rawNote, String adminId, String adminName) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        String note = rawNote == null || rawNote.isBlank() ? null : rawNote.trim();
        if (excluded && note == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason for excluding this order");
        }
        order.setStandingExcluded(excluded);
        if (excluded) {
            order.setStandingExcludedAt(Instant.now());
            order.setStandingExclusionNote(note);
        } else if (note != null) {
            order.setStandingExclusionNote(note);
        }
        salesOrderRepository.save(order);
        announceDecision(order, excluded, note, adminId, adminName);
        return new StandingExclusionResponse(
                order.getId(), order.isStandingExcluded(), order.getStandingExcludedAt(),
                order.getStandingExclusionNote());
    }

    @Transactional(readOnly = true)
    public StandingExclusionResponse standingExclusion(String orderId) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        return new StandingExclusionResponse(
                order.getId(), order.isStandingExcluded(), order.getStandingExcludedAt(),
                order.getStandingExclusionNote());
    }

    /** The supplier sees each platform decision, and the reason, in its own support thread. */
    private void announceDecision(SalesOrder order, boolean excluded, String note, String adminId, String adminName) {
        var detail = supportService.ensureTenantThreadForAdmin(order.getBusinessId(), adminId, adminName);
        if (detail.conversation() == null) {
            return;
        }
        String reason = note == null ? "" : " Reason: " + note;
        String body = excluded
                ? "Platform support left order " + order.getId() + " out of your standing." + reason
                : "Platform support put order " + order.getId() + " back into your standing." + reason;
        supportService.sendAdminMessage(detail.conversation().id(), adminId, adminName,
                new SendSupportMessageRequest(body, null, null, null));
    }
}
