package zelisline.ub.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.messaging.api.dto.SmsCreditBalanceResponse;
import zelisline.ub.messaging.domain.BusinessSmsCreditAccount;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.domain.SmsCreditLedgerEntry;
import zelisline.ub.messaging.domain.SmsCreditLedgerKind;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.repository.BusinessSmsCreditAccountRepository;
import zelisline.ub.messaging.repository.SmsCreditLedgerRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@ExtendWith(MockitoExtension.class)
class SmsCreditServiceTest {

    private static final String BIZ = "biz-1";

    @Mock
    private BusinessSmsCreditAccountRepository accountRepository;
    @Mock
    private SmsCreditLedgerRepository ledgerRepository;
    @Mock
    private BusinessRepository businessRepository;
    @Mock
    private SmsCreditSettingsService settingsService;
    @Mock
    private AuditEventPublisher auditEventPublisher;
    @Mock
    private AuditEventBuilder auditEventBuilder;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private AuditEventBuilder.Builder builder;

    private SmsCreditService service;

    @BeforeEach
    void setUp() {
        service = new SmsCreditService(
                accountRepository, ledgerRepository, businessRepository,
                settingsService, auditEventPublisher, auditEventBuilder, eventPublisher);
        PlatformSmsCreditSettings settings = new PlatformSmsCreditSettings();
        settings.setId(PlatformSmsCreditSettings.SINGLETON_ID);
        settings.setEnabled(true);
        when(settingsService.loadSingleton()).thenReturn(settings);
        lenient().when(auditEventBuilder.builder(any(AuditEventCategory.class), any(), any(AuditEventSeverity.class)))
                .thenReturn(builder);
        lenient().when(builder.businessId(any())).thenReturn(builder);
        lenient().when(builder.actor(any(), any(AuditEventActorType.class))).thenReturn(builder);
        lenient().when(builder.target(any(), any())).thenReturn(builder);
        lenient().when(builder.targetLabel(any())).thenReturn(builder);
        lenient().when(builder.source(any())).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(null);
    }

    private Business tieredBusiness(String tier) {
        Business b = new Business();
        b.setId(BIZ);
        b.setSubscriptionTier(tier);
        return b;
    }

    private BusinessSmsCreditAccount account(int includedUsed, int purchased) {
        BusinessSmsCreditAccount a = new BusinessSmsCreditAccount();
        a.setBusinessId(BIZ);
        a.setIncludedUsed(includedUsed);
        a.setPurchasedBalance(purchased);
        a.setCycleStartedAt(Instant.now());
        return a;
    }

    private void stubAllowance(int allowance) {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(tieredBusiness("starter")));
        when(settingsService.resolveAllowance("starter")).thenReturn(allowance);
    }

    @Test
    void balanceViewComputesIncludedThenPurchased() {
        stubAllowance(30);
        when(accountRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(account(12, 45)));

        SmsCreditBalanceResponse view = service.getBalanceView(BIZ);

        assertThat(view.available()).isEqualTo(63);
        assertThat(view.includedRemaining()).isEqualTo(18);
        assertThat(view.includedAllowance()).isEqualTo(30);
        assertThat(view.purchasedBalance()).isEqualTo(45);
        assertThat(view.unitPriceKes()).isEqualByComparingTo("1.00");
        assertThat(view.lowBalance()).isFalse();
    }

    @Test
    void debitConsumesIncludedFirst() {
        stubAllowance(30);
        when(accountRepository.findForUpdate(BIZ)).thenReturn(Optional.of(account(12, 0)));

        int after = service.debit(BIZ, SmsSendReason.OTP, null);

        assertThat(after).isEqualTo(17);
        ArgumentCaptor<BusinessSmsCreditAccount> saved = ArgumentCaptor.forClass(BusinessSmsCreditAccount.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getIncludedUsed()).isEqualTo(13);
        assertThat(saved.getValue().getPurchasedBalance()).isZero();
        ArgumentCaptor<SmsCreditLedgerEntry> entry = ArgumentCaptor.forClass(SmsCreditLedgerEntry.class);
        verify(ledgerRepository).save(entry.capture());
        assertThat(entry.getValue().getKind()).isEqualTo(SmsCreditLedgerKind.INCLUDED_SPEND);
        assertThat(entry.getValue().getDelta()).isEqualTo(-1);
        assertThat(entry.getValue().getBalanceAfter()).isEqualTo(17);
        assertThat(entry.getValue().getReason()).isEqualTo("otp");
    }

    @Test
    void debitFallsThroughToPurchasedWhenIncludedExhausted() {
        stubAllowance(30);
        when(accountRepository.findForUpdate(BIZ)).thenReturn(Optional.of(account(30, 10)));

        int after = service.debit(BIZ, SmsSendReason.PAYROLL, "ref-1");

        assertThat(after).isEqualTo(9);
        ArgumentCaptor<BusinessSmsCreditAccount> saved = ArgumentCaptor.forClass(BusinessSmsCreditAccount.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getPurchasedBalance()).isEqualTo(9);
        assertThat(saved.getValue().getIncludedUsed()).isEqualTo(30);
        ArgumentCaptor<SmsCreditLedgerEntry> entry = ArgumentCaptor.forClass(SmsCreditLedgerEntry.class);
        verify(ledgerRepository).save(entry.capture());
        assertThat(entry.getValue().getKind()).isEqualTo(SmsCreditLedgerKind.PURCHASED_SPEND);
        assertThat(entry.getValue().getReferenceId()).isEqualTo("ref-1");
    }

    @Test
    void debitThrowsWhenNothingLeft() {
        stubAllowance(30);
        when(accountRepository.findForUpdate(BIZ)).thenReturn(Optional.of(account(30, 0)));

        assertThatThrownBy(() -> service.debit(BIZ, SmsSendReason.OTP, null))
                .isInstanceOf(SmsCreditsDepletedException.class)
                .satisfies(ex -> {
                    SmsCreditsDepletedException e = (SmsCreditsDepletedException) ex;
                    assertThat(e.getAvailable()).isZero();
                    assertThat(e.getIncludedRemaining()).isZero();
                    assertThat(e.getUnitPriceKes()).isEqualByComparingTo(BigDecimal.ONE);
                });
    }

    @Test
    void requireAvailableThrowsWhenZero() {
        stubAllowance(30);
        when(accountRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(account(30, 0)));

        assertThatThrownBy(() -> service.requireAvailable(BIZ, SmsSendReason.OTP, null))
                .isInstanceOf(SmsCreditsDepletedException.class);
    }

    @Test
    void grantCreditsPurchasedBalanceAndLedger() {
        stubAllowance(30);
        when(accountRepository.findForUpdate(BIZ)).thenReturn(Optional.of(account(10, 5)));

        int after = service.grant(BIZ, 50, "manual", "sa-1");

        assertThat(after).isEqualTo(75);
        ArgumentCaptor<SmsCreditLedgerEntry> entry = ArgumentCaptor.forClass(SmsCreditLedgerEntry.class);
        verify(ledgerRepository).save(entry.capture());
        assertThat(entry.getValue().getKind()).isEqualTo(SmsCreditLedgerKind.GRANT);
        assertThat(entry.getValue().getDelta()).isEqualTo(50);
        assertThat(entry.getValue().getCreatedByUserId()).isEqualTo("sa-1");
        verify(auditEventPublisher).publish(eq(null));
    }

    @Test
    void includedOverrideWinsOverTier() {
        BusinessSmsCreditAccount a = account(5, 2);
        a.setIncludedOverride(100);
        when(accountRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(a));

        SmsCreditBalanceResponse view = service.getBalanceView(BIZ);

        assertThat(view.includedAllowance()).isEqualTo(100);
        assertThat(view.available()).isEqualTo(97);
    }
}
