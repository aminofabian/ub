package zelisline.ub.payments.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.ProfitPocketSettings;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * Collects customer-pay till / paybill fingerprints so Profit Pocket cannot reuse them.
 */
@Service
@RequiredArgsConstructor
public class CustomerPayEndpointService {

    private final PaymentGatewayConfigRepository configRepository;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public record CustomerPayEndpoints(Set<String> tills, Set<String> paybills) {
        static CustomerPayEndpoints empty() {
            return new CustomerPayEndpoints(Set.of(), Set.of());
        }
    }

    @Transactional(readOnly = true)
    public CustomerPayEndpoints collect(String businessId) {
        List<PaymentGatewayConfig> configs = configRepository.findByBusinessIdAndStatus(
                businessId, GatewayStatus.ACTIVE);
        if (configs.isEmpty()) {
            return CustomerPayEndpoints.empty();
        }
        Set<String> tills = new LinkedHashSet<>();
        Set<String> paybills = new LinkedHashSet<>();
        for (PaymentGatewayConfig cfg : configs) {
            absorbDisplayInstructions(cfg.getDisplayInstructionsJson(), tills, paybills);
            absorbCredentials(cfg.getCredentialsJson(), tills, paybills);
        }
        return new CustomerPayEndpoints(Set.copyOf(tills), Set.copyOf(paybills));
    }

    /**
     * @return human message when pocket destination matches a customer-pay endpoint; else null
     */
    @Transactional(readOnly = true)
    public String collisionMessage(String businessId, ProfitPocketSettings settings) {
        if (settings == null || settings.getDestinationType() == null) {
            return null;
        }
        CustomerPayEndpoints endpoints = collect(businessId);
        String type = settings.getDestinationType();
        if (ProfitPocketSettings.TYPE_TILL.equals(type)) {
            String till = digitsOnly(settings.getDestinationAccount());
            if (till != null && endpoints.tills().contains(till)) {
                return "Till " + till
                        + " is already a customer-pay till. Choose a different expense / owner destination.";
            }
        } else if (ProfitPocketSettings.TYPE_PAYBILL.equals(type)
                || ProfitPocketSettings.TYPE_BANK.equals(type)) {
            String paybill = digitsOnly(settings.getDestinationPaybill());
            String account = ProfitPocketSettings.TYPE_BANK.equals(type)
                    ? normalizeAccount(settings.getDestinationAccount())
                    : normalizeAccount(settings.getDestinationPaybillAccount());
            if (paybill != null && account != null) {
                String key = paybillKey(paybill, account);
                if (endpoints.paybills().contains(key)) {
                    return "Paybill " + paybill + " · " + account
                            + " is already where customers pay. Choose a different expense / owner destination.";
                }
            }
            // Also block when paybill number alone matches a customer till (common Mix).
            if (paybill != null && endpoints.tills().contains(paybill)) {
                return "Paybill " + paybill
                        + " matches a customer-pay till. Choose a different expense / owner destination.";
            }
        }
        return null;
    }

    private void absorbDisplayInstructions(String json, Set<String> tills, Set<String> paybills) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String type = text(root, "type");
            if ("till".equalsIgnoreCase(type)) {
                String till = digitsOnly(text(root, "tillNumber"));
                if (till != null) {
                    tills.add(till);
                }
            } else if ("paybill".equalsIgnoreCase(type)) {
                String paybill = digitsOnly(text(root, "businessNumber"));
                String account = normalizeAccount(text(root, "accountNumber"));
                if (paybill != null && account != null) {
                    paybills.add(paybillKey(paybill, account));
                }
                if (paybill != null) {
                    tills.add(paybill); // treat as reserved number too
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void absorbCredentials(String encrypted, Set<String> tills, Set<String> paybills) {
        if (encrypted == null || encrypted.isBlank()) {
            return;
        }
        try {
            String decrypted = encryptionService.decrypt(encrypted);
            if (decrypted == null || decrypted.isBlank()) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(decrypted, Map.class);
            addDigits(tills, stringVal(map, "tillNumber"));
            addDigits(tills, stringVal(map, "shortcode"));
            String shortcodeType = stringVal(map, "shortcodeType");
            String shortcode = digitsOnly(stringVal(map, "shortcode"));
            if (shortcode != null && "paybill".equalsIgnoreCase(shortcodeType)) {
                String account = normalizeAccount(stringVal(map, "accountNumber"));
                if (account != null) {
                    paybills.add(paybillKey(shortcode, account));
                }
                tills.add(shortcode);
            }
            String webhookTills = stringVal(map, "webhookTillNumbers");
            if (webhookTills != null) {
                for (String part : webhookTills.split("[,;\\s]+")) {
                    addDigits(tills, part);
                }
            }
        } catch (Exception ignored) {
            // best-effort — unreadable credentials skip silently
        }
    }

    private static void addDigits(Set<String> tills, String raw) {
        String d = digitsOnly(raw);
        if (d != null && d.length() >= 5) {
            tills.add(d);
        }
    }

    private static String paybillKey(String paybill, String account) {
        return paybill + "|" + account.toLowerCase(Locale.ROOT);
    }

    private static String digitsOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("\\D+", "");
        return digits.isBlank() ? null : digits;
    }

    private static String normalizeAccount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        return root.get(field).asText(null);
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
