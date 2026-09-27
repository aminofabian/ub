package zelisline.ub.integrations.pickupmtaani.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsService.PickupMtaaniResolved;
import zelisline.ub.integrations.pickupmtaani.domain.PickupMtaaniQuote;
import zelisline.ub.storefront.api.dto.PickupMtaaniFulfillmentRequest;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.ShopperCheckoutProfileRepository;
import zelisline.ub.storefront.repository.WebCheckoutSessionRepository;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;

class PickupMtaaniCheckoutServiceTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String QUOTE_ID = "quote-1";

    private final PickupMtaaniQuoteService quoteService = mock(PickupMtaaniQuoteService.class);
    private final PickupMtaaniSettingsService settingsService = mock(PickupMtaaniSettingsService.class);
    private final WebOrderShipmentRepository shipmentRepository = mock(WebOrderShipmentRepository.class);
    private final ShopperCheckoutProfileRepository profileRepository = mock(ShopperCheckoutProfileRepository.class);
    private final WebCheckoutSessionRepository sessionRepository = mock(WebCheckoutSessionRepository.class);

    private final PickupMtaaniCheckoutService service = new PickupMtaaniCheckoutService(
            new ObjectMapper(), quoteService, settingsService, shipmentRepository,
            profileRepository, sessionRepository);

    @BeforeEach
    void setUp() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(new PickupMtaaniResolved(
                "key", true, "single_business", 362L, "Kahawa West", "Kahawa",
                true, true, "pass_through", 0, true));
        when(shipmentRepository.save(any(WebOrderShipment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PickupMtaaniQuote quote(long origin, String mode, long destination) {
        PickupMtaaniQuote q = new PickupMtaaniQuote();
        q.setId(QUOTE_ID);
        q.setBusinessId(BUSINESS_ID);
        q.setMode(mode);
        q.setOriginAgentId(origin);
        q.setDestinationId(destination);
        q.setDestinationLabel("Westlands Agent");
        q.setUpstreamFeeKes(new BigDecimal("150.00"));
        q.setShopperFeeKes(new BigDecimal("150.00"));
        q.setFeeMode("pass_through");
        q.setExpiresAt(Instant.now().plusSeconds(600));
        q.setCreatedAt(Instant.now());
        return q;
    }

    @Test
    void validate_rejectsUnknownCarrier() {
        assertThatThrownBy(() -> service.validateSelection(BUSINESS_ID, new PickupMtaaniFulfillmentRequest(
                "uber", "agent", 454L, null, null, QUOTE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only Pickup Mtaani");
    }

    @Test
    void validate_rejectsBadMode() {
        assertThatThrownBy(() -> service.validateSelection(BUSINESS_ID, new PickupMtaaniFulfillmentRequest(
                "pickup_mtaani", "express", 454L, null, null, QUOTE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("agent or doorstep");
    }

    @Test
    void validate_rejectsWhenNotReady() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(PickupMtaaniResolved.disabled());
        when(quoteService.requireActiveQuote(BUSINESS_ID, QUOTE_ID, "agent", 454L))
                .thenReturn(quote(362L, "agent", 454L));

        assertThatThrownBy(() -> service.validateSelection(BUSINESS_ID, new PickupMtaaniFulfillmentRequest(
                "pickup_mtaani", "agent", 454L, null, null, QUOTE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void validate_rejectsWhenOriginChanged() {
        when(quoteService.requireActiveQuote(BUSINESS_ID, QUOTE_ID, "agent", 454L))
                .thenReturn(quote(999L, "agent", 454L));

        assertThatThrownBy(() -> service.validateSelection(BUSINESS_ID, new PickupMtaaniFulfillmentRequest(
                "pickup_mtaani", "agent", 454L, null, null, QUOTE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("pickup point changed");
    }

    @Test
    void validate_andParse_roundTrips() {
        when(quoteService.requireActiveQuote(BUSINESS_ID, QUOTE_ID, "agent", 454L))
                .thenReturn(quote(362L, "agent", 454L));

        String json = service.validateSelection(BUSINESS_ID, new PickupMtaaniFulfillmentRequest(
                "pickup_mtaani", "agent", 454L, null, null, QUOTE_ID));

        var selection = service.parseSelection(json).orElseThrow();
        assertThat(selection.carrier()).isEqualTo("pickup_mtaani");
        assertThat(selection.mode()).isEqualTo("agent");
        assertThat(selection.originAgentId()).isEqualTo(362L);
        assertThat(selection.destinationId()).isEqualTo(454L);
        assertThat(selection.destinationLabel()).isEqualTo("Westlands Agent");
        assertThat(selection.quoteId()).isEqualTo(QUOTE_ID);
    }

    @Test
    void createPendingShipment_agentMode_fillsAgentColumn() {
        when(quoteService.requireActiveQuote(BUSINESS_ID, QUOTE_ID, "agent", 454L))
                .thenReturn(quote(362L, "agent", 454L));
        var selection = service.parseSelection(service.validateSelection(BUSINESS_ID,
                new PickupMtaaniFulfillmentRequest("pickup_mtaani", "agent", 454L, "Westlands Agent", null, QUOTE_ID)))
                .orElseThrow();
        var priced = service.price(BUSINESS_ID, selection);

        service.createPendingShipment(BUSINESS_ID, "order-1", selection, priced, 1850);

        var captor = org.mockito.ArgumentCaptor.forClass(WebOrderShipment.class);
        org.mockito.Mockito.verify(shipmentRepository).save(captor.capture());
        WebOrderShipment s = captor.getValue();
        assertThat(s.getWebOrderId()).isEqualTo("order-1");
        assertThat(s.getCarrier()).isEqualTo("pickup_mtaani");
        assertThat(s.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_PENDING);
        assertThat(s.getDestinationAgentId()).isEqualTo(454L);
        assertThat(s.getDoorstepDestinationId()).isNull();
        assertThat(s.getShopperFeeKes()).isEqualByComparingTo("150.00");
        assertThat(s.getPackageValueKes()).isEqualTo(1850);
    }

    @Test
    void createPendingShipment_doorstepMode_fillsDoorstepColumn() {
        when(quoteService.requireActiveQuote(BUSINESS_ID, QUOTE_ID, "doorstep", 900L))
                .thenReturn(quote(362L, "doorstep", 900L));
        var selection = service.parseSelection(service.validateSelection(BUSINESS_ID,
                new PickupMtaaniFulfillmentRequest("pickup_mtaani", "doorstep", 900L, null, "Blue gate", QUOTE_ID)))
                .orElseThrow();
        var priced = service.price(BUSINESS_ID, selection);

        service.createPendingShipment(BUSINESS_ID, "order-2", selection, priced, 500);

        var captor = org.mockito.ArgumentCaptor.forClass(WebOrderShipment.class);
        org.mockito.Mockito.verify(shipmentRepository).save(captor.capture());
        WebOrderShipment s = captor.getValue();
        assertThat(s.getDoorstepDestinationId()).isEqualTo(900L);
        assertThat(s.getDestinationAgentId()).isNull();
        assertThat(s.getLocationDescription()).isEqualTo("Blue gate");
    }

    @Test
    void toWholeKes_roundsHalfUp() {
        assertThat(PickupMtaaniCheckoutService.toWholeKes(new BigDecimal("1849.50"))).isEqualTo(1850);
        assertThat(PickupMtaaniCheckoutService.toWholeKes(new BigDecimal("1849.49"))).isEqualTo(1849);
        assertThat(PickupMtaaniCheckoutService.toWholeKes(null)).isNull();
    }
}
