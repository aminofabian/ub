package zelisline.ub.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentWebhookEvent;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PaymentWebhookEventRepository;
import zelisline.ub.purchasing.api.dto.PostSupplierPaymentResponse;
import zelisline.ub.purchasing.domain.SupplierDisbursement;
import zelisline.ub.purchasing.domain.SupplierDisbursementStatuses;
import zelisline.ub.purchasing.repository.SupplierDisbursementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

@ExtendWith(MockitoExtension.class)
class SupplierDisbursementWebhookTest {

    @Mock
    private SupplierDisbursementRepository disbursementRepository;
    @Mock
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock
    private SupplierPaymentAllocationRepository allocationRepository;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private PaymentGatewayConfigRepository configRepository;
    @Mock
    private SupplierPayoutSettingsService supplierPayoutSettingsService;
    @Mock
    private CredentialEncryptionService encryptionService;
    @Mock
    private KopokopoPaymentGateway kopokopoGateway;
    @Mock
    private SupplierPaymentService supplierPaymentService;
    @Mock
    private PaymentWebhookEventRepository webhookEventRepository;
    @Mock
    private PathBAssociatedCostService pathBAssociatedCostService;

    private SupplierDisbursementService service;

    @BeforeEach
    void setUp() {
        service = new SupplierDisbursementService(
                disbursementRepository,
                supplierInvoiceRepository,
                allocationRepository,
                supplierRepository,
                configRepository,
                supplierPayoutSettingsService,
                encryptionService,
                kopokopoGateway,
                supplierPaymentService,
                webhookEventRepository,
                new ObjectMapper(),
                pathBAssociatedCostService);
        ReflectionTestUtils.setField(service, "publicApiBaseUrl", "http://localhost:5050");
    }

    @Test
    void intermediateWebhook_doesNotBurnEventIdOrConfirm() {
        SupplierDisbursement pending = pendingDisbursement();
        when(disbursementRepository.findByKopokopoSendMoneyId("sm-1")).thenReturn(Optional.of(pending));

        WebhookResult pendingResult = sendMoney(
                false,
                false,
                "sm-1",
                "sm-1",
                null);

        boolean handled = service.processKopokopoSendMoneyWebhook("biz-1", "cfg-1", pendingResult);

        assertThat(handled).isTrue();
        assertThat(pending.getStatus()).isEqualTo(SupplierDisbursementStatuses.PENDING);
        verify(webhookEventRepository, never()).save(any());
        verify(supplierPaymentService, never()).recordKopokopoDisbursement(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void successAfterIntermediate_confirmsAndRecordsEvent() {
        SupplierDisbursement pending = pendingDisbursement();
        when(disbursementRepository.findByKopokopoSendMoneyId("sm-1")).thenReturn(Optional.of(pending));
        when(webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, "sm-1"))
                .thenReturn(false);
        when(supplierPaymentService.recordKopokopoDisbursement(
                eq("biz-1"),
                eq("sup-1"),
                eq("inv-1"),
                eq(new BigDecimal("20.00")),
                eq("QCLREF"),
                any(Instant.class)))
                .thenReturn(new PostSupplierPaymentResponse("pay-1", null, null, null));

        WebhookResult success = sendMoney(true, false, "sm-1", "sm-1", "QCLREF");

        boolean handled = service.processKopokopoSendMoneyWebhook("biz-1", "cfg-1", success);

        assertThat(handled).isTrue();
        assertThat(pending.getStatus()).isEqualTo(SupplierDisbursementStatuses.SUCCESS);
        assertThat(pending.getSupplierPaymentId()).isEqualTo("pay-1");
        verify(webhookEventRepository).save(any(PaymentWebhookEvent.class));
        verify(supplierPaymentService).recordKopokopoDisbursement(
                eq("biz-1"),
                eq("sup-1"),
                eq("inv-1"),
                eq(new BigDecimal("20.00")),
                eq("QCLREF"),
                any(Instant.class));
    }

    @Test
    void successWhenEventIdAlreadyBurned_stillConfirmsOpenDisbursement() {
        SupplierDisbursement pending = pendingDisbursement();
        when(disbursementRepository.findByKopokopoSendMoneyId("sm-1")).thenReturn(Optional.of(pending));
        when(webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, "sm-1"))
                .thenReturn(true);
        when(supplierPaymentService.recordKopokopoDisbursement(
                eq("biz-1"),
                eq("sup-1"),
                eq("inv-1"),
                eq(new BigDecimal("20.00")),
                eq("QCLREF"),
                any(Instant.class)))
                .thenReturn(new PostSupplierPaymentResponse("pay-1", null, null, null));

        WebhookResult success = sendMoney(true, false, "sm-1", "sm-1", "QCLREF");

        boolean handled = service.processKopokopoSendMoneyWebhook("biz-1", "cfg-1", success);

        assertThat(handled).isTrue();
        assertThat(pending.getStatus()).isEqualTo(SupplierDisbursementStatuses.SUCCESS);
        verify(webhookEventRepository, never()).save(any());
        verify(supplierPaymentService, times(1)).recordKopokopoDisbursement(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelPending_marksCancelledWhenKopokopoStillPending() {
        SupplierDisbursement pending = pendingDisbursement();
        when(disbursementRepository.findByBusinessIdAndSupplierInvoiceIdOrderByCreatedAtDesc("biz-1", "inv-1"))
                .thenReturn(List.of(pending));
        when(disbursementRepository.save(pending)).thenReturn(pending);

        var response = service.cancelDisbursement("biz-1", "inv-1");

        assertThat(response.status()).isEqualTo(SupplierDisbursementStatuses.CANCELLED);
        assertThat(pending.getStatus()).isEqualTo(SupplierDisbursementStatuses.CANCELLED);
        verify(supplierPaymentService, never()).recordKopokopoDisbursement(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelPending_recordsLedgerWhenKopokopoAlreadySucceeded() {
        SupplierDisbursement pending = pendingDisbursement();
        pending.setPaymentGatewayConfigId("cfg-1");
        when(disbursementRepository.findByBusinessIdAndSupplierInvoiceIdOrderByCreatedAtDesc("biz-1", "inv-1"))
                .thenReturn(List.of(pending));
        zelisline.ub.payments.domain.PaymentGatewayConfig cfg = new zelisline.ub.payments.domain.PaymentGatewayConfig();
        cfg.setId("cfg-1");
        cfg.setGatewayType(GatewayType.KOPOKOPO);
        cfg.setCredentialsJson("enc");
        when(configRepository.findById("cfg-1")).thenReturn(Optional.of(cfg));
        when(encryptionService.decrypt("enc")).thenReturn("{\"tillNumber\":\"123\"}");
        when(kopokopoGateway.querySendMoneyStatus(eq("sm-1"), any()))
                .thenReturn(sendMoney(true, false, "sm-1", "sm-1", "QCLREF"));
        when(supplierPaymentService.recordKopokopoDisbursement(
                any(), any(), any(), any(), any(), any()))
                .thenReturn(new PostSupplierPaymentResponse("pay-1", null, null, null));

        var response = service.cancelDisbursement("biz-1", "inv-1");

        assertThat(response.status()).isEqualTo(SupplierDisbursementStatuses.SUCCESS);
        assertThat(pending.getSupplierPaymentId()).isEqualTo("pay-1");
    }

    @Test
    void intermediateThenSuccessSequence_onlyPersistsOnSuccess() {
        SupplierDisbursement pending = pendingDisbursement();
        when(disbursementRepository.findByKopokopoSendMoneyId("sm-1")).thenReturn(Optional.of(pending));
        when(webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, "sm-1"))
                .thenReturn(false);
        when(supplierPaymentService.recordKopokopoDisbursement(
                any(), any(), any(), any(), any(), any()))
                .thenReturn(new PostSupplierPaymentResponse("pay-1", null, null, null));

        service.processKopokopoSendMoneyWebhook(
                "biz-1", "cfg-1", sendMoney(false, false, "sm-1", "sm-1", null));
        service.processKopokopoSendMoneyWebhook(
                "biz-1", "cfg-1", sendMoney(true, false, "sm-1", "sm-1", "QCLREF"));

        ArgumentCaptor<PaymentWebhookEvent> captor = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(webhookEventRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getGatewayEventId()).isEqualTo("sm-1");
        assertThat(pending.getStatus()).isEqualTo(SupplierDisbursementStatuses.SUCCESS);
    }

    private static SupplierDisbursement pendingDisbursement() {
        SupplierDisbursement d = new SupplierDisbursement();
        d.setId("disb-1");
        d.setBusinessId("biz-1");
        d.setSupplierId("sup-1");
        d.setSupplierInvoiceId("inv-1");
        d.setKopokopoSendMoneyId("sm-1");
        d.setAmount(new BigDecimal("20.00"));
        d.setStatus(SupplierDisbursementStatuses.PENDING);
        d.setCreatedAt(Instant.now());
        return d;
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
                new BigDecimal("20.00"),
                "inv-1",
                success,
                terminalFailure,
                checkoutId,
                eventId,
                "send_money",
                "{}",
                null);
    }
}
