package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.notifications.application.NotificationTemplateRenderer;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Tells owners and admins that a sale was voided. The person who voided it is
 * left out. A failure here must not undo the void.
 */
@Service
@RequiredArgsConstructor
public class SaleVoidNotifier {

    private static final Logger log = LoggerFactory.getLogger(SaleVoidNotifier.class);
    private static final int MONEY_SCALE = 2;
    private static final int MAX_REASON_LENGTH = 120;
    private static final String DEFAULT_CURRENCY = "KES";
    private static final String FALLBACK_ACTOR = "Someone";
    private static final String FALLBACK_RECEIPT = "a sale";
    static final String ACTIVITY_PATH = "/business/logs";

    private final NotificationService notificationService;
    private final NotificationTemplateRenderer templateRenderer;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final SaleActorNameService saleActorNameService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyVoided(String businessId, Sale sale, String voidedByUserId, String reason) {
        try {
            deliver(businessId, sale, voidedByUserId, reason);
        } catch (RuntimeException ex) {
            log.warn("Sale void notice failed businessId={} saleId={}", businessId, sale.getId(), ex);
        }
    }

    private void deliver(String businessId, Sale sale, String voidedByUserId, String reason) {
        LinkedHashSet<String> recipients = recipients(businessId, voidedByUserId);
        if (recipients.isEmpty()) {
            return;
        }
        Map<String, String> vars = variables(businessId, sale, voidedByUserId, reason);
        NotificationTemplateRenderer.RenderedNotification rendered =
                templateRenderer.render(businessId, NotificationTypes.SALE_VOIDED, vars);
        String payloadJson = payload(rendered, vars);
        for (String userId : recipients) {
            notificationService.tryInsertDedupeForUser(
                    businessId,
                    userId,
                    NotificationTypes.SALE_VOIDED,
                    NotificationTypes.SALE_VOIDED + ":" + sale.getId() + ":" + userId,
                    rendered.category(),
                    rendered.priority(),
                    payloadJson);
        }
    }

    private LinkedHashSet<String> recipients(String businessId, String voidedByUserId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String roleKey : List.of(IdentityService.OWNER_ROLE_KEY, IdentityService.ADMIN_ROLE_KEY)) {
            addRole(ids, businessId, roleKey, voidedByUserId);
        }
        return ids;
    }

    private void addRole(LinkedHashSet<String> ids, String businessId, String roleKey, String voidedByUserId) {
        for (User user : userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(businessId, roleKey)) {
            if (user.getId() != null && !user.getId().equals(voidedByUserId)) {
                ids.add(user.getId());
            }
        }
    }

    private Map<String, String> variables(String businessId, Sale sale, String voidedByUserId, String reason) {
        String actor = saleActorNameService.resolveSoldByName(businessId, voidedByUserId);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("voidedByName", blank(actor) ? FALLBACK_ACTOR : actor.trim());
        vars.put("receiptLabel", receiptLabel(sale));
        vars.put("amount", money(sale));
        vars.put("currency", currency(businessId));
        vars.put("reasonSuffix", reasonSuffix(reason));
        vars.put("saleId", sale.getId());
        vars.put("actionUrl", ACTIVITY_PATH);
        return vars;
    }

    private String payload(
            NotificationTemplateRenderer.RenderedNotification rendered,
            Map<String, String> variables
    ) {
        Map<String, Object> body = new LinkedHashMap<>(variables);
        body.put("title", blank(rendered.title()) ? "Sale voided" : rendered.title());
        body.put("body", blank(rendered.body()) ? fallbackBody(variables) : rendered.body());
        body.put("actionUrl", blank(rendered.actionUrl()) ? ACTIVITY_PATH : rendered.actionUrl());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize sale void notice", ex);
            return "{}";
        }
    }

    private String currency(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getCurrency)
                .filter(value -> !blank(value))
                .orElse(DEFAULT_CURRENCY);
    }

    private static String fallbackBody(Map<String, String> vars) {
        return vars.get("voidedByName")
                + " voided sale "
                + vars.get("receiptLabel")
                + " · "
                + vars.get("currency")
                + " "
                + vars.get("amount")
                + vars.get("reasonSuffix");
    }

    private static String receiptLabel(Sale sale) {
        if (sale.getReceiptNo() == null) {
            return FALLBACK_RECEIPT;
        }
        return "#" + sale.getReceiptNo();
    }

    private static String money(Sale sale) {
        BigDecimal total = sale.getGrandTotal() == null ? BigDecimal.ZERO : sale.getGrandTotal();
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String reasonSuffix(String reason) {
        if (blank(reason)) {
            return "";
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            trimmed = trimmed.substring(0, MAX_REASON_LENGTH - 3) + "...";
        }
        return ". Reason: " + trimmed;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
