package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.CreditClaimChannels;
import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.application.MpesaPayerIdentityService;
import zelisline.ub.credits.application.PublicPaymentClaimService;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.PublicPaymentClaim;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.InboundTillPayment;
import zelisline.ub.payments.domain.InboundTillPaymentStatuses;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.repository.InboundTillPaymentRepository;

/**
 * Persists unmatched Buy Goods webhooks and late-binds them to till-awaits, sales, or
 * exact-reference payment claims — without guessing credit accounts by phone.
 */
@Service
@RequiredArgsConstructor
public class InboundTillPaymentService {

    private static final Logger log = LoggerFactory.getLogger(InboundTillPaymentService.class);

    static final Duration MATCH_WINDOW = Duration.ofMinutes(10);

    private final InboundTillPaymentRepository inboundRepository;
    private final PublicPaymentClaimRepository publicPaymentClaimRepository;
    private final ObjectProvider<PublicPaymentClaimService> publicPaymentClaimService;
    private final ObjectMapper objectMapper;
    private final MpesaPayerIdentityService mpesaPayerIdentityService;

    /**
     * Idempotently store a successful unmatched buygoods webhook as PENDING.
     * Returns empty when the event/receipt was already stored.
     */
    @Transactional
    public Optional<InboundTillPayment> persistUnmatchedBuygoods(String businessId, WebhookResult parsed) {
        if (parsed == null || !parsed.success() || parsed.amount() == null) {
            return Optional.empty();
        }
        if (parsed.topic() == null
                || !parsed.topic().equalsIgnoreCase("buygoods_transaction_received")) {
            return Optional.empty();
        }

        String eventId = resolveEventId(parsed);
        if (eventId == null) {
            log.warn("Buygoods webhook missing event id — not persisting business={}", businessId);
            return Optional.empty();
        }
        if (inboundRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)) {
            Optional<InboundTillPayment> existing = inboundRepository
                    .findByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId);
            existing.ifPresent(row -> ensurePayerLinked(businessId, row, parsed));
            return existing;
        }

        String txnReceipt = trimOrNull(parsed.gatewayTransactionId());
        final String receipt = txnReceipt != null ? txnReceipt : trimOrNull(parsed.reference());
        if (receipt != null) {
            Optional<InboundTillPayment> byReceipt = inboundRepository
                    .findFirstByBusinessIdAndMpesaReceiptIgnoreCase(businessId, receipt);
            if (byReceipt.isPresent()) {
                byReceipt.ifPresent(row -> ensurePayerLinked(businessId, row, parsed));
                return byReceipt;
            }
        }

        InboundTillPayment row = new InboundTillPayment();
        row.setBusinessId(businessId);
        row.setGatewayType(GatewayType.KOPOKOPO);
        row.setGatewayEventId(eventId);
        row.setMpesaReceipt(receipt);
        row.setPhone(parsed.phoneIsMasked() ? null : StkPhoneNormalizer.normalize(parsed.phoneNumber()));
        row.setPayerFirstName(parsed.firstName());
        row.setPayerLastName(parsed.lastName());
        row.setMaskedMsisdn(parsed.maskedPhone());
        row.setAmount(parsed.amount().setScale(2, RoundingMode.HALF_UP));
        row.setTillNumber(extractTillNumber(parsed.rawPayload()));
        row.setRawPayload(parsed.rawPayload());
        row.setStatus(InboundTillPaymentStatuses.PENDING);
        try {
            Optional<Customer> payer = mpesaPayerIdentityService.resolveFromWebhook(businessId, parsed);
            payer.ifPresent(c -> row.setLinkedCustomerId(c.getId()));
            return Optional.of(inboundRepository.save(row));
        } catch (DataIntegrityViolationException e) {
            log.info("Inbound till payment duplicate ignored eventId={} business={}", eventId, businessId);
            return inboundRepository.findByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)
                    .or(() -> receipt == null
                            ? Optional.empty()
                            : inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCase(
                                    businessId, receipt));
        }
    }

    /**
     * Find a single clear PENDING inbound match for a newly opened till-await (race fix).
     */
    @Transactional(readOnly = true)
    public Optional<InboundTillPayment> findClearPendingMatch(
            String businessId,
            BigDecimal amount,
            String phoneNumber
    ) {
        if (amount == null) {
            return Optional.empty();
        }
        Instant since = Instant.now().minus(MATCH_WINDOW);
        List<InboundTillPayment> pending = inboundRepository
                .findByBusinessIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        businessId, InboundTillPaymentStatuses.PENDING, since);
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        List<InboundTillPayment> amountMatches = pending.stream()
                .filter(p -> amountsClose(p.getAmount(), scaled))
                .toList();
        if (amountMatches.isEmpty()) {
            return Optional.empty();
        }

        String phone = StkPhoneNormalizer.normalize(phoneNumber);
        if (phone != null) {
            List<InboundTillPayment> phoneMatches = amountMatches.stream()
                    .filter(p -> phone.equals(StkPhoneNormalizer.normalize(p.getPhone())))
                    .toList();
            if (phoneMatches.size() == 1) {
                return Optional.of(phoneMatches.get(0));
            }
            if (phoneMatches.size() > 1) {
                log.info("Inbound till: multiple phone+amount matches — using most recent id={}",
                        phoneMatches.get(0).getId());
                return Optional.of(phoneMatches.get(0));
            }
        }

        if (amountMatches.size() == 1) {
            return Optional.of(amountMatches.get(0));
        }

        log.warn("Inbound till: ambiguous amount match business={} amount={} candidates={} — skip",
                businessId, scaled, amountMatches.size());
        return Optional.empty();
    }

    @Transactional
    public void markLinkedToPush(InboundTillPayment inbound, String pushId) {
        if (inbound == null || pushId == null || pushId.isBlank()) {
            return;
        }
        if (InboundTillPaymentStatuses.LINKED.equals(inbound.getStatus())
                && pushId.equals(inbound.getLinkedPushId())) {
            return;
        }
        inbound.setStatus(InboundTillPaymentStatuses.LINKED);
        inbound.setLinkedPushId(pushId);
        inboundRepository.save(inbound);
    }

    /**
     * When a SUCCESS push settles, attach any PENDING inbound that shares the M-Pesa receipt.
     */
    @Transactional
    public void linkPendingByReceiptToPush(String businessId, String mpesaReceipt, String pushId) {
        if (mpesaReceipt == null || mpesaReceipt.isBlank() || pushId == null) {
            return;
        }
        inboundRepository
                .findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                        businessId, mpesaReceipt.trim(), InboundTillPaymentStatuses.PENDING)
                .ifPresent(row -> markLinkedToPush(row, pushId));
    }

    /**
     * When an inbound till row exists for {@code receipt}, require {@code paymentAmount} to match
     * (±1.00). Used by sale create/adjust so inflated cart totals cannot settle against a smaller
     * verified M-Pesa payment.
     */
    public void requireAmountMatchesIfKnown(
            String businessId,
            String receiptRaw,
            BigDecimal paymentAmount
    ) {
        if (receiptRaw == null || receiptRaw.isBlank() || paymentAmount == null) {
            return;
        }
        String receipt = receiptRaw.trim();
        Optional<InboundTillPayment> row = inboundRepository
                .findFirstByBusinessIdAndMpesaReceiptIgnoreCase(businessId, receipt);
        if (row.isEmpty()) {
            return;
        }
        InboundTillPayment inbound = row.get();
        if (!amountsClose(paymentAmount, inbound.getAmount())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Sale total does not match M-Pesa till payment of "
                            + inbound.getAmount().toPlainString());
        }
    }

    /**
     * Stamp sale linkage for a PENDING (or already receipt-known) inbound; returns the receipt
     * to use as {@code sale_payments.gateway_txn_id} when the binding is clean.
     *
     * <p>Refuses to re-link a receipt already bound to a different sale or to an approved claim,
     * and refuses to link when the sale amount does not match the inbound amount — those cases are
     * logged and left for manual review instead of silently mis-binding. Linking the same sale
     * again (payment re-adjust) is idempotent.
     */
    @Transactional
    public Optional<String> linkToSaleByReceipt(
            String businessId,
            String saleId,
            String receiptRaw,
            BigDecimal paymentAmount
    ) {
        if (receiptRaw == null || receiptRaw.isBlank() || saleId == null) {
            return Optional.empty();
        }
        String receipt = receiptRaw.trim();
        Optional<InboundTillPayment> row = inboundRepository
                .findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                        businessId, receipt, InboundTillPaymentStatuses.PENDING);
        if (row.isEmpty()) {
            row = inboundRepository.findFirstByBusinessIdAndMpesaReceiptIgnoreCase(businessId, receipt);
        }
        if (row.isEmpty()) {
            return Optional.empty();
        }
        InboundTillPayment inbound = row.get();

        if (inbound.getLinkedClaimId() != null) {
            log.warn("Inbound till: receipt={} already linked to claim {} — not linking sale={}",
                    receipt, inbound.getLinkedClaimId(), saleId);
            return Optional.empty();
        }
        if (inbound.getLinkedSaleId() != null && !inbound.getLinkedSaleId().equals(saleId)) {
            log.warn("Inbound till: receipt={} already linked to sale={} — not relinking sale={}",
                    receipt, inbound.getLinkedSaleId(), saleId);
            return Optional.empty();
        }
        if (paymentAmount != null && !amountsClose(paymentAmount, inbound.getAmount())) {
            log.warn("Inbound till: amount mismatch for sale link receipt={} saleAmount={} inboundAmount={} — not linking",
                    receipt, paymentAmount, inbound.getAmount());
            return Optional.empty();
        }

        if (inbound.getLinkedSaleId() == null || !inbound.getLinkedSaleId().equals(saleId)) {
            inbound.setLinkedSaleId(saleId);
            if (!InboundTillPaymentStatuses.LINKED.equals(inbound.getStatus())) {
                inbound.setStatus(InboundTillPaymentStatuses.LINKED);
            }
            inboundRepository.save(inbound);
        }
        if (inbound.getLinkedCustomerId() != null) {
            mpesaPayerIdentityService.attachToSaleIfUnassigned(
                    businessId, saleId, inbound.getLinkedCustomerId());
        }
        String txn = inbound.getMpesaReceipt() != null ? inbound.getMpesaReceipt() : receipt;
        return Optional.of(txn);
    }

    /**
     * Auto-approve a SUBMITTED payment claim whose submitted_reference equals the M-Pesa receipt —
     * but only when the payment is real and unbound: a PENDING inbound row exists for the receipt,
     * the receipt is not already linked to a sale or another claim, and the claimed amount matches
     * the inbound amount. Otherwise returns false and the claim stays SUBMITTED for admin review.
     */
    @Transactional
    public boolean tryAutoApproveClaimByReceipt(
            String businessId,
            String mpesaReceipt,
            InboundTillPayment inboundOrNull
    ) {
        if (mpesaReceipt == null || mpesaReceipt.isBlank()) {
            return false;
        }
        String receipt = mpesaReceipt.trim();
        List<PublicPaymentClaim> claims = publicPaymentClaimRepository
                .findByBusinessIdAndStatusAndSubmittedReferenceIgnoreCase(
                        businessId, CreditClaimStatuses.SUBMITTED, receipt);
        if (claims.isEmpty()) {
            return false;
        }
        if (claims.size() > 1) {
            log.warn("Inbound till: multiple SUBMITTED claims for receipt={} business={} — skip auto-approve",
                    receipt, businessId);
            return false;
        }
        PublicPaymentClaim claim = claims.get(0);

        InboundTillPayment inbound = inboundOrNull != null
                ? inboundOrNull
                : inboundRepository
                        .findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
                                businessId, receipt, InboundTillPaymentStatuses.PENDING)
                        .orElse(null);
        // No proof the payment hit the till: never auto-approve a fabricated reference.
        if (inbound == null || !InboundTillPaymentStatuses.PENDING.equals(inbound.getStatus())) {
            log.info("Inbound till: no PENDING inbound for receipt={} — claim {} left for manual review",
                    receipt, claim.getId());
            return false;
        }
        if (inbound.getLinkedClaimId() != null || inbound.getLinkedSaleId() != null) {
            log.warn("Inbound till: receipt={} already linked — skip auto-approve of claim {}",
                    receipt, claim.getId());
            return false;
        }
        BigDecimal claimAmount = claim.getSubmittedAmount();
        if (claimAmount == null || !amountsClose(claimAmount, inbound.getAmount())) {
            log.warn("Inbound till: claim {} amount {} != inbound {} — skip auto-approve",
                    claim.getId(), claimAmount, inbound.getAmount());
            return false;
        }

        PublicPaymentClaimService claimsService = publicPaymentClaimService.getIfAvailable();
        if (claimsService == null) {
            return false;
        }
        try {
            // Bind the receipt to the claim before the financial effect, so a failed approval
            // cannot leave the payment PENDING-and-available; revert below on failure.
            inbound.setStatus(InboundTillPaymentStatuses.LINKED);
            inbound.setLinkedClaimId(claim.getId());
            inboundRepository.save(inbound);

            claimsService.approve(businessId, claim.getId(), CreditClaimChannels.MPESA);
            log.info("Auto-approved payment claim {} from buygoods receipt={} business={}",
                    claim.getId(), receipt, businessId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to auto-approve claim {} for receipt={}: {}",
                    claim.getId(), receipt, e.getMessage());
            inbound.setStatus(InboundTillPaymentStatuses.PENDING);
            inbound.setLinkedClaimId(null);
            inboundRepository.save(inbound);
            return false;
        }
    }

    private String extractTillNumber(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode resource = root.path("event").path("resource");
            if (resource.isMissingNode() || resource.isNull()) {
                resource = root.path("attributes");
            }
            if (resource.isMissingNode() || resource.isNull()) {
                resource = root.path("data").path("attributes");
            }
            if (resource.isMissingNode() || resource.isNull()) {
                return null;
            }
            if (resource.hasNonNull("till_number")) {
                String till = resource.get("till_number").asText();
                return till != null && !till.isBlank() ? till.trim() : null;
            }
        } catch (Exception e) {
            log.debug("Could not extract till_number from buygoods payload: {}", e.getMessage());
        }
        return null;
    }

    private void ensurePayerLinked(String businessId, InboundTillPayment row, WebhookResult parsed) {
        if (row.getLinkedCustomerId() != null && !row.getLinkedCustomerId().isBlank()) {
            return;
        }
        mpesaPayerIdentityService.resolveFromWebhook(businessId, parsed).ifPresent(c -> {
            row.setLinkedCustomerId(c.getId());
            if (row.getPayerFirstName() == null) {
                row.setPayerFirstName(parsed.firstName());
            }
            if (row.getPayerLastName() == null) {
                row.setPayerLastName(parsed.lastName());
            }
            if (row.getMaskedMsisdn() == null) {
                row.setMaskedMsisdn(parsed.maskedPhone());
            }
            inboundRepository.save(row);
        });
    }

    private static String resolveEventId(WebhookResult parsed) {
        if (parsed.webhookEventId() != null && !parsed.webhookEventId().isBlank()) {
            return parsed.webhookEventId().trim();
        }
        if (parsed.gatewayTransactionId() != null && !parsed.gatewayTransactionId().isBlank()) {
            return parsed.gatewayTransactionId().trim();
        }
        return null;
    }

    private static String trimOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static boolean amountsClose(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.subtract(b).abs().compareTo(new BigDecimal("1.00")) <= 0;
    }
}
