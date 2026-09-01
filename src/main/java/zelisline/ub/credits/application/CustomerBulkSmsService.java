package zelisline.ub.credits.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.CustomerBulkMessageRequest;
import zelisline.ub.credits.api.dto.CustomerBulkMessageResponse;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class CustomerBulkSmsService {

    private static final int MAX_RECIPIENTS = 500;

    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final BusinessRepository businessRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    @Transactional
    public CustomerBulkMessageResponse bulkSend(String businessId, CustomerBulkMessageRequest request) {
        List<String> ids = dedupeIds(request.customerIds());
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No customers selected");
        }
        if (ids.size() > MAX_RECIPIENTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Too many recipients (max " + MAX_RECIPIENTS + ")");
        }
        String template = request.body().trim();
        if (template.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message body is required");
        }

        TenantMessagingConfig messaging = messagingSettingsService.resolveForDispatch(
                businessId, SmsSendReason.GENERAL);
        if (!messaging.secretsReadable()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    messaging.secretsReadError() != null
                            ? messaging.secretsReadError()
                            : "Messaging credentials are not readable");
        }
        if (!messaging.smsConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Configure SMS under Credit customers → Messaging");
        }

        Business business = businessRepository.findById(businessId).orElse(null);
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "our shop";

        int sent = 0;
        int skipped = 0;
        List<CustomerBulkMessageResponse.CustomerBulkMessageFailure> failures = new ArrayList<>();

        for (String customerId : ids) {
            Customer customer = customerRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                    .orElse(null);
            if (customer == null || customer.getAnonymisedAt() != null) {
                skipped++;
                failures.add(new CustomerBulkMessageResponse.CustomerBulkMessageFailure(
                        customerId, displayName(customer), "Customer not found"));
                continue;
            }

            String phoneDigits = resolvePrimaryPhoneDigits(customerId);
            if (phoneDigits == null) {
                skipped++;
                failures.add(new CustomerBulkMessageResponse.CustomerBulkMessageFailure(
                        customerId, displayName(customer), "No usable phone number"));
                continue;
            }

            String rendered = render(template, customer.getName(), shopName);
            try {
                CustomerMessageDispatcher.DeliveryResult delivery =
                        customerMessageDispatcher.deliverSmsOnly(messaging, phoneDigits, rendered);
                if ("sent".equals(delivery.outcome()) || "stub".equals(delivery.outcome())) {
                    sent++;
                } else {
                    skipped++;
                    failures.add(new CustomerBulkMessageResponse.CustomerBulkMessageFailure(
                            customerId,
                            displayName(customer),
                            delivery.detail() != null ? delivery.detail() : "Send failed"));
                }
            } catch (zelisline.ub.messaging.application.SmsCreditsDepletedException ex) {
                skipped++;
                failures.add(new CustomerBulkMessageResponse.CustomerBulkMessageFailure(
                        customerId, displayName(customer), ex.getMessage()));
                break;
            }
        }

        return new CustomerBulkMessageResponse(sent, skipped, failures);
    }

    private static List<String> dedupeIds(List<String> raw) {
        Set<String> seen = new LinkedHashSet<>();
        for (String id : raw) {
            if (id == null) {
                continue;
            }
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                seen.add(trimmed);
            }
        }
        return List.copyOf(seen);
    }

    private String resolvePrimaryPhoneDigits(String customerId) {
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (phones.isEmpty()) {
            return null;
        }
        CustomerPhone pick = phones.stream().filter(CustomerPhone::isPrimary).findFirst().orElse(phones.getFirst());
        if (pick.getPhone() == null || pick.getPhone().isBlank()) {
            return null;
        }
        if (pick.getMaskedMsisdn() != null && !pick.getMaskedMsisdn().isBlank()
                && (pick.getPhone().contains("x") || pick.getPhone().contains("X"))) {
            return null;
        }
        return StkPhoneNormalizer.normalize(pick.getPhone());
    }

    private static String render(String template, String customerName, String shopName) {
        String name = customerName == null || customerName.isBlank() ? "there" : customerName.trim();
        return template
                .replace("{name}", name)
                .replace("{shop}", shopName)
                .replace("{Name}", capitalize(name))
                .replace("{Shop}", shopName);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static String displayName(Customer customer) {
        if (customer == null || customer.getName() == null || customer.getName().isBlank()) {
            return "Customer";
        }
        return customer.getName().trim();
    }
}
