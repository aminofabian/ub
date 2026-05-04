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
import zelisline.ub.credits.CreditClaimChannels;
import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.PublicPaymentClaim;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;

@Service
@RequiredArgsConstructor
public class PublicPaymentClaimService {

    private static final int MONEY_SCALE = 2;
    private static final int TOKEN_RANDOM_BYTES = 24;

    private final PublicPaymentClaimRepository publicPaymentClaimRepository;
    private final CreditAccountRepository creditAccountRepository;
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

    /** Idempotent: second approve with the same channel is a silent no-op (§14.8). */
    @Transactional
    public void approve(String businessId, String claimId, String channel) {
        if (!CreditClaimChannels.isValid(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel must be 'cash' or 'mpesa'");
        }
        PublicPaymentClaim row = publicPaymentClaimRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found"));
        assertBusiness(row, businessId);
        if (CreditClaimStatuses.APPROVED.equals(row.getStatus())) {
            return;
        }
        if (CreditClaimStatuses.REJECTED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rejected claims cannot be approved");
        }
        if (!CreditClaimStatuses.SUBMITTED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim cannot be approved in this state");
        }
        if (row.getSubmittedAmount() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim has no submitted amount");
        }
        BigDecimal pay = row.getSubmittedAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        creditSaleDebtService.applyInboundArPayment(businessId, row.getCreditAccountId(), pay);
        String memo = "Public payment claim " + claimId + " (" + channel + ")";
        String je = CreditClaimChannels.CASH.equals(channel)
                ? creditsJournalService.postInboundCashTowardAr(businessId, pay, claimId, memo)
                : creditsJournalService.postInboundMpesaTowardAr(businessId, pay, claimId, memo);
        row.setStatus(CreditClaimStatuses.APPROVED);
        row.setApprovedJournalId(je);
        row.setUpdatedAt(Instant.now());
        publicPaymentClaimRepository.save(row);
    }

    /** Idempotent: second reject is a silent no-op. */
    @Transactional
    public void reject(String businessId, String claimId, String reasonRaw) {
        PublicPaymentClaim row = publicPaymentClaimRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found"));
        assertBusiness(row, businessId);
        if (CreditClaimStatuses.REJECTED.equals(row.getStatus())) {
            return;
        }
        if (CreditClaimStatuses.APPROVED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approved claims cannot be rejected");
        }
        if (!CreditClaimStatuses.SUBMITTED.equals(row.getStatus())
                && !CreditClaimStatuses.ISSUED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim cannot be rejected in this state");
        }
        row.setStatus(CreditClaimStatuses.REJECTED);
        row.setRejectionReason(trimOrNullLong(reasonRaw, 500));
        row.setUpdatedAt(Instant.now());
        publicPaymentClaimRepository.save(row);
    }

    private static void assertBusiness(PublicPaymentClaim row, String businessId) {
        if (!businessId.equals(row.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found");
        }
    }

    private static String trimOrNull(String raw) {
        return trimOrNullLong(raw, 128);
    }

    private static String trimOrNullLong(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        int cap = Math.min(maxLen, t.length());
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
