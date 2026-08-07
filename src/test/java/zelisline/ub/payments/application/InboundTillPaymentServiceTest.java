package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.credits.CreditClaimChannels;
import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.application.PublicPaymentClaimService;
import zelisline.ub.credits.domain.PublicPaymentClaim;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.InboundTillPayment;
import zelisline.ub.payments.domain.InboundTillPaymentStatuses;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.repository.InboundTillPaymentRepository;

@ExtendWith(MockitoExtension.class)
class InboundTillPaymentServiceTest {

    @Mock
    private InboundTillPaymentRepository inboundRepository;
    @Mock
    private PublicPaymentClaimRepository publicPaymentClaimRepository;
    @Mock
    private ObjectProvider<PublicPaymentClaimService> publicPaymentClaimServiceProvider;
    @Mock
    private PublicPaymentClaimService publicPaymentClaimService;

    private InboundTillPaymentService service;

    @BeforeEach
    void setUp() {
        service = new InboundTillPaymentService(
                inboundRepository,
                publicPaymentClaimRepository,
                publicPaymentClaimServiceProvider,
                new ObjectMapper());
    }

    @Test
    void persistUnmatchedBuygoods_savesPendingWithTillAndPhone() {
        when(inboundRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, "evt-1"))
                .thenReturn(false);
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCase("biz-1", "OJL7OW3J59"))
                .thenReturn(Optional.empty());
        when(inboundRepository.save(any(InboundTillPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        String raw = """
                {"topic":"buygoods_transaction_received","id":"evt-1","event":{"resource":{
                  "amount":"150.00","status":"Received","reference":"OJL7OW3J59",
                  "sender_phone_number":"+254714282874","till_number":"K000123"
                }}}
                """;
        WebhookResult parsed = buygoods("evt-1", "OJL7OW3J59", "0714282874", "150.00", raw);

        Optional<InboundTillPayment> saved = service.persistUnmatchedBuygoods("biz-1", parsed);

        assertThat(saved).isPresent();
        ArgumentCaptor<InboundTillPayment> cap = ArgumentCaptor.forClass(InboundTillPayment.class);
        verify(inboundRepository).save(cap.capture());
        InboundTillPayment row = cap.getValue();
        assertThat(row.getStatus()).isEqualTo(InboundTillPaymentStatuses.PENDING);
        assertThat(row.getMpesaReceipt()).isEqualTo("OJL7OW3J59");
        assertThat(row.getPhone()).isEqualTo("254714282874");
        assertThat(row.getTillNumber()).isEqualTo("K000123");
        assertThat(row.getAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void persistUnmatchedBuygoods_dedupesByEventId() {
        InboundTillPayment existing = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        when(inboundRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, "evt-1"))
                .thenReturn(true);
        when(inboundRepository.findByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, "evt-1"))
                .thenReturn(Optional.of(existing));

        WebhookResult parsed = buygoods("evt-1", "OJL7OW3J59", "0714282874", "150.00", "{}");
        Optional<InboundTillPayment> saved = service.persistUnmatchedBuygoods("biz-1", parsed);

        assertThat(saved).contains(existing);
        verify(inboundRepository, never()).save(any());
    }

    @Test
    void findClearPendingMatch_prefersPhoneAndAmount() {
        InboundTillPayment match = pending("in-1", "AAA111", "254714282874", "200.00");
        InboundTillPayment other = pending("in-2", "BBB222", "254700000000", "200.00");
        when(inboundRepository.findByBusinessIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("biz-1"), eq(InboundTillPaymentStatuses.PENDING), any(Instant.class)))
                .thenReturn(List.of(match, other));

        Optional<InboundTillPayment> found = service.findClearPendingMatch(
                "biz-1", new BigDecimal("200.00"), "0714282874");

        assertThat(found).contains(match);
    }

    @Test
    void findClearPendingMatch_uniqueAmountWithoutPhone() {
        InboundTillPayment only = pending("in-1", "AAA111", "254714282874", "99.00");
        when(inboundRepository.findByBusinessIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("biz-1"), eq(InboundTillPaymentStatuses.PENDING), any(Instant.class)))
                .thenReturn(List.of(only));

        Optional<InboundTillPayment> found = service.findClearPendingMatch(
                "biz-1", new BigDecimal("99.00"), null);

        assertThat(found).contains(only);
    }

    @Test
    void findClearPendingMatch_skipsAmbiguousAmount() {
        InboundTillPayment a = pending("in-1", "AAA111", "254711111111", "50.00");
        InboundTillPayment b = pending("in-2", "BBB222", "254722222222", "50.00");
        when(inboundRepository.findByBusinessIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("biz-1"), eq(InboundTillPaymentStatuses.PENDING), any(Instant.class)))
                .thenReturn(List.of(a, b));

        Optional<InboundTillPayment> found = service.findClearPendingMatch(
                "biz-1", new BigDecimal("50.00"), null);

        assertThat(found).isEmpty();
    }

    @Test
    void linkToSaleByReceipt_marksLinkedAndReturnsTxn() {
        InboundTillPayment row = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                eq("biz-1"), eq("OJL7OW3J59"), eq(InboundTillPaymentStatuses.PENDING)))
                .thenReturn(Optional.of(row));
        when(inboundRepository.save(any(InboundTillPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<String> txn = service.linkToSaleByReceipt(
                "biz-1", "sale-9", "OJL7OW3J59", new BigDecimal("150.00"));

        assertThat(txn).contains("OJL7OW3J59");
        assertThat(row.getStatus()).isEqualTo(InboundTillPaymentStatuses.LINKED);
        assertThat(row.getLinkedSaleId()).isEqualTo("sale-9");
    }

    @Test
    void linkToSaleByReceipt_refusesReceiptAlreadyClaimed() {
        InboundTillPayment row = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        row.setStatus(InboundTillPaymentStatuses.LINKED);
        row.setLinkedClaimId("claim-1");
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                eq("biz-1"), eq("OJL7OW3J59"), eq(InboundTillPaymentStatuses.PENDING)))
                .thenReturn(Optional.empty());
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCase("biz-1", "OJL7OW3J59"))
                .thenReturn(Optional.of(row));

        Optional<String> txn = service.linkToSaleByReceipt(
                "biz-1", "sale-9", "OJL7OW3J59", new BigDecimal("150.00"));

        assertThat(txn).isEmpty();
        assertThat(row.getLinkedSaleId()).isNull();
        verify(inboundRepository, never()).save(any());
    }

    @Test
    void linkToSaleByReceipt_refusesAmountMismatch() {
        InboundTillPayment row = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                eq("biz-1"), eq("OJL7OW3J59"), eq(InboundTillPaymentStatuses.PENDING)))
                .thenReturn(Optional.of(row));

        Optional<String> txn = service.linkToSaleByReceipt(
                "biz-1", "sale-9", "OJL7OW3J59", new BigDecimal("2000.00"));

        assertThat(txn).isEmpty();
        assertThat(row.getStatus()).isEqualTo(InboundTillPaymentStatuses.PENDING);
        verify(inboundRepository, never()).save(any());
    }

    @Test
    void requireAmountMatchesIfKnown_throwsWhenSaleExceedsTillPayment() {
        InboundTillPayment row = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCase("biz-1", "OJL7OW3J59"))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() ->
                        service.requireAmountMatchesIfKnown(
                                "biz-1", "OJL7OW3J59", new BigDecimal("300.00")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("150");
    }

    @Test
    void requireAmountMatchesIfKnown_noopWhenNoInbound() {
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCase("biz-1", "MISSING"))
                .thenReturn(Optional.empty());

        service.requireAmountMatchesIfKnown("biz-1", "MISSING", new BigDecimal("300.00"));
    }

    @Test
    void linkToSaleByReceipt_doesNotRelinkAnotherSale() {
        InboundTillPayment row = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        row.setStatus(InboundTillPaymentStatuses.LINKED);
        row.setLinkedSaleId("sale-8");
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                eq("biz-1"), eq("OJL7OW3J59"), eq(InboundTillPaymentStatuses.PENDING)))
                .thenReturn(Optional.empty());
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCase("biz-1", "OJL7OW3J59"))
                .thenReturn(Optional.of(row));

        Optional<String> txn = service.linkToSaleByReceipt(
                "biz-1", "sale-9", "OJL7OW3J59", new BigDecimal("150.00"));

        assertThat(txn).isEmpty();
        assertThat(row.getLinkedSaleId()).isEqualTo("sale-8");
        verify(inboundRepository, never()).save(any());
    }

    @Test
    void linkToSaleByReceipt_sameSaleRelinkIsIdempotent() {
        InboundTillPayment row = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        row.setStatus(InboundTillPaymentStatuses.LINKED);
        row.setLinkedSaleId("sale-9");
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                eq("biz-1"), eq("OJL7OW3J59"), eq(InboundTillPaymentStatuses.PENDING)))
                .thenReturn(Optional.empty());
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCase("biz-1", "OJL7OW3J59"))
                .thenReturn(Optional.of(row));

        Optional<String> txn = service.linkToSaleByReceipt(
                "biz-1", "sale-9", "OJL7OW3J59", new BigDecimal("150.00"));

        assertThat(txn).contains("OJL7OW3J59");
        verify(inboundRepository, never()).save(any());
    }

    @Test
    void tryAutoApproveClaimByReceipt_approvesUniqueSubmittedClaim() {
        when(publicPaymentClaimServiceProvider.getIfAvailable()).thenReturn(publicPaymentClaimService);
        PublicPaymentClaim claim = new PublicPaymentClaim();
        claim.setId("claim-1");
        claim.setBusinessId("biz-1");
        claim.setStatus(CreditClaimStatuses.SUBMITTED);
        claim.setSubmittedReference("OJL7OW3J59");
        claim.setSubmittedAmount(new BigDecimal("150.00"));
        when(publicPaymentClaimRepository.findByBusinessIdAndStatusAndSubmittedReferenceIgnoreCase(
                "biz-1", CreditClaimStatuses.SUBMITTED, "OJL7OW3J59"))
                .thenReturn(List.of(claim));

        InboundTillPayment inbound = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        when(inboundRepository.save(any(InboundTillPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean ok = service.tryAutoApproveClaimByReceipt("biz-1", "OJL7OW3J59", inbound);

        assertThat(ok).isTrue();
        verify(publicPaymentClaimService).approve("biz-1", "claim-1", CreditClaimChannels.MPESA);
        assertThat(inbound.getStatus()).isEqualTo(InboundTillPaymentStatuses.LINKED);
        assertThat(inbound.getLinkedClaimId()).isEqualTo("claim-1");
    }

    @Test
    void tryAutoApproveClaimByReceipt_requiresPersistedInbound() {
        PublicPaymentClaim claim = new PublicPaymentClaim();
        claim.setId("claim-1");
        claim.setBusinessId("biz-1");
        claim.setStatus(CreditClaimStatuses.SUBMITTED);
        claim.setSubmittedReference("OJL7OW3J59");
        claim.setSubmittedAmount(new BigDecimal("150.00"));
        when(publicPaymentClaimRepository.findByBusinessIdAndStatusAndSubmittedReferenceIgnoreCase(
                "biz-1", CreditClaimStatuses.SUBMITTED, "OJL7OW3J59"))
                .thenReturn(List.of(claim));
        when(inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                eq("biz-1"), eq("OJL7OW3J59"), eq(InboundTillPaymentStatuses.PENDING)))
                .thenReturn(Optional.empty());

        // Claim-first path with a fabricated reference: no inbound row exists, so no auto-approve.
        boolean ok = service.tryAutoApproveClaimByReceipt("biz-1", "OJL7OW3J59", null);

        assertThat(ok).isFalse();
        verify(publicPaymentClaimService, never()).approve(any(), any(), any());
    }

    @Test
    void tryAutoApproveClaimByReceipt_skipsWhenInboundAlreadyLinked() {
        PublicPaymentClaim claim = new PublicPaymentClaim();
        claim.setId("claim-2");
        claim.setBusinessId("biz-1");
        claim.setStatus(CreditClaimStatuses.SUBMITTED);
        claim.setSubmittedReference("OJL7OW3J59");
        claim.setSubmittedAmount(new BigDecimal("150.00"));
        when(publicPaymentClaimRepository.findByBusinessIdAndStatusAndSubmittedReferenceIgnoreCase(
                "biz-1", CreditClaimStatuses.SUBMITTED, "OJL7OW3J59"))
                .thenReturn(List.of(claim));

        InboundTillPayment inbound = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");
        inbound.setStatus(InboundTillPaymentStatuses.LINKED);
        inbound.setLinkedClaimId("claim-1");

        boolean ok = service.tryAutoApproveClaimByReceipt("biz-1", "OJL7OW3J59", inbound);

        assertThat(ok).isFalse();
        verify(publicPaymentClaimService, never()).approve(any(), any(), any());
    }

    @Test
    void tryAutoApproveClaimByReceipt_skipsAmountMismatch() {
        PublicPaymentClaim claim = new PublicPaymentClaim();
        claim.setId("claim-1");
        claim.setBusinessId("biz-1");
        claim.setStatus(CreditClaimStatuses.SUBMITTED);
        claim.setSubmittedReference("OJL7OW3J59");
        claim.setSubmittedAmount(new BigDecimal("100.00"));
        when(publicPaymentClaimRepository.findByBusinessIdAndStatusAndSubmittedReferenceIgnoreCase(
                "biz-1", CreditClaimStatuses.SUBMITTED, "OJL7OW3J59"))
                .thenReturn(List.of(claim));

        InboundTillPayment inbound = pending("in-1", "OJL7OW3J59", "254714282874", "150.00");

        boolean ok = service.tryAutoApproveClaimByReceipt("biz-1", "OJL7OW3J59", inbound);

        assertThat(ok).isFalse();
        verify(publicPaymentClaimService, never()).approve(any(), any(), any());
        assertThat(inbound.getStatus()).isEqualTo(InboundTillPaymentStatuses.PENDING);
    }

    @Test
    void tryAutoApproveClaimByReceipt_skipsAmbiguousClaims() {
        PublicPaymentClaim a = new PublicPaymentClaim();
        a.setId("c1");
        PublicPaymentClaim b = new PublicPaymentClaim();
        b.setId("c2");
        when(publicPaymentClaimRepository.findByBusinessIdAndStatusAndSubmittedReferenceIgnoreCase(
                "biz-1", CreditClaimStatuses.SUBMITTED, "OJL7OW3J59"))
                .thenReturn(List.of(a, b));

        boolean ok = service.tryAutoApproveClaimByReceipt("biz-1", "OJL7OW3J59", null);

        assertThat(ok).isFalse();
        verify(publicPaymentClaimService, never()).approve(any(), any(), any());
    }

    private static WebhookResult buygoods(
            String eventId,
            String receipt,
            String phone,
            String amount,
            String raw
    ) {
        return new WebhookResult(
                null,
                receipt,
                phone,
                new BigDecimal(amount),
                receipt,
                true,
                false,
                null,
                eventId,
                "buygoods_transaction_received",
                raw);
    }

    private static InboundTillPayment pending(String id, String receipt, String phone, String amount) {
        InboundTillPayment row = new InboundTillPayment();
        row.setId(id);
        row.setBusinessId("biz-1");
        row.setGatewayType(GatewayType.KOPOKOPO);
        row.setGatewayEventId("evt-" + id);
        row.setMpesaReceipt(receipt);
        row.setPhone(phone);
        row.setAmount(new BigDecimal(amount));
        row.setStatus(InboundTillPaymentStatuses.PENDING);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        return row;
    }
}
