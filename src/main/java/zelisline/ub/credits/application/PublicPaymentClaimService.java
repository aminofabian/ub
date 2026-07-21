package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.CreditClaimChannels;
import zelisline.ub.credits.CreditClaimSources;
import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.api.dto.PaymentClaimReviewRowResponse;
import zelisline.ub.credits.api.dto.ProposeTabClearanceRequest;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.PublicPaymentClaim;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
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
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditSaleDebtService creditSaleDebtService;
    private final CreditsJournalService creditsJournalService;
    private final CashierTabClearanceAccess cashierTabClearanceAccess;

    @Transactional(readOnly = true)
    public List<PublicPaymentClaim> listSubmitted(String businessId) {
        return publicPaymentClaimRepository.findByBusinessIdAndStatusOrderByCreatedAtAsc(
                businessId, CreditClaimStatuses.SUBMITTED);
    }

    @Transactional(readOnly = true)
    public List<PaymentClaimReviewRowResponse> listSubmittedForReview(String businessId) {
        List<PublicPaymentClaim> rows = listSubmitted(businessId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> accountIds = rows.stream().map(PublicPaymentClaim::getCreditAccountId).distinct().toList();
        Map<String, CreditAccount> accounts = new HashMap<>();
        for (CreditAccount acc : creditAccountRepository.findAllById(accountIds)) {
            accounts.put(acc.getId(), acc);
        }
        List<String> customerIds = accounts.values().stream().map(CreditAccount::getCustomerId).distinct().toList();
        Map<String, Customer> customers = new HashMap<>();
        for (Customer c : customerRepository.findAllById(customerIds)) {
            customers.put(c.getId(), c);
        }
        Map<String, String> primaryPhone = new HashMap<>();
        for (CustomerPhone p : customerPhoneRepository.findByCustomerIdIn(customerIds)) {
            if (p.isPrimary() || !primaryPhone.containsKey(p.getCustomerId())) {
                primaryPhone.put(p.getCustomerId(), p.getPhone());
            }
        }
        List<PaymentClaimReviewRowResponse> out = new ArrayList<>(rows.size());
        for (PublicPaymentClaim row : rows) {
            CreditAccount acc = accounts.get(row.getCreditAccountId());
            Customer customer = acc == null ? null : customers.get(acc.getCustomerId());
            String customerId = customer == null ? null : customer.getId();
            out.add(new PaymentClaimReviewRowResponse(
                    row.getId(),
                    row.getBusinessId(),
                    row.getCreditAccountId(),
                    customerId,
                    customer == null ? null : customer.getName(),
                    customerId == null ? null : primaryPhone.get(customerId),
                    row.getStatus(),
                    row.getSource() == null ? CreditClaimSources.PUBLIC : row.getSource(),
                    row.getProposedChannel(),
                    row.getSubmittedAmount(),
                    row.getSubmittedReference(),
                    row.getCreditNote(),
                    row.getSubmittedByUserId(),
                    row.getApprovedJournalId(),
                    row.getRejectionReason(),
                    row.getCreatedAt(),
                    row.getUpdatedAt()
            ));
        }
        return out;
    }

    @Transactional
    public String proposeCashierTabClearance(
            String businessId,
            ProposeTabClearanceRequest req,
            String actorUserId
    ) {
        cashierTabClearanceAccess.requireEnabled(businessId);
        if (!CreditClaimChannels.isValid(req.channel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel must be 'cash' or 'mpesa'");
        }
        CreditAccount account = creditAccountRepository
                .findByCustomerIdAndBusinessId(req.customerId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found"));
        BigDecimal owed = account.getBalanceOwed() == null ? BigDecimal.ZERO : account.getBalanceOwed();
        BigDecimal amount = req.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }
        if (amount.compareTo(owed) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Amount exceeds tab balance of " + owed.toPlainString()
            );
        }
        PublicPaymentClaim row = new PublicPaymentClaim();
        row.setBusinessId(businessId);
        row.setCreditAccountId(account.getId());
        // Staff claims are not redeemable via public token; store a unique opaque hash.
        row.setTokenHash(sha256Hex("cashier:" + UUID.randomUUID()).toLowerCase());
        row.setStatus(CreditClaimStatuses.SUBMITTED);
        row.setSource(CreditClaimSources.CASHIER);
        row.setProposedChannel(req.channel());
        row.setSubmittedByUserId(actorUserId);
        row.setSubmittedAmount(amount);
        row.setSubmittedReference(trimOrNull(req.reference()));
        row.setCreditNote("Till clearance — " + req.channel());
        publicPaymentClaimRepository.save(row);
        return row.getId();
    }

    /**
     * Customer on the public tab portal reports they already paid (e.g. M-Pesa to till).
     * Creates a submitted claim for admin review — no redeemable token.
     */
    @Transactional
    public String submitTabPortalClaim(
            String businessId,
            String customerId,
            BigDecimal amount,
            String referenceRaw
    ) {
        CreditAccount account = creditAccountRepository
                .findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found"));
        BigDecimal owed = account.getBalanceOwed() == null ? BigDecimal.ZERO : account.getBalanceOwed();
        BigDecimal amt = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }
        if (amt.compareTo(owed) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Amount exceeds tab balance of " + owed.toPlainString()
            );
        }
        PublicPaymentClaim row = new PublicPaymentClaim();
        row.setBusinessId(businessId);
        row.setCreditAccountId(account.getId());
        row.setTokenHash(sha256Hex("tab_portal:" + UUID.randomUUID()).toLowerCase());
        row.setStatus(CreditClaimStatuses.SUBMITTED);
        row.setSource(CreditClaimSources.TAB_PORTAL);
        row.setProposedChannel(CreditClaimChannels.MPESA);
        row.setSubmittedAmount(amt);
        row.setSubmittedReference(trimOrNull(referenceRaw));
        row.setCreditNote("Tab portal — reported payment");
        publicPaymentClaimRepository.save(row);
        return row.getId();
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
        row.setSource(CreditClaimSources.PUBLIC);
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
