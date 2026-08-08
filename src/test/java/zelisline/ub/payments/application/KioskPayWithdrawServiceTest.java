package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.payments.api.dto.KioskPayWithdrawalResponse;
import zelisline.ub.payments.domain.KioskPayAccount;
import zelisline.ub.payments.domain.KioskPayAccountStatuses;
import zelisline.ub.payments.domain.KioskPayWithdrawal;
import zelisline.ub.payments.domain.KioskPayWithdrawalStatuses;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.KioskPayWithdrawalRepository;

class KioskPayWithdrawServiceTest {

    private KioskPayWithdrawalRepository withdrawalRepository;
    private KioskPayWalletService walletService;
    private PlatformKioskPaySettingsService platformSettings;
    private KopokopoPaymentGateway kopokopoGateway;
    private KioskPayWithdrawService service;

    private static final String BUSINESS = "b1";
    private static final Map<String, String> CREDS =
            Map.of("clientId", "c", "clientSecret", "s", "apiKey", "k", "tillNumber", "123456");

    @BeforeEach
    void setUp() {
        withdrawalRepository = mock(KioskPayWithdrawalRepository.class);
        walletService = mock(KioskPayWalletService.class);
        platformSettings = mock(PlatformKioskPaySettingsService.class);
        kopokopoGateway = mock(KopokopoPaymentGateway.class);
        service = new KioskPayWithdrawService(
                withdrawalRepository, walletService, platformSettings, kopokopoGateway);

        when(withdrawalRepository.findByBusinessIdAndIdempotencyKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(withdrawalRepository.existsByBusinessIdAndStatusIn(anyString(), any())).thenReturn(false);
        when(withdrawalRepository.sumSuccessfulSince(anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(withdrawalRepository.findByBusinessIdAndStatusInOrderByCreatedAtAsc(anyString(), any()))
                .thenReturn(List.of());
        when(withdrawalRepository.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(withdrawalRepository.save(any(KioskPayWithdrawal.class)))
                .thenAnswer(inv -> {
                    // Mirrors @PrePersist: JPA assigns the id on save, plain `new` does not.
                    KioskPayWithdrawal row = inv.getArgument(0);
                    if (row.getId() == null) {
                        row.setId("wd-" + java.util.UUID.randomUUID());
                    }
                    return row;
                });
        when(platformSettings.requireEnabledSettings()).thenReturn(settings());
        when(platformSettings.kopokopoCredentials()).thenReturn(Optional.of(CREDS));
        when(walletService.getOrCreateForUpdate(BUSINESS)).thenReturn(activeAccount());
        when(walletService.getOrCreate(BUSINESS)).thenReturn(activeAccount());
        when(kopokopoGateway.sendMoney(any())).thenReturn(SendMoneyResult.accepted("sm-1"));
    }

    private PlatformKioskPaySettings settings() {
        PlatformKioskPaySettings s = new PlatformKioskPaySettings();
        s.setEnabled(true);
        s.setMinWithdrawAmount(new BigDecimal("20"));
        s.setDailyWithdrawLimit(new BigDecimal("200000"));
        s.setCurrency("KES");
        return s;
    }

    private KioskPayAccount activeAccount() {
        KioskPayAccount a = new KioskPayAccount();
        a.setId("acc-1");
        a.setBusinessId(BUSINESS);
        a.setStatus(KioskPayAccountStatuses.ACTIVE);
        a.setAvailableBalance(new BigDecimal("100000"));
        a.setPendingBalance(BigDecimal.ZERO);
        a.setLifetimeIn(BigDecimal.ZERO);
        a.setLifetimeOut(BigDecimal.ZERO);
        return a;
    }

    private zelisline.ub.payments.api.dto.KioskPayWithdrawRequest request(String amount, String phone, String idem) {
        return new zelisline.ub.payments.api.dto.KioskPayWithdrawRequest(
                new BigDecimal(amount), phone, idem);
    }

    @Test
    void requestWithdraw_happyPathHoldsAndMarksProcessing() {
        KioskPayWithdrawalResponse response = service.requestWithdraw(BUSINESS, request("100", "0712345678", "idem-1"));

        assertThat(response.status()).isEqualTo(KioskPayWithdrawalStatuses.PROCESSING);
        assertThat(response.phoneNumber()).isEqualTo("254712345678");
        verify(walletService).holdForWithdraw(
                any(KioskPayAccount.class), eq(new BigDecimal("100.00")), eq("KES"), anyString(), anyString());
        verify(kopokopoGateway).sendMoney(any());
    }

    @Test
    void requestWithdraw_rejectedByProviderMarksFailedAndReleasesHold() {
        when(kopokopoGateway.sendMoney(any()))
                .thenReturn(SendMoneyResult.rejected("SOURCE_INVALID", "Source identifier is invalid"));

        KioskPayWithdrawalResponse response = service.requestWithdraw(BUSINESS, request("100", "0712345678", "idem-2"));

        assertThat(response.status()).isEqualTo(KioskPayWithdrawalStatuses.FAILED);
        assertThat(response.failureReason())
                .isEqualTo("Withdrawal couldn't go through right now. Your balance was restored — try again in a few minutes.");
        verify(walletService).releaseWithdrawHold(
                any(KioskPayAccount.class), eq(new BigDecimal("100.00")), eq("KES"), anyString(), anyString());
    }

    @Test
    void requestWithdraw_belowMinimumRejects() {
        assertThatThrownBy(() -> service.requestWithdraw(BUSINESS, request("5", "0712345678", "idem-3")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requestWithdraw_dailyLimitExceededRejects() {
        when(withdrawalRepository.sumSuccessfulSince(anyString(), any()))
                .thenReturn(new BigDecimal("199999"));

        assertThatThrownBy(() -> service.requestWithdraw(BUSINESS, request("100", "0712345678", "idem-4")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requestWithdraw_oneInFlightConflict() {
        when(withdrawalRepository.existsByBusinessIdAndStatusIn(anyString(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.requestWithdraw(BUSINESS, request("100", "0712345678", "idem-5")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void requestWithdraw_duplicateIdempotencyKeyReturnsExisting() {
        KioskPayWithdrawal existing = new KioskPayWithdrawal();
        existing.setId("wd-existing");
        existing.setBusinessId(BUSINESS);
        existing.setStatus(KioskPayWithdrawalStatuses.PROCESSING);
        existing.setPhoneNumber("254712345678");
        existing.setAmount(new BigDecimal("100.00"));
        existing.setCurrency("KES");
        when(withdrawalRepository.findByBusinessIdAndIdempotencyKey(BUSINESS, "idem-6"))
                .thenReturn(Optional.of(existing));

        KioskPayWithdrawalResponse response = service.requestWithdraw(BUSINESS, request("100", "0712345678", "idem-6"));

        assertThat(response.id()).isEqualTo("wd-existing");
        verify(kopokopoGateway, never()).sendMoney(any());
    }

    @Test
    void requestWithdraw_invalidPhoneRejects() {
        assertThatThrownBy(() -> service.requestWithdraw(BUSINESS, request("100", "not-a-phone", "idem-7")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requestWithdraw_floatPausedFailsFast() {
        PlatformKioskPaySettings constrained = settings();
        constrained.setSendMoneyFloatConstrainedUntil(Instant.now().plusSeconds(300));
        when(platformSettings.requireEnabledSettings()).thenReturn(constrained);

        assertThatThrownBy(() -> service.requestWithdraw(BUSINESS, request("100", "0712345678", "idem-8")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(kopokopoGateway, never()).sendMoney(any());
        verify(walletService, never()).holdForWithdraw(
                any(KioskPayAccount.class), any(), any(), anyString(), anyString());
    }

    @Test
    void requestWithdraw_floatInsufficientRejectionPausesAndClassifies() {
        when(kopokopoGateway.sendMoney(any()))
                .thenReturn(SendMoneyResult.rejected(
                        "INSUFFICIENT_FUNDS", "Transfer amount exceeds amount available to move"));

        KioskPayWithdrawalResponse response = service.requestWithdraw(BUSINESS, request("190", "0712345678", "idem-9"));

        assertThat(response.status()).isEqualTo(KioskPayWithdrawalStatuses.FAILED);
        assertThat(response.failureReason())
                .isEqualTo("Withdrawal couldn't go through right now. Your balance was restored — try again in a few minutes.");
        assertThat(response.failureReason()).doesNotContain("amount available to move");
        verify(platformSettings).markSendMoneyFloatConstrained(any());
        verify(walletService).releaseWithdrawHold(
                any(KioskPayAccount.class), eq(new BigDecimal("190.00")), eq("KES"), anyString(), anyString());
    }

    @Test
    void handleSendMoneyWebhook_successSettles() {
        KioskPayWithdrawal row = processingRow("sm-1");
        when(withdrawalRepository.findByKopokopoSendMoneyId("sm-1")).thenReturn(Optional.of(row));

        WebhookResult webhook = new WebhookResult(
                null, "sm-1", "254712345678", new BigDecimal("100.00"), null,
                true, false, null, "evt-1", "send_money", null);

        boolean handled = service.handleSendMoneyWebhook(webhook);

        assertThat(handled).isTrue();
        assertThat(row.getStatus()).isEqualTo(KioskPayWithdrawalStatuses.SUCCESS);
        verify(walletService).settleWithdraw(
                any(KioskPayAccount.class), eq(new BigDecimal("100.00")), eq("KES"), eq(row.getId()), anyString());
    }

    @Test
    void handleSendMoneyWebhook_terminalFailureReleasesHold() {
        KioskPayWithdrawal row = processingRow("sm-2");
        when(withdrawalRepository.findByKopokopoSendMoneyId("sm-2")).thenReturn(Optional.of(row));

        WebhookResult webhook = new WebhookResult(
                null, "sm-2", "254712345678", new BigDecimal("100.00"), null,
                false, true, null, "evt-2", "send_money", null, "Recipient not registered");

        service.handleSendMoneyWebhook(webhook);

        assertThat(row.getStatus()).isEqualTo(KioskPayWithdrawalStatuses.FAILED);
        assertThat(row.getFailureReason()).isEqualTo("Recipient not registered");
        verify(walletService).releaseWithdrawHold(
                any(KioskPayAccount.class), eq(new BigDecimal("100.00")), eq("KES"), eq(row.getId()), anyString());
    }

    @Test
    void publicFailureReason_hidesFloatAndKeepsRealRecipientErrors() {
        assertThat(KioskPayWithdrawService.publicFailureReason(
                "Transfer amount exceeds amount available to move"))
                .doesNotContain("amount available to move")
                .contains("try again");
        assertThat(KioskPayWithdrawService.publicFailureReason(
                "Transfer amount together with the transfer fees exceeds amount available to move"))
                .doesNotContain("transfer fees");
        assertThat(KioskPayWithdrawService.publicFailureReason("Recipient not registered"))
                .isEqualTo("Recipient not registered");
    }

    @Test
    void reconcileAllInFlight_releasesStaleRequestedRow() {
        KioskPayWithdrawal stale = new KioskPayWithdrawal();
        stale.setId("wd-stale");
        stale.setBusinessId(BUSINESS);
        stale.setAccountId("acc-1");
        stale.setAmount(new BigDecimal("100.00"));
        stale.setCurrency("KES");
        stale.setPhoneNumber("254712345678");
        stale.setStatus(KioskPayWithdrawalStatuses.REQUESTED);
        stale.setIdempotencyKey("idem-stale");
        stale.setRequestedAt(Instant.now().minusSeconds(60));
        when(withdrawalRepository.findByStatusInOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(stale));
        when(withdrawalRepository.findByBusinessIdAndStatusInOrderByCreatedAtAsc(anyString(), any()))
                .thenReturn(List.of(stale));

        int changed = service.reconcileAllInFlight();

        assertThat(changed).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(KioskPayWithdrawalStatuses.FAILED);
        verify(walletService).releaseWithdrawHold(
                any(KioskPayAccount.class), eq(new BigDecimal("100.00")), eq("KES"), eq(stale.getId()), anyString());
    }

    private KioskPayWithdrawal processingRow(String sendMoneyId) {
        KioskPayWithdrawal row = new KioskPayWithdrawal();
        row.setId("wd-" + sendMoneyId);
        row.setBusinessId(BUSINESS);
        row.setAccountId("acc-1");
        row.setAmount(new BigDecimal("100.00"));
        row.setCurrency("KES");
        row.setPhoneNumber("254712345678");
        row.setStatus(KioskPayWithdrawalStatuses.PROCESSING);
        row.setIdempotencyKey("idem-" + sendMoneyId);
        row.setKopokopoSendMoneyId(sendMoneyId);
        row.setRequestedAt(Instant.now());
        row.setProcessingAt(Instant.now());
        return row;
    }
}
