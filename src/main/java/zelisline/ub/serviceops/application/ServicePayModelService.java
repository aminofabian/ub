package zelisline.ub.serviceops.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * What a shop's service commission is worked out on (ADR 0019): the whole price the customer
 * paid, or the price after the service's fixed product cost. One choice per business, kept in
 * the business settings under {@code servicePay.model}.
 */
@Service
@RequiredArgsConstructor
public class ServicePayModelService {

    /** Commission on every shilling the customer pays for the service. The default. */
    public static final String GROSS = "GROSS";
    /** The service's product cost comes off first; commission is on what is left. */
    public static final String NET_OF_PRODUCT = "NET_OF_PRODUCT";

    private static final String KEY = "servicePay";
    private static final String FIELD = "model";

    private final BusinessRepository businessRepository;
    private final ObjectMapper objectMapper;

    /** The shop's model; {@link #GROSS} when none was chosen or the settings cannot be read. */
    @Transactional(readOnly = true)
    public String model(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(business -> read(business.getSettings()))
                .orElse(GROSS);
    }

    public boolean deductsProductCost(String businessId) {
        return NET_OF_PRODUCT.equals(model(businessId));
    }

    /** Applies to services sold from now on; what was already earned stays as it is. */
    @Transactional
    public String setModel(String businessId, String model) {
        if (!GROSS.equals(model) && !NET_OF_PRODUCT.equals(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose GROSS or NET_OF_PRODUCT");
        }
        // Locked: the settings are one document that other screens also rewrite.
        Business business = businessRepository.findByIdAndDeletedAtIsNullForUpdate(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        try {
            String current = business.getSettings();
            JsonNode parsed = document(current);
            ObjectNode root = parsed instanceof ObjectNode node ? node : objectMapper.createObjectNode();
            ObjectNode pay = root.get(KEY) instanceof ObjectNode node ? node : objectMapper.createObjectNode();
            pay.put(FIELD, model);
            root.set(KEY, pay);
            business.setSettings(objectMapper.writeValueAsString(root));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The shop's settings could not be read");
        }
        businessRepository.save(business);
        return model;
    }

    /** The settings as a document. Some databases hand the JSON column back as a quoted string. */
    private JsonNode document(String settings) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(settings == null || settings.isBlank() ? "{}" : settings);
        return node.isTextual() ? objectMapper.readTree(node.asText()) : node;
    }

    private String read(String settings) {
        if (settings == null || settings.isBlank()) {
            return GROSS;
        }
        try {
            String stored = document(settings).path(KEY).path(FIELD).asText("");
            return NET_OF_PRODUCT.equals(stored) ? NET_OF_PRODUCT : GROSS;
        } catch (JsonProcessingException ex) {
            return GROSS;
        }
    }
}
