package zelisline.ub.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.ai.api.dto.AiLogoQuotaResponse;
import zelisline.ub.ai.domain.BusinessAiLogoUsage;
import zelisline.ub.ai.repository.BusinessAiLogoUsageRepository;
import zelisline.ub.messaging.api.dto.SmsCreditBalanceResponse;
import zelisline.ub.messaging.application.SmsCreditService;
import zelisline.ub.messaging.application.SmsCreditSettingsService;
import zelisline.ub.messaging.application.SmsCreditsDepletedException;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.domain.SmsSendReason;

@ExtendWith(MockitoExtension.class)
class AiLogoCreditServiceTest {

    private static final String BIZ = "biz-1";

    @Mock
    private BusinessAiLogoUsageRepository usageRepository;
    @Mock
    private SmsCreditService smsCreditService;
    @Mock
    private SmsCreditSettingsService settingsService;

    private AiLogoCreditService service;
    private PlatformSmsCreditSettings settings;

    @BeforeEach
    void setUp() {
        service = new AiLogoCreditService(usageRepository, smsCreditService, settingsService);
        settings = new PlatformSmsCreditSettings();
        settings.setId(PlatformSmsCreditSettings.SINGLETON_ID);
        settings.setAiLogoFreeAllowance(1);
        settings.setAiLogoCreditCost(50);
        settings.setUnitPriceKes(new BigDecimal("1.00"));
        settings.setMinPurchaseCredits(10);
        settings.setMaxPurchaseCredits(500);
        lenient().when(settingsService.loadSingleton()).thenReturn(settings);
    }

    @Test
    void quotaShowsFreeFirstKit() {
        when(usageRepository.findByBusinessId(BIZ)).thenReturn(Optional.empty());
        when(smsCreditService.getBalanceView(BIZ)).thenReturn(balance(0));

        AiLogoQuotaResponse quota = service.quota(BIZ);

        assertThat(quota.freeRemaining()).isEqualTo(1);
        assertThat(quota.nextGenerationIsFree()).isTrue();
        assertThat(quota.canGenerate()).isTrue();
        assertThat(quota.creditCost()).isEqualTo(50);
    }

    @Test
    void reserveConsumesFreeSlot() {
        BusinessAiLogoUsage usage = usage(0, 0);
        when(usageRepository.findForUpdate(BIZ)).thenReturn(Optional.of(usage));

        AiLogoCreditService.Reservation reservation = service.reserve(BIZ);

        assertThat(reservation.free()).isTrue();
        assertThat(usage.getFreeUsed()).isEqualTo(1);
        verify(usageRepository).save(usage);
        verify(smsCreditService, never()).requirePurchasedAvailable(any(), any(Integer.class));
    }

    @Test
    void reserveRequiresCreditsAfterFreeUsed() {
        BusinessAiLogoUsage usage = usage(1, 0);
        when(usageRepository.findForUpdate(BIZ)).thenReturn(Optional.of(usage));

        AiLogoCreditService.Reservation reservation = service.reserve(BIZ);

        assertThat(reservation.free()).isFalse();
        verify(smsCreditService).requirePurchasedAvailable(BIZ, 50);
    }

    @Test
    void reserveThrowsWhenCreditsShort() {
        BusinessAiLogoUsage usage = usage(1, 0);
        when(usageRepository.findForUpdate(BIZ)).thenReturn(Optional.of(usage));
        doThrow(new SmsCreditsDepletedException(
                        "short", 0, 0, 10, new BigDecimal("1.00")))
                .when(smsCreditService)
                .requirePurchasedAvailable(BIZ, 50);

        assertThatThrownBy(() -> service.reserve(BIZ))
                .isInstanceOf(SmsCreditsDepletedException.class);
    }

    @Test
    void releaseFreeRollsBackReservation() {
        BusinessAiLogoUsage usage = usage(1, 0);
        when(usageRepository.findForUpdate(BIZ)).thenReturn(Optional.of(usage));

        service.releaseFree(BIZ);

        assertThat(usage.getFreeUsed()).isEqualTo(0);
        verify(usageRepository).save(usage);
    }

    @Test
    void commitPaidDebitsAndIncrementsPaidCount() {
        BusinessAiLogoUsage usage = usage(1, 0);
        when(usageRepository.findForUpdate(BIZ)).thenReturn(Optional.of(usage));

        service.commitPaid(BIZ, "req-1");

        verify(smsCreditService).debitPurchased(eq(BIZ), eq(50), eq(SmsSendReason.AI_LOGO), eq("req-1"));
        ArgumentCaptor<BusinessAiLogoUsage> captor = ArgumentCaptor.forClass(BusinessAiLogoUsage.class);
        verify(usageRepository).save(captor.capture());
        assertThat(captor.getValue().getPaidCount()).isEqualTo(1);
    }

    @Test
    void quotaBlocksWhenFreeUsedAndNoPurchasedCredits() {
        when(usageRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(usage(1, 0)));
        when(smsCreditService.getBalanceView(BIZ)).thenReturn(balance(20));

        AiLogoQuotaResponse quota = service.quota(BIZ);

        assertThat(quota.nextGenerationIsFree()).isFalse();
        assertThat(quota.canGenerate()).isFalse();
        assertThat(quota.purchasedCredits()).isEqualTo(20);
    }

    private static BusinessAiLogoUsage usage(int freeUsed, int paidCount) {
        BusinessAiLogoUsage usage = new BusinessAiLogoUsage();
        usage.setBusinessId(BIZ);
        usage.setFreeUsed(freeUsed);
        usage.setPaidCount(paidCount);
        return usage;
    }

    private static SmsCreditBalanceResponse balance(int purchased) {
        return new SmsCreditBalanceResponse(
                purchased, 0, 0, purchased, null, new BigDecimal("1.00"),
                false, true, 10, 500);
    }
}
