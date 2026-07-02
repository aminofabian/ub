package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.sales.api.dto.VariableWeightBarcodeLookupResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class VariableWeightBarcodeService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;

    @Transactional(readOnly = true)
    public VariableWeightBarcodeLookupResponse lookupForPos(
            String businessId,
            String branchId,
            String rawBarcode
    ) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        VariableWeightBarcodeConfig config = readConfig(business);
        VariableWeightBarcodeParseResult parsed = VariableWeightBarcodeParser.parse(rawBarcode, config)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a variable-weight barcode"));

        Item item = itemRepository
                .findByBusinessIdAndPluCodeAndDeletedAtIsNull(businessId, parsed.pluCode())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Unknown PLU " + parsed.pluCode() + " — assign a PLU code to the weighed item"));

        if (!item.isWeighed()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "PLU " + parsed.pluCode() + " is not a weighed item");
        }
        if (!item.isSellable() || !item.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not sellable");
        }

        BigDecimal shelfPrice = pricingService.getCurrentOpenSellingPrice(businessId, item.getId(), branchId);
        if (shelfPrice == null || shelfPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Set a shelf price for " + item.getName().trim());
        }

        return buildResponse(item, parsed, shelfPrice);
    }

    @Transactional(readOnly = true)
    public VariableWeightBarcodeLookupResponse lookupForPublic(String rawBarcode) {
        VariableWeightBarcodeConfig config = VariableWeightBarcodeConfig.standardEnabled();
        VariableWeightBarcodeParseResult parsed = VariableWeightBarcodeParser.parse(rawBarcode, config)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        Item item = itemRepository.findFirstPublishedByPluCode(parsed.pluCode())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Unknown PLU " + parsed.pluCode()));

        if (!item.isWeighed()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "PLU " + parsed.pluCode() + " is not a weighed item");
        }

        BigDecimal shelfPrice = item.getBundlePrice();
        if (shelfPrice == null || shelfPrice.compareTo(BigDecimal.ZERO) <= 0) {
            shelfPrice = null;
        }

        if (parsed.embeddedField() == VariableWeightBarcodeConfig.EmbeddedField.PRICE) {
            if (parsed.embeddedPrice() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read embedded price");
            }
            if (shelfPrice == null) {
                return new VariableWeightBarcodeLookupResponse(
                        item.getId(),
                        item.getName(),
                        parsed.pluCode(),
                        null,
                        null,
                        parsed.embeddedPrice(),
                        "price"
                );
            }
        } else if (shelfPrice == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }

        return buildResponse(item, parsed, shelfPrice);
    }

    private static VariableWeightBarcodeLookupResponse buildResponse(
            Item item,
            VariableWeightBarcodeParseResult parsed,
            BigDecimal shelfPrice
    ) {
        BigDecimal quantity;
        BigDecimal lineTotal;
        if (parsed.embeddedField() == VariableWeightBarcodeConfig.EmbeddedField.WEIGHT) {
            if (parsed.embeddedWeightKg() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read embedded weight");
            }
            quantity = parsed.embeddedWeightKg().setScale(4, RoundingMode.HALF_UP);
            lineTotal = quantity.multiply(shelfPrice).setScale(2, RoundingMode.HALF_UP);
            return new VariableWeightBarcodeLookupResponse(
                    item.getId(),
                    item.getName(),
                    parsed.pluCode(),
                    quantity,
                    shelfPrice.setScale(2, RoundingMode.HALF_UP),
                    lineTotal,
                    "weight"
            );
        }

        BigDecimal embeddedPrice = parsed.embeddedPrice();
        if (embeddedPrice == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read embedded price");
        }
        quantity = embeddedPrice.divide(shelfPrice, 4, RoundingMode.HALF_UP);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid embedded price for shelf rate");
        }
        lineTotal = embeddedPrice.setScale(2, RoundingMode.HALF_UP);
        return new VariableWeightBarcodeLookupResponse(
                item.getId(),
                item.getName(),
                parsed.pluCode(),
                quantity,
                shelfPrice.setScale(2, RoundingMode.HALF_UP),
                lineTotal,
                "price"
        );
    }

    static VariableWeightBarcodeConfig readConfig(Business business) {
        String raw = business.getSettings();
        if (raw == null || raw.isBlank()) {
            return VariableWeightBarcodeConfig.disabled();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            return VariableWeightBarcodeConfig.fromBusinessSettings(root.get("butcher"));
        } catch (Exception ex) {
            return VariableWeightBarcodeConfig.disabled();
        }
    }

    public static String normalizePluCode(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = VariableWeightBarcodeParser.digitsOnly(raw);
        return digits.isEmpty() ? null : digits;
    }
}
