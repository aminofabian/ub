package zelisline.ub.integrations.pickupmtaani.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniQuoteRequest;
import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsService.PickupMtaaniResolved;
import zelisline.ub.integrations.pickupmtaani.domain.PickupMtaaniQuote;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.DeliveryCharge;
import zelisline.ub.integrations.pickupmtaani.repository.PickupMtaaniQuoteRepository;

class PickupMtaaniQuoteServiceTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String KEY = "pm-key";
    private static final long ORIGIN = 362L;

    private final PickupMtaaniSettingsService settingsService = mock(PickupMtaaniSettingsService.class);
    private final PickupMtaaniClient client = mock(PickupMtaaniClient.class);
    private final PickupMtaaniQuoteRepository quoteRepository = mock(PickupMtaaniQuoteRepository.class);

    private final PickupMtaaniQuoteService service =
            new PickupMtaaniQuoteService(settingsService, client, quoteRepository);

    @BeforeEach
    void setUp() {
        when(quoteRepository.save(any(PickupMtaaniQuote.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PickupMtaaniResolved ready(String feeMode, int markup) {
        return new PickupMtaaniResolved(
                KEY, true, "single_business", ORIGIN, "Kahawa West", "Kahawa",
                true, true, feeMode, markup, true);
    }

    @Test
    void createQuote_notReady_conflict() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(PickupMtaaniResolved.disabled());

        assertThatThrownBy(() -> service.createQuote(BUSINESS_ID, new PickupMtaaniQuoteRequest("agent", 1L, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not available");
        verify(client, never()).getAgentDeliveryCharge(any(), anyLong(), anyLong());
    }

    @Test
    void createQuote_invalidMode_badRequest() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(ready("pass_through", 0));

        assertThatThrownBy(() -> service.createQuote(BUSINESS_ID, new PickupMtaaniQuoteRequest("express", 1L, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("agent or doorstep");
    }

    @Test
    void createQuote_agentNotOffered_badRequest() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(new PickupMtaaniResolved(
                KEY, true, "single_business", ORIGIN, null, null,
                false, true, "pass_through", 0, true));

        assertThatThrownBy(() -> service.createQuote(BUSINESS_ID, new PickupMtaaniQuoteRequest("agent", 1L, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not offered");
    }

    @Test
    void createQuote_agentPassThrough_shopperPaysUpstream() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(ready("pass_through", 0));
        when(client.getAgentDeliveryCharge(KEY, ORIGIN, 454L))
                .thenReturn(new DeliveryCharge(new BigDecimal("150.00"), "{}"));

        var response = service.createQuote(BUSINESS_ID,
                new PickupMtaaniQuoteRequest("agent", 454L, "Westlands Agent", null));

        assertThat(response.quoteId()).isNotBlank();
        assertThat(response.amountKes()).isEqualByComparingTo("150.00");
        assertThat(response.mode()).isEqualTo("agent");
        assertThat(response.destinationLabel()).isEqualTo("Westlands Agent");
        verify(quoteRepository).save(any(PickupMtaaniQuote.class));
    }

    @Test
    void createQuote_markup_addsFixedAmount() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(ready("markup", 30));
        when(client.getAgentDeliveryCharge(KEY, ORIGIN, 454L))
                .thenReturn(new DeliveryCharge(new BigDecimal("150.00"), "{}"));

        var response = service.createQuote(BUSINESS_ID,
                new PickupMtaaniQuoteRequest("agent", 454L, null, null));

        assertThat(response.amountKes()).isEqualByComparingTo("180.00");
    }

    @Test
    void createQuote_absorb_shopperFree() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(ready("absorb", 0));
        when(client.getAgentDeliveryCharge(KEY, ORIGIN, 454L))
                .thenReturn(new DeliveryCharge(new BigDecimal("150.00"), "{}"));

        var response = service.createQuote(BUSINESS_ID,
                new PickupMtaaniQuoteRequest("agent", 454L, null, null));

        assertThat(response.amountKes()).isEqualByComparingTo("0.00");
    }

    @Test
    void createQuote_doorstep_usesDoorstepCharge() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(ready("pass_through", 0));
        when(client.getDoorstepDeliveryCharge(KEY, ORIGIN, 900L))
                .thenReturn(new DeliveryCharge(new BigDecimal("220.50"), "{}"));

        var response = service.createQuote(BUSINESS_ID,
                new PickupMtaaniQuoteRequest("doorstep", 900L, null, "Blue gate near the school"));

        assertThat(response.amountKes()).isEqualByComparingTo("220.50");
        assertThat(response.mode()).isEqualTo("doorstep");
        verify(client).getDoorstepDeliveryCharge(KEY, ORIGIN, 900L);
    }

    @Test
    void applyFeeMode_scalesAndClamps() {
        assertThat(PickupMtaaniQuoteService.applyFeeMode(new BigDecimal("99.999"), "pass_through", 0))
                .isEqualByComparingTo("100.00");
        assertThat(PickupMtaaniQuoteService.applyFeeMode(new BigDecimal("50"), "markup", -5))
                .isEqualByComparingTo("50.00");
        assertThat(PickupMtaaniQuoteService.applyFeeMode(null, "absorb", 0))
                .isEqualByComparingTo("0.00");
    }
}
