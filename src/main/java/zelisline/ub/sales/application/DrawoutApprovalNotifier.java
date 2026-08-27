package zelisline.ub.sales.application;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.notifications.application.NotificationTemplateRenderer;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.CashDrawout;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class DrawoutApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger(DrawoutApprovalNotifier.class);
    private static final String APPROVE_PERMISSION = "shifts.drawouts.approve";
    static final Duration LINK_TTL = Duration.ofHours(24);

    private final NotificationService notificationService;
    private final NotificationTemplateRenderer templateRenderer;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final DrawoutApprovalToken drawoutApprovalToken;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    public void notifyInitiated(String businessId, Shift shift, CashDrawout drawout, String initiatedByName) {
        boolean pending = SalesConstants.DRAWOUT_STATUS_PENDING_APPROVAL.equals(drawout.getStatus());
        String reviewUrl = publicReviewUrl(drawout);
        String inAppUrl = "/shifts?drawout=" + drawout.getId();
        String type = pending
                ? NotificationTypes.DRAWOUT_APPROVAL_REQUESTED
                : NotificationTypes.DRAWOUT_RECORDED;

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("drawoutId", drawout.getId());
        vars.put("shiftId", drawout.getShiftId());
        vars.put("amount", drawout.getAmount() != null ? drawout.getAmount().toPlainString() : "");
        vars.put("currency", currency(businessId));
        vars.put("category", drawout.getCategory() != null ? drawout.getCategory().replace('_', ' ') : "");
        vars.put("recipientName", blankToDash(drawout.getRecipientName()));
        vars.put("initiatedByName", blankToDash(initiatedByName));
        vars.put("description", blankToDash(drawout.getDescription()));
        vars.put("actionUrl", inAppUrl);

        NotificationTemplateRenderer.RenderedNotification rendered =
                templateRenderer.render(businessId, type, vars);
        String payloadJson = buildPayload(rendered, vars);

        java.util.LinkedHashSet<String> approverIds = new java.util.LinkedHashSet<>(
                userRepository.findIdsWithPermission(businessId, APPROVE_PERMISSION));
        if (approverIds.isEmpty()) {
            // Fall back to owner/admin so a missing grant still reaches someone.
            for (String roleKey : List.of(IdentityService.OWNER_ROLE_KEY, "admin")) {
                for (User u : userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(businessId, roleKey)) {
                    approverIds.add(u.getId());
                }
            }
        }
        for (String userId : approverIds) {
            notificationService.tryInsertDedupeForUser(
                    businessId,
                    userId,
                    type,
                    type + ":" + drawout.getId() + ":" + userId,
                    rendered.category(),
                    rendered.priority(),
                    payloadJson);
        }

        eventPublisher.publishEvent(new RealtimeBridge.DrawoutInitiatedEvent(
                businessId,
                shift.getBranchId(),
                shift.getId(),
                drawout.getId(),
                drawout.getAmount(),
                drawout.getCategory(),
                drawout.getDescription(),
                drawout.getRecipientName(),
                initiatedByName,
                pending,
                reviewUrl));
    }

    public String publicReviewUrl(CashDrawout drawout) {
        Instant exp = drawout.getExpiresAt() != null
                ? drawout.getExpiresAt()
                : Instant.now().plus(LINK_TTL);
        if (exp.isBefore(Instant.now().plusSeconds(60))) {
            exp = Instant.now().plus(LINK_TTL);
        }
        String token = drawoutApprovalToken.issue(drawout.getId(), exp);
        String base = frontendBaseUrl == null || frontendBaseUrl.isBlank()
                ? "http://localhost:3000"
                : frontendBaseUrl.trim().replaceAll("/+$", "");
        return base + "/drawouts/review?token=" + token;
    }

    public String resolveLinkActorUserId(String businessId) {
        List<String> approverIds = userRepository.findIdsWithPermission(businessId, APPROVE_PERMISSION);
        if (!approverIds.isEmpty()) {
            return approverIds.get(0);
        }
        return userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(businessId, IdentityService.OWNER_ROLE_KEY)
                .stream()
                .map(User::getId)
                .findFirst()
                .or(() -> userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(businessId, "admin")
                        .stream()
                        .map(User::getId)
                        .findFirst())
                .orElse(null);
    }

    private String currency(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getCurrency)
                .filter(c -> c != null && !c.isBlank())
                .orElse("KES");
    }

    private String buildPayload(
            NotificationTemplateRenderer.RenderedNotification rendered,
            Map<String, String> variables
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(variables);
        payload.put("title", rendered.title());
        payload.put("body", rendered.body());
        payload.put("actionUrl", rendered.actionUrl() != null && !rendered.actionUrl().isBlank()
                ? rendered.actionUrl()
                : variables.getOrDefault("actionUrl", "/shifts"));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize drawout notification payload", e);
            return "{}";
        }
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }
}
