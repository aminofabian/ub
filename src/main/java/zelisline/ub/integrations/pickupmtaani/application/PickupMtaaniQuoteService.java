package zelisline.ub.integrations.pickupmtaani.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniQuoteRequest;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniQuoteResponse;
import zelisline.ub.integrations.pickupmtaani.domain.PickupMtaaniQuote;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.repository.PickupMtaaniQuoteRepository;

/**
 * Prices a Pickup Mtaani destination for storefront checkout (scope §7, §13).
 * A quote is stored for 30 minutes and carries the fee the shopper saw, so a
 * later checkout cannot submit a cheaper amount.
 */
@Service
@RequiredArgsConstructor
public class PickupMtaaniQuoteService {

    public static final String MODE_AGENT = "agent";
    public static final String MODE_DOORSTEP = "doorstep";

    private static final Duration QUOTE_TTL = Duration.ofMinutes(30);
    private static final int MONEY_SCALE = 2;

    private final PickupMtaaniSettingsService settingsService;
    private final PickupMtaaniClient pickupMtaaniClient;
    private final PickupMtaaniQuoteRepository quoteRepository;

    @Transactional
    public PickupMtaaniQuoteResponse createQuote(String businessId, PickupMtaaniQuoteRequest request) {
        PickupMtaaniSettingsService.PickupMtaaniResolved config = settingsService.resolve(businessId);
        if (!config.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Pickup Mtaani is not available for this shop right now.");
        }
        String mode = normalizeMode(request.mode());
        if (mode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be agent or doorstep.");
        }
        if (MODE_AGENT.equals(mode) && !config.agent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent pickup is not offered.");
        }
        if (MODE_DOORSTEP.equals(mode) && !config.doorstep()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Doorstep delivery is not offered.");
        }

        Long destinationId = request.destinationId();
        BigDecimal upstream = MODE_AGENT.equals(mode)
                ? pickupMtaaniClient
                        .getAgentDeliveryCharge(config.apiKey(), config.originAgentId(), destinationId)
                        .amountKes()
                : pickupMtaaniClient
                        .getDoorstepDeliveryCharge(config.apiKey(), config.originAgentId(), destinationId)
                        .amountKes();
        BigDecimal shopperFee = applyFeeMode(upstream, config.feeMode(), config.markupKes());

        Instant now = Instant.now();
        PickupMtaaniQuote quote = new PickupMtaaniQuote();
        quote.setId(UUID.randomUUID().toString());
        quote.setBusinessId(businessId);
        quote.setMode(mode);
        quote.setOriginAgentId(config.originAgentId());
        quote.setDestinationId(destinationId);
        quote.setDestinationLabel(trimToNull(request.destinationLabel()));
        quote.setUpstreamFeeKes(upstream.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        quote.setShopperFeeKes(shopperFee);
        quote.setFeeMode(config.feeMode());
        quote.setExpiresAt(now.plus(QUOTE_TTL));
        quote.setCreatedAt(now);
        quoteRepository.save(quote);

        return new PickupMtaaniQuoteResponse(
                quote.getId(),
                mode,
                destinationId,
                quote.getDestinationLabel(),
                shopperFee,
                quote.getExpiresAt()
        );
    }

    /**
     * Loads a quote for the tenant and checks it still matches the chosen
     * destination. Expired or mismatched quotes raise rather than silently
     * repricing (scope §10).
     */
    @Transactional(readOnly = true)
    public PickupMtaaniQuote requireActiveQuote(
            String businessId, String quoteId, String mode, Long destinationId) {
        PickupMtaaniQuote quote = quoteRepository.findByIdAndBusinessId(quoteId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Delivery quote not found. Choose delivery again."));
        if (quote.getExpiresAt() == null || quote.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Your delivery quote expired. Choose delivery again.");
        }
        if (!quote.getMode().equals(mode) || !quote.getDestinationId().equals(destinationId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Delivery quote does not match the chosen destination.");
        }
        return quote;
    }

    static BigDecimal applyFeeMode(BigDecimal upstream, String feeMode, int markupKes) {
        BigDecimal base = upstream == null ? BigDecimal.ZERO : upstream;
        BigDecimal result = switch (feeMode == null ? "" : feeMode) {
            case PickupMtaaniSettingsJson.FEE_MODE_ABSORB -> BigDecimal.ZERO;
            case PickupMtaaniSettingsJson.FEE_MODE_MARKUP ->
                    base.add(BigDecimal.valueOf(Math.max(0, markupKes)));
            default -> base;
        };
        return result.max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalizeMode(String raw) {
        if (raw == null) {
            return null;
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        return (MODE_AGENT.equals(mode) || MODE_DOORSTEP.equals(mode)) ? mode : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
