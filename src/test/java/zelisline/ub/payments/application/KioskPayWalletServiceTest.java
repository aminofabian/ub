package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.payments.api.dto.KioskPayAccountResponse;
import zelisline.ub.payments.api.dto.KioskPayPosAvailabilityResponse;
import zelisline.ub.payments.domain.KioskPayAccount;
import zelisline.ub.payments.domain.KioskPayAccountStatuses;
import zelisline.ub.payments.domain.KioskPayLedgerEntry;
import zelisline.ub.payments.domain.KioskPayLedgerEntryTypes;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.repository.KioskPayAccountRepository;
import zelisline.ub.payments.repository.KioskPayLedgerEntryRepository;

class KioskPayWalletServiceTest {

    private KioskPayAccountRepository accountRepository;
    private KioskPayLedgerEntryRepository ledgerRepository;
    private PlatformKioskPaySettingsService platformSettings;
    private ApplicationEventPublisher eventPublisher;
    private KioskPayWalletService service;

    private static final String BUSINESS = "b1";

    @BeforeEach
    void setUp() {
        accountRepository = mock(KioskPayAccountRepository.class);
        ledgerRepository = mock(KioskPayLedgerEntryRepository.class);
        platformSettings = mock(PlatformKioskPaySettingsService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new KioskPayWalletService(accountRepository, ledgerRepository, platformSettings, eventPublisher);

        when(ledgerRepository.findByReference(anyString())).thenReturn(Optional.empty());
        when(ledgerRepository.save(any(KioskPayLedgerEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any(KioskPayAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private PlatformKioskPaySettings settings(boolean enabled) {
        PlatformKioskPaySettings s = new PlatformKioskPaySettings();
        s.setEnabled(enabled);
        s.setMinWithdrawAmount(new BigDecimal("20"));
        s.setDailyWithdrawLimit(new BigDecimal("200000"));
        s.setCurrency("KES");
        return s;
    }

    private KioskPayAccount activeAccount(String businessId, String available, String pending) {
        KioskPayAccount a = new KioskPayAccount();
        a.setId("acc-1");
        a.setBusinessId(businessId);
        a.setStatus(KioskPayAccountStatuses.ACTIVE);
        a.setAvailableBalance(new BigDecimal(available));
        a.setPendingBalance(new BigDecimal(pending));
        a.setLifetimeIn(BigDecimal.ZERO);
        a.setLifetimeOut(BigDecimal.ZERO);
        return a;
    }

    @Test
    void creditPaymentCapture_creditsNetAfterProviderFee() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        when(accountRepository.findByBusinessId(BUSINESS))
                .thenReturn(Optional.of(activeAccount(BUSINESS, "0", "0")));

        service.creditPaymentCapture(BUSINESS, new BigDecimal("1000.00"), "KES", "ref-1",
                "WEB_ORDER", "order-1", "chk-1", new BigDecimal("10.00"));

        KioskPayAccount updated = accountRepository.findByBusinessId(BUSINESS).orElseThrow();
        assertThat(updated.getAvailableBalance()).isEqualByComparingTo("990.00");
        assertThat(updated.getPendingBalance()).isEqualByComparingTo("0");
        assertThat(updated.getLifetimeIn()).isEqualByComparingTo("1000.00");
        // PAYMENT_CAPTURE + PROVIDER_FEE entries
        verify(ledgerRepository, org.mockito.Mockito.times(2)).save(any(KioskPayLedgerEntry.class));
    }

    @Test
    void creditPaymentCapture_isIdempotentForDuplicateReference() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        when(accountRepository.findByBusinessId(BUSINESS))
                .thenReturn(Optional.of(activeAccount(BUSINESS, "100", "0")));
        when(ledgerRepository.findByReference("ref-dup")).thenReturn(Optional.of(new KioskPayLedgerEntry()));

        service.creditPaymentCapture(BUSINESS, new BigDecimal("500.00"), "KES", "ref-dup",
                "WEB_ORDER", "order-1", "chk-1");

        KioskPayAccount updated = accountRepository.findByBusinessId(BUSINESS).orElseThrow();
        assertThat(updated.getAvailableBalance()).isEqualByComparingTo("100");
        verify(ledgerRepository, never()).save(any(KioskPayLedgerEntry.class));
    }

    @Test
    void creditPaymentCapture_settlesEvenWhenProductDisabledAndDoesNotReactivate() {
        // An order initiated while enabled must still credit after a toggle-off —
        // and a late webhook must not silently flip an OFF account back to ACTIVE.
        when(platformSettings.loadSingleton()).thenReturn(settings(false));
        KioskPayAccount off = activeAccount(BUSINESS, "0", "0");
        off.setStatus(KioskPayAccountStatuses.OFF);
        when(accountRepository.findByBusinessId(BUSINESS)).thenReturn(Optional.of(off));

        service.creditPaymentCapture(BUSINESS, new BigDecimal("200.00"), "KES", "ref-2",
                "POS_PAYMENT", "push-1", "push-1");

        assertThat(off.getAvailableBalance()).isEqualByComparingTo("200.00");
        assertThat(off.getStatus()).isEqualTo(KioskPayAccountStatuses.OFF);
    }

    @Test
    void creditPaymentCapture_duplicateLedgerReferenceRollsBackInsteadOfSilentlyLosingMoney() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        when(accountRepository.findByBusinessId(BUSINESS))
                .thenReturn(Optional.of(activeAccount(BUSINESS, "0", "0")));
        when(ledgerRepository.save(any(KioskPayLedgerEntry.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate reference"));

        assertThatThrownBy(() -> service.creditPaymentCapture(
                BUSINESS, new BigDecimal("300.00"), "KES", "ref-3",
                "WEB_ORDER", "order-3", "chk-3"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void holdForWithdraw_movesAvailableToPending() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        KioskPayAccount account = activeAccount(BUSINESS, "1000", "0");

        service.holdForWithdraw(account, new BigDecimal("300.00"), "KES", "wd-1", "wd-hold-1");

        assertThat(account.getAvailableBalance()).isEqualByComparingTo("700.00");
        assertThat(account.getPendingBalance()).isEqualByComparingTo("300.00");
    }

    @Test
    void holdForWithdraw_insufficientBalanceThrows() {
        KioskPayAccount account = activeAccount(BUSINESS, "100", "0");

        assertThatThrownBy(() -> service.holdForWithdraw(
                account, new BigDecimal("300.00"), "KES", "wd-2", "wd-hold-2"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void settleWithdraw_removesPendingAndIncrementsLifetimeOut() {
        KioskPayAccount account = activeAccount(BUSINESS, "700", "300");

        service.settleWithdraw(account, new BigDecimal("300.00"), "KES", "wd-3", "wd-settle-3");

        assertThat(account.getAvailableBalance()).isEqualByComparingTo("700.00");
        assertThat(account.getPendingBalance()).isEqualByComparingTo("0");
        assertThat(account.getLifetimeOut()).isEqualByComparingTo("300.00");
    }

    @Test
    void releaseWithdrawHold_restoresAvailable() {
        KioskPayAccount account = activeAccount(BUSINESS, "700", "300");

        service.releaseWithdrawHold(account, new BigDecimal("300.00"), "KES", "wd-4", "wd-release-4");

        assertThat(account.getAvailableBalance()).isEqualByComparingTo("1000.00");
        assertThat(account.getPendingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void adjustBalance_creditsAndWritesAdjustmentEntry() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        when(accountRepository.findByBusinessId(BUSINESS))
                .thenReturn(Optional.of(activeAccount(BUSINESS, "0", "0")));

        KioskPayAccountResponse response = service.adjustBalance(BUSINESS, new BigDecimal("500.00"), "refund correction");

        assertThat(response.availableBalance()).isEqualByComparingTo("500.00");
        verify(ledgerRepository).save(any(KioskPayLedgerEntry.class));
    }

    @Test
    void adjustBalance_debitBelowZeroThrowsConflict() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        when(accountRepository.findByBusinessId(BUSINESS))
                .thenReturn(Optional.of(activeAccount(BUSINESS, "100", "0")));

        assertThatThrownBy(() -> service.adjustBalance(BUSINESS, new BigDecimal("-500.00"), "oops"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void adjustBalance_rejectsZeroDelta() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        when(accountRepository.findByBusinessId(BUSINESS))
                .thenReturn(Optional.of(activeAccount(BUSINESS, "100", "0")));

        assertThatThrownBy(() -> service.adjustBalance(BUSINESS, BigDecimal.ZERO, "noop"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void posAvailability_requiresStkConfigured() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        when(accountRepository.findByBusinessId(BUSINESS))
                .thenReturn(Optional.of(activeAccount(BUSINESS, "0", "0")));
        when(platformSettings.kopokopoCredentials()).thenReturn(Optional.empty());

        KioskPayPosAvailabilityResponse response = service.posAvailability(BUSINESS);

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isNotNull();
    }

    @Test
    void getAccount_exposesLimitsForMerchantUi() {
        when(platformSettings.loadSingleton()).thenReturn(settings(true));
        when(accountRepository.findByBusinessId(BUSINESS)).thenReturn(Optional.empty());

        KioskPayAccountResponse response = service.getAccount(BUSINESS);

        assertThat(response.minWithdrawAmount()).isEqualByComparingTo("20");
        assertThat(response.dailyWithdrawLimit()).isEqualByComparingTo("200000");
        assertThat(response.status()).isEqualTo(KioskPayAccountStatuses.OFF);
    }
}
