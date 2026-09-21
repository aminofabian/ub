package zelisline.ub.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.domain.Expense;
import zelisline.ub.finance.domain.ExpenseDisbursement;
import zelisline.ub.finance.domain.ExpenseDisbursementStatuses;
import zelisline.ub.finance.repository.ExpenseDisbursementRepository;
import zelisline.ub.finance.repository.ExpenseRepository;
import zelisline.ub.finance.repository.ExpenseScheduleOccurrenceRepository;
import zelisline.ub.finance.repository.ExpenseScheduleRepository;
import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentWebhookEvent;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PaymentWebhookEventRepository;

@ExtendWith(MockitoExtension.class)
class ExpenseDisbursementWebhookTest {

    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private ExpenseDisbursementRepository disbursementRepository;
    @Mock
    private ExpenseScheduleOccurrenceRepository occurrenceRepository;
    @Mock
    private ExpenseScheduleRepository scheduleRepository;
    @Mock
    private PaymentGatewayConfigRepository configRepository;
    @Mock
    private SupplierPayoutSettingsService supplierPayoutSettingsService;
    @Mock
    private CredentialEncryptionService encryptionService;
    @Mock
    private KopokopoPaymentGateway kopokopoGateway;
    @Mock
    private PaymentWebhookEventRepository webhookEventRepository;
    @Mock
    private AuditEventPublisher auditEventPublisher;

    private ExpenseDisbursementService service;

    @BeforeEach
    void setUp() {
        AuditEventBuilder auditEventBuilder = mock(AuditEventBuilder.class, RETURNS_DEEP_STUBS);
        service = new ExpenseDisbursementService(
                expenseRepository,
                disbursementRepository,
                occurrenceRepository,
                scheduleRepository,
                configRepository,
                supplierPayoutSettingsService,
                encryptionService,
                kopokopoGateway,
                webhookEventRepository,
                new ObjectMapper(),
                auditEventPublisher,
                auditEventBuilder);
        ReflectionTestUtils.setField(service, "publicApiBaseUrl", "http://localhost:5050");
    }

    @Test
    void unmatchedWebhook_returnsFalse() {
        when(disbursementRepository.findByKopokopoSendMoneyId("sm-x")).thenReturn(Optional.empty());

        boolean handled = service.processKopokopoSendMoneyWebhook(
                "biz-1", "cfg-1", sendMoney(true, false, "sm-x", "sm-x", "REF"));

        assertThat(handled).isFalse();
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void successWebhook_marksExpensePaid() {
        ExpenseDisbursement pending = pendingDisbursement();
        Expense expense = postedExpense();
        when(disbursementRepository.findByKopokopoSendMoneyId("sm-1")).thenReturn(Optional.of(pending));
        when(webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, "sm-1"))
                .thenReturn(false);
        when(expenseRepository.findByIdAndBusinessId("exp-1", "biz-1")).thenReturn(Optional.of(expense));

        boolean handled = service.processKopokopoSendMoneyWebhook(
                "biz-1", "cfg-1", sendMoney(true, false, "sm-1", "sm-1", "QCL"));

        assertThat(handled).isTrue();
        assertThat(pending.getStatus()).isEqualTo(ExpenseDisbursementStatuses.SUCCESS);
        assertThat(expense.getPaidAt()).isNotNull();
        verify(webhookEventRepository).save(any(PaymentWebhookEvent.class));
        verify(expenseRepository).save(expense);
        verify(disbursementRepository).save(pending);
    }

    private static ExpenseDisbursement pendingDisbursement() {
        ExpenseDisbursement d = new ExpenseDisbursement();
        d.setId("ed-1");
        d.setBusinessId("biz-1");
        d.setExpenseId("exp-1");
        d.setGatewayType(GatewayType.KOPOKOPO);
        d.setPaymentGatewayConfigId("cfg-1");
        d.setKopokopoSendMoneyId("sm-1");
        d.setAmount(new BigDecimal("1500.00"));
        d.setCurrency("KES");
        d.setStatus(ExpenseDisbursementStatuses.PENDING);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        return d;
    }

    private static Expense postedExpense() {
        Expense e = new Expense();
        e.setId("exp-1");
        e.setBusinessId("biz-1");
        e.setName("Shop rent");
        e.setAmount(new BigDecimal("1500.00"));
        e.setPaymentMethod(FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL);
        e.setApprovalStatus(FinanceConstants.EXPENSE_APPROVAL_POSTED);
        e.setJournalEntryId("je-1");
        e.setExpenseLedgerAccountId("acc-1");
        e.setCategoryType(FinanceConstants.EXPENSE_CATEGORY_FIXED);
        e.setSource(FinanceConstants.EXPENSE_SOURCE_RECURRING);
        e.setCreatedBy("user-1");
        return e;
    }

    private static WebhookResult sendMoney(
            boolean success,
            boolean terminalFailure,
            String checkoutId,
            String eventId,
            String txnRef
    ) {
        return new WebhookResult(
                null,
                txnRef != null ? txnRef : checkoutId,
                null,
                new BigDecimal("1500.00"),
                "exp-1",
                success,
                terminalFailure,
                checkoutId,
                eventId,
                "send_money",
                "{}",
                null);
    }
}
