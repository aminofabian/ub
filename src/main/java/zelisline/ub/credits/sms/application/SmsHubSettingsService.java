package zelisline.ub.credits.sms.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.AutomationsResponse;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.OrderNoticeSetting;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.UpdateAutomationsRequest;
import zelisline.ub.credits.sms.domain.BusinessSmsHubSettings;
import zelisline.ub.credits.sms.domain.SmsSendWindow;
import zelisline.ub.credits.sms.domain.SmsTemplates;
import zelisline.ub.credits.sms.repository.BusinessSmsHubSettingsRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Per-shop SMS automation settings (SMS Hub, ADR 0013): which order updates are sent
 * and how they are worded, and the thank-you and win-back rules.
 */
@Service
@RequiredArgsConstructor
public class SmsHubSettingsService {

    /** Order notices raised by the storefront, in the order they happen. */
    public static final List<String> ORDER_NOTICES =
            List.of("stk_sent", "payment_received", "confirmed", "dispatched", "completed");

    public static final Set<String> ORDER_VARIABLES = Set.of("name", "order_no", "amount", "shop");
    public static final Set<String> CUSTOMER_VARIABLES = Set.of("name", "first_name", "shop");

    public static final String DEFAULT_THANK_YOU =
            "Hi {first_name}, thank you for shopping at {shop}. We hope to see you again soon.";
    public static final String DEFAULT_WINBACK =
            "Hi {first_name}, we have missed you at {shop}. Come by this week, we would love to see you.";

    private static final int MAX_TEMPLATE = 320;
    private static final TypeReference<Map<String, OrderNoticeSetting>> NOTICE_MAP = new TypeReference<>() {
    };

    private final BusinessSmsHubSettingsRepository repository;
    private final BusinessRepository businessRepository;
    private final ObjectMapper objectMapper;

    /** Whether an order notice should be sent and, when the shop reworded it, its template. */
    public record OrderNoticeRule(boolean enabled, String template) {
    }

    /** Stored settings, or unsaved defaults for a shop that has never changed them. */
    @Transactional(readOnly = true)
    public BusinessSmsHubSettings load(String businessId) {
        return repository.findById(businessId).orElseGet(() -> {
            BusinessSmsHubSettings defaults = new BusinessSmsHubSettings();
            defaults.setBusinessId(businessId);
            return defaults;
        });
    }

    @Transactional(readOnly = true)
    public OrderNoticeRule orderNotice(String businessId, String notice) {
        OrderNoticeSetting setting = notices(load(businessId)).get(notice);
        if (setting == null) {
            return new OrderNoticeRule(true, null);
        }
        boolean enabled = setting.enabled() == null || setting.enabled();
        return new OrderNoticeRule(enabled, blankToNull(setting.template()));
    }

    /** Shop name for the {shop} variable. */
    @Transactional(readOnly = true)
    public String shopName(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getName)
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .orElse("our shop");
    }

    @Transactional(readOnly = true)
    public AutomationsResponse view(String businessId, String optOutFooter) {
        return toResponse(load(businessId), optOutFooter);
    }

    @Transactional
    public AutomationsResponse update(String businessId, UpdateAutomationsRequest request, String optOutFooter) {
        BusinessSmsHubSettings settings = load(businessId);

        if (request.orderNotices() != null) {
            Map<String, OrderNoticeSetting> merged = notices(settings);
            for (Map.Entry<String, OrderNoticeSetting> entry : request.orderNotices().entrySet()) {
                if (!ORDER_NOTICES.contains(entry.getKey()) || entry.getValue() == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Unknown order update: " + entry.getKey());
                }
                String template = blankToNull(entry.getValue().template());
                validateTemplate(template, ORDER_VARIABLES);
                merged.put(entry.getKey(), new OrderNoticeSetting(
                        entry.getValue().enabled() == null || entry.getValue().enabled(), template));
            }
            try {
                settings.setOrderNoticesJson(objectMapper.writeValueAsString(merged));
            } catch (JsonProcessingException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid order update settings");
            }
        }
        if (request.thankYouEnabled() != null) {
            settings.setThankYouEnabled(request.thankYouEnabled());
        }
        if (request.thankYouTemplate() != null) {
            String template = blankToNull(request.thankYouTemplate());
            validateTemplate(template, CUSTOMER_VARIABLES);
            settings.setThankYouTemplate(template);
        }
        if (request.winbackEnabled() != null) {
            settings.setWinbackEnabled(request.winbackEnabled());
        }
        if (request.winbackDays() != null) {
            if (request.winbackDays() < 7 || request.winbackDays() > 365) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Win-back days must be between 7 and 365");
            }
            settings.setWinbackDays(request.winbackDays());
        }
        if (request.winbackTemplate() != null) {
            String template = blankToNull(request.winbackTemplate());
            validateTemplate(template, CUSTOMER_VARIABLES);
            settings.setWinbackTemplate(template);
        }
        if (request.promoWeeklyCap() != null) {
            if (request.promoWeeklyCap() < 1 || request.promoWeeklyCap() > 7) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Weekly limit must be between 1 and 7");
            }
            settings.setPromoWeeklyCap(request.promoWeeklyCap());
        }
        return toResponse(repository.save(settings), optOutFooter);
    }

    private AutomationsResponse toResponse(BusinessSmsHubSettings settings, String optOutFooter) {
        Map<String, OrderNoticeSetting> stored = notices(settings);
        Map<String, OrderNoticeSetting> notices = new LinkedHashMap<>();
        for (String key : ORDER_NOTICES) {
            OrderNoticeSetting setting = stored.get(key);
            notices.put(key, setting == null
                    ? new OrderNoticeSetting(true, null)
                    : new OrderNoticeSetting(setting.enabled() == null || setting.enabled(),
                            blankToNull(setting.template())));
        }
        return new AutomationsResponse(
                notices,
                settings.isThankYouEnabled(),
                settings.getThankYouTemplate(),
                DEFAULT_THANK_YOU,
                settings.isWinbackEnabled(),
                settings.getWinbackDays(),
                settings.getWinbackTemplate(),
                DEFAULT_WINBACK,
                settings.getPromoWeeklyCap(),
                SmsSendWindow.describe(),
                optOutFooter);
    }

    private Map<String, OrderNoticeSetting> notices(BusinessSmsHubSettings settings) {
        String json = settings.getOrderNoticesJson();
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, OrderNoticeSetting> parsed = objectMapper.readValue(json, NOTICE_MAP);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private static void validateTemplate(String template, Set<String> allowed) {
        if (template == null) {
            return;
        }
        if (template.length() > MAX_TEMPLATE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Wording is too long (max " + MAX_TEMPLATE + " characters)");
        }
        List<String> unknown = SmsTemplates.unknownVariables(template, allowed);
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unsupported variables: " + String.join(", ", unknown));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
