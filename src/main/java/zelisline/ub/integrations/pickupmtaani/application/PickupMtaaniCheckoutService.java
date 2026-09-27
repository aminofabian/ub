package zelisline.ub.integrations.pickupmtaani.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.pickupmtaani.domain.PickupMtaaniQuote;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.storefront.api.dto.PickupMtaaniFulfillmentRequest;
import zelisline.ub.storefront.domain.ShopperCheckoutProfile;
import zelisline.ub.storefront.domain.WebCheckoutSession;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.ShopperCheckoutProfileRepository;
import zelisline.ub.storefront.repository.WebCheckoutSessionRepository;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;

/**
 * Bridges the shopper's Pickup Mtaani choice into checkout (scope §7, §10):
 * validate it against a live quote, persist the selection on the checkout
 * session/profile, then at submit price it and write the {@code pending}
 * shipment row. No upstream parcel is created here.
 */
@Service
@RequiredArgsConstructor
public class PickupMtaaniCheckoutService {

    public static final String CARRIER = "pickup_mtaani";

    public static final String MODE_AGENT = "agent";
    public static final String MODE_DOORSTEP = "doorstep";

    private final ObjectMapper objectMapper;
    private final PickupMtaaniQuoteService quoteService;
    private final PickupMtaaniSettingsService settingsService;
    private final WebOrderShipmentRepository shipmentRepository;
    private final ShopperCheckoutProfileRepository profileRepository;
    private final WebCheckoutSessionRepository sessionRepository;

    /** Persisted fulfilment choice. */
    public record Selection(
            String carrier,
            String mode,
            Long originAgentId,
            Long destinationId,
            String destinationLabel,
            String locationDescription,
            String quoteId
    ) {
    }

    /** Fees resolved from the quote at submit time. */
    public record PricedSelection(BigDecimal quotedFeeKes, BigDecimal shopperFeeKes, String feeMode) {
    }

    /**
     * Validates a storefront fulfilment choice and returns the JSON to store on
     * the checkout session/profile. Throws when the quote is missing, expired, or
     * does not match the chosen destination.
     */
    @Transactional(readOnly = true)
    public String validateSelection(String businessId, PickupMtaaniFulfillmentRequest request) {
        String carrier = blankToNull(request.carrier());
        if (carrier == null || !CARRIER.equalsIgnoreCase(carrier)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Only Pickup Mtaani delivery is supported here.");
        }
        String mode = normalizeMode(request.mode());
        if (mode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be agent or doorstep.");
        }
        PickupMtaaniQuote quote =
                quoteService.requireActiveQuote(businessId, request.quoteId(), mode, request.destinationId());
        PickupMtaaniSettingsService.PickupMtaaniResolved config = settingsService.resolve(businessId);
        if (!config.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Pickup Mtaani is not available for this shop right now.");
        }
        if (quote.getOriginAgentId() != null && !quote.getOriginAgentId().equals(config.originAgentId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The shop's pickup point changed. Choose delivery again.");
        }
        String label = firstNonBlank(blankToNull(request.destinationLabel()), quote.getDestinationLabel());
        Selection selection = new Selection(
                CARRIER,
                mode,
                config.originAgentId(),
                request.destinationId(),
                label,
                blankToNull(request.locationDescription()),
                request.quoteId()
        );
        return write(selection);
    }

    public Optional<Selection> parseSelection(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, Selection.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * The delivery choice saved on this cart's checkout session or, when signed
     * in, the shopper's profile.
     */
    @Transactional(readOnly = true)
    public Optional<Selection> selectionFor(String businessId, String cartId, HttpServletRequest request) {
        String json = CurrentTenantUser.optionalHuman(request)
                .map(principal -> profileRepository
                        .findByBusinessIdAndUserId(businessId, principal.userId())
                        .map(ShopperCheckoutProfile::getPickupMtaani)
                        .orElse(null))
                .orElseGet(() -> sessionRepository
                        .findByBusinessIdAndCartId(businessId, cartId)
                        .map(WebCheckoutSession::getPickupMtaani)
                        .orElse(null));
        return parseSelection(json);
    }

    /** Re-validates the quote and returns the fees. */
    @Transactional(readOnly = true)
    public PricedSelection price(String businessId, Selection selection) {
        PickupMtaaniQuote quote = quoteService.requireActiveQuote(
                businessId, selection.quoteId(), selection.mode(), selection.destinationId());
        return new PricedSelection(
                quote.getUpstreamFeeKes(), quote.getShopperFeeKes(), quote.getFeeMode());
    }

    /** Writes the pending shipment row for a freshly created order. */
    @Transactional
    public void createPendingShipment(
            String businessId,
            String webOrderId,
            Selection selection,
            PricedSelection priced,
            Integer packageValueKes
    ) {
        WebOrderShipment shipment = new WebOrderShipment();
        shipment.setId(UUID.randomUUID().toString());
        shipment.setBusinessId(businessId);
        shipment.setWebOrderId(webOrderId);
        shipment.setCarrier(CARRIER);
        shipment.setMode(selection.mode());
        shipment.setOriginAgentId(selection.originAgentId());
        if (MODE_AGENT.equals(selection.mode())) {
            shipment.setDestinationAgentId(selection.destinationId());
        } else {
            shipment.setDoorstepDestinationId(selection.destinationId());
        }
        shipment.setDestinationLabel(selection.destinationLabel());
        shipment.setLocationDescription(selection.locationDescription());
        shipment.setQuotedFeeKes(priced.quotedFeeKes());
        shipment.setShopperFeeKes(priced.shopperFeeKes());
        shipment.setFeeMode(priced.feeMode());
        shipment.setPackageValueKes(packageValueKes);
        shipment.setBookStatus(WebOrderShipment.BOOK_PENDING);
        shipmentRepository.save(shipment);
    }

    /** Whole-KES goods value sent upstream as {@code packageValue}. */
    public static Integer toWholeKes(BigDecimal goodsTotal) {
        if (goodsTotal == null) {
            return null;
        }
        return goodsTotal.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private String write(Selection selection) {
        try {
            return objectMapper.writeValueAsString(selection);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the delivery choice.");
        }
    }

    private static String normalizeMode(String raw) {
        if (raw == null) {
            return null;
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        return (MODE_AGENT.equals(mode) || MODE_DOORSTEP.equals(mode)) ? mode : null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}
