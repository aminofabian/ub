package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CustomerPhoneNormalizer;
import zelisline.ub.credits.domain.PublicPaymentClaim;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;

@Service
@RequiredArgsConstructor
public class PublicPaymentClaimService {

    private static final int MONEY_SCALE = 2;
    private static final int TOKEN_RANDOM_BYTES = 24;

    private final PublicPaymentClaimRepository publicPaymentClaimRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CustomerRepository customerRepository;
    private final CreditSaleDebtService creditSaleDebtService;
    private final CreditsJournalService creditsJournalService;

    @Transactional(readOnly = true)
    public List<PublicPaymentClaim> listSubmitted(String businessId) {
        return publicPaymentClaimRepository.findByBusinessIdAndStatusOrderByCreatedAtAsc(
                businessId, CreditClaimStatuses.SUBMITTED);
    }

    @Transactional
    public IssuedClaimToken issueClaim(String businessId, String customerId) {
        CreditAccount account = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found"));
        SecureRandom rnd = new SecureRandom();
        byte[] rawTok = new byte[TOKEN_RANDOM_BYTES];
        rnd.nextBytes(rawTok);
        String plainToken = HexFormat.of().formatHex(rawTok);
        String hash = sha256Hex(plainToken).toLowerCase();

        PublicPaymentClaim row = new PublicPaymentClaim();
        row.setBusinessId(businessId);
        row.setCreditAccountId(account.getId());
        row.setTokenHash(hash);
        row.setStatus(CreditClaimStatuses.ISSUED);
        publicPaymentClaimRepository.save(row);
        return new IssuedClaimToken(row.getId(), plainToken);
    }

    @Transactional
    public void submitByTokenPlain(String plainToken, BigDecimal amount, String referenceRaw) {
        String hash = sha256Hex(plainToken).toLowerCase();
        PublicPaymentClaim row = publicPaymentClaimRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown claim"));
        applySubmission(row, amount, referenceRaw);
    }

    /**
     * Public fallback path where caller identifies the customer by business + phone instead of plaintext token.
     * Uses the latest claim in ISSUED/SUBMITTED for that customer account.
     */
    @Transactional
    public void submitByBusinessAndPhone(String businessId, String phoneRaw, BigDecimal amount, String referenceRaw) {
        String normalized = CustomerPhoneNormalizer.normalize(phoneRaw);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone search requires digits");
        }
        var matches = customerRepository.findByBusinessIdAndPhoneNormalized(
                businessId, normalized, org.springframework.data.domain.PageRequest.of(0, 2));
        if (matches.getContent().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        if (matches.getContent().size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone matches multiple customers");
        }
        String customerId = matches.getContent().getFirst().getId();
        CreditAccount account = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found"));
        PublicPaymentClaim row = publicPaymentClaimRepository
                .findFirstByBusinessIdAndCreditAccountIdAndStatusInOrderByCreatedAtDesc(
                        businessId,
                        account.getId(),
                        List.of(CreditClaimStatuses.ISSUED, CreditClaimStatuses.SUBMITTED))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active claim for this customer"));
        applySubmission(row, amount, referenceRaw);
    }

    private void applySubmission(PublicPaymentClaim row, BigDecimal amount, String referenceRaw) {
        BigDecimal amt = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }
        if (!CreditClaimStatuses.ISSUED.equals(row.getStatus())
                && !CreditClaimStatuses.SUBMITTED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim is not awaiting submission");
        }
        row.setSubmittedAmount(amt);
        row.setSubmittedReference(trimOrNull(referenceRaw));
        row.setStatus(CreditClaimStatuses.SUBMITTED);
        row.setUpdatedAt(Instant.now());
        publicPaymentClaimRepository.save(row);
    }

    /** Idempotent: second approve is a silent no-op. */
    @Transactional
    public void approve(String businessId, String claimId) {
        PublicPaymentClaim row = publicPaymentClaimRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found"));
        assertBusiness(row, businessId);
        if (CreditClaimStatuses.APPROVED.equals(row.getStatus())) {
            return;
        }
        if (!CreditClaimStatuses.SUBMITTED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim cannot be approved in this state");
        }
        if (row.getSubmittedAmount() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim has no submitted amount");
        }
        BigDecimal pay = row.getSubmittedAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        creditSaleDebtService.applyInboundArPayment(businessId, row.getCreditAccountId(), pay);
        String je = creditsJournalService.postInboundMpesaTowardAr(
                businessId, pay, claimId, "Public payment claim " + claimId);
        row.setStatus(CreditClaimStatuses.APPROVED);
        row.setApprovedJournalId(je);
        row.setUpdatedAt(Instant.now());
        publicPaymentClaimRepository.save(row);
    }

    private static void assertBusiness(PublicPaymentClaim row, String businessId) {
        if (!businessId.equals(row.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found");
        }
    }

    private static String trimOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        int cap = Math.min(128, t.length());
        return t.substring(0, cap);
    }

    public record IssuedClaimToken(String claimId, String plaintextToken) {
    }

    private static String sha256Hex(String utf8Source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(utf8Source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
