package zelisline.ub.pharmacy.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.ClaimRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.ClaimResponse;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.ClaimStatusRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.SchemeRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.SchemeResponse;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordLine;
import zelisline.ub.pharmacy.domain.PharmacyScheme;
import zelisline.ub.pharmacy.domain.PharmacySchemeClaim;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.PharmacySchemeClaimRepository;
import zelisline.ub.pharmacy.repository.PharmacySchemeRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.repository.SaleRepository;

/**
 * Medical-scheme claims kept by hand (ADR 0033, Phase 3): which scheme owes what for which
 * dispensing record, and where each claim stands. Kiosk holds no tariff, sends nothing to a
 * scheme and moves no money: how the scheme's share was taken at the till (for example on a
 * credit account in the scheme's name) is the shop's own bookkeeping.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemeClaimService {

    private static final int MAX_ROWS = 500;

    private final PharmacySchemeRepository schemeRepository;
    private final PharmacySchemeClaimRepository claimRepository;
    private final DispensingRecordRepository recordRepository;
    private final DispensingRecordLineRepository lineRepository;
    private final SaleRepository saleRepository;
    private final zelisline.ub.credits.repository.CustomerRepository customerRepository;
    private final zelisline.ub.credits.repository.CreditAccountRepository creditAccountRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    // ── Schemes ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SchemeResponse> schemes(String businessId) {
        Map<String, BigDecimal> outstanding = new HashMap<>();
        Map<String, Integer> open = new HashMap<>();
        for (PharmacySchemeClaim c : claimRepository
                .findByBusinessIdOrderByCreatedAtDescIdDesc(businessId, PageRequest.of(0, 5000))) {
            if (isOutstanding(c.getStatus())) {
                outstanding.merge(c.getSchemeId(), c.getAmountClaimed().subtract(c.getAmountPaid()), BigDecimal::add);
                open.merge(c.getSchemeId(), 1, Integer::sum);
            }
        }
        List<SchemeResponse> out = new ArrayList<>();
        for (PharmacyScheme s : schemeRepository.findByBusinessIdOrderByNameAsc(businessId)) {
            out.add(new SchemeResponse(
                    s.getId(), s.getName(), s.getContact(), s.getPaymentTermsDays(), s.isActive(),
                    outstanding.getOrDefault(s.getId(), BigDecimal.ZERO), open.getOrDefault(s.getId(), 0),
                    s.getCreditCustomerId(), tabBalance(businessId, s)));
        }
        return out;
    }

    @Transactional
    public SchemeResponse saveScheme(String businessId, String schemeId, SchemeRequest req) {
        PharmacyScheme s;
        if (schemeId == null) {
            s = new PharmacyScheme();
            s.setBusinessId(businessId);
        } else {
            s = schemeRepository.findByIdAndBusinessId(schemeId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found"));
        }
        s.setName(req.name().strip());
        s.setContact(req.contact() == null || req.contact().isBlank() ? null : req.contact().strip());
        s.setPaymentTermsDays(req.paymentTermsDays());
        if (req.active() != null) {
            s.setActive(req.active());
        }
        ensureCreditAccount(businessId, s);
        schemeRepository.save(s);
        return new SchemeResponse(
                s.getId(), s.getName(), s.getContact(), s.getPaymentTermsDays(), s.isActive(), BigDecimal.ZERO, 0,
                s.getCreditCustomerId(), tabBalance(businessId, s));
    }

    /**
     * Gives the scheme a customer record with a credit account, once. The till then charges the
     * scheme's share of a sale to that customer with the ordinary "customer credit" payment, and
     * the existing credit ledger carries what the scheme owes. The account never gets reminders.
     */
    private void ensureCreditAccount(String businessId, PharmacyScheme s) {
        if (s.getCreditCustomerId() != null
                && customerRepository.findById(s.getCreditCustomerId())
                        .filter(c -> businessId.equals(c.getBusinessId()))
                        .isPresent()) {
            return;
        }
        zelisline.ub.credits.domain.Customer customer = new zelisline.ub.credits.domain.Customer();
        customer.setBusinessId(businessId);
        customer.setName(s.getName() + " (medical scheme)");
        customerRepository.save(customer);
        zelisline.ub.credits.domain.CreditAccount account = new zelisline.ub.credits.domain.CreditAccount();
        account.setBusinessId(businessId);
        account.setCustomerId(customer.getId());
        account.setRemindersOptOut(true);
        creditAccountRepository.save(account);
        s.setCreditCustomerId(customer.getId());
    }

    private BigDecimal tabBalance(String businessId, PharmacyScheme s) {
        if (s.getCreditCustomerId() == null) {
            return null;
        }
        return creditAccountRepository.findByCustomerIdAndBusinessId(s.getCreditCustomerId(), businessId)
                .map(zelisline.ub.credits.domain.CreditAccount::getBalanceOwed)
                .orElse(null);
    }

    // ── Claims ────────────────────────────────────────────────────────

    @Transactional
    public ClaimResponse createClaim(String businessId, ClaimRequest req, String userId, String onlyBranchId) {
        PharmacyScheme scheme = schemeRepository.findByIdAndBusinessId(req.schemeId().strip(), businessId)
                .filter(PharmacyScheme::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a scheme"));
        DispensingRecord record = recordRepository.findByIdAndBusinessId(req.recordId().strip(), businessId)
                .filter(r -> onlyBranchId == null || onlyBranchId.equals(r.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found"));
        if (record.getVoidedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This sale was voided, so there is nothing to claim");
        }
        for (PharmacySchemeClaim existing : claimRepository.findByBusinessIdAndRecordId(businessId, record.getId())) {
            if (existing.getSchemeId().equals(scheme.getId()) && !PharmacySchemeClaim.REJECTED.equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This record already has a claim with " + scheme.getName());
            }
        }
        BigDecimal amount = req.amountClaimed().setScale(2, RoundingMode.HALF_UP);
        Sale sale = saleRepository.findById(record.getSaleId())
                .filter(s -> businessId.equals(s.getBusinessId()))
                .orElse(null);
        if (sale != null && sale.getGrandTotal() != null && amount.compareTo(sale.getGrandTotal()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The claim is more than the sale");
        }
        PharmacySchemeClaim c = new PharmacySchemeClaim();
        c.setBusinessId(businessId);
        c.setSchemeId(scheme.getId());
        c.setRecordId(record.getId());
        c.setSaleId(record.getSaleId());
        c.setBranchId(record.getBranchId());
        c.setMemberNo(req.memberNo().strip());
        c.setAuthorisationCode(
                req.authorisationCode() == null || req.authorisationCode().isBlank() ? null : req.authorisationCode().strip());
        c.setAmountClaimed(amount);
        c.setCreatedBy(userId);
        claimRepository.save(c);
        audit(businessId, userId, AuditEventTypes.PHARMACY_CLAIM_SAVED, c.getId(), c.getStatus());
        return toResponses(businessId, List.of(c)).getFirst();
    }

    /** What a claim with no dispensing record shows where the medicines would be. */
    public static final String VISIT_CLAIM_LABEL = "Clinic visit";

    /**
     * A claim for a paid clinic visit (ADR 0034, decision 13). It stands on the sale; when the
     * visit also dispensed medicines the claim points at that record too. One open claim per
     * scheme per sale.
     */
    @Transactional
    public ClaimResponse createClaimForSale(
            String businessId, String schemeId, String saleId, String branchId, String memberNo,
            String authorisationCode, BigDecimal amountClaimed, String userId) {
        PharmacyScheme scheme = schemeRepository.findByIdAndBusinessId(schemeId.strip(), businessId)
                .filter(PharmacyScheme::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a scheme"));
        Sale sale = saleRepository.findById(saleId)
                .filter(x -> businessId.equals(x.getBusinessId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        for (PharmacySchemeClaim existing : claimRepository.findByBusinessIdAndSaleId(businessId, sale.getId())) {
            if (existing.getSchemeId().equals(scheme.getId()) && !PharmacySchemeClaim.REJECTED.equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This visit already has a claim with " + scheme.getName());
            }
        }
        BigDecimal amount = amountClaimed.setScale(2, RoundingMode.HALF_UP);
        if (sale.getGrandTotal() != null && amount.compareTo(sale.getGrandTotal()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The claim is more than the bill");
        }
        String recordId = recordRepository.findByBusinessIdAndSaleId(businessId, sale.getId()).stream()
                .filter(r -> r.getVoidedAt() == null)
                .map(DispensingRecord::getId)
                .findFirst()
                .orElse(null);
        PharmacySchemeClaim c = new PharmacySchemeClaim();
        c.setBusinessId(businessId);
        c.setSchemeId(scheme.getId());
        c.setRecordId(recordId);
        c.setSaleId(sale.getId());
        c.setBranchId(branchId);
        c.setMemberNo(memberNo.strip());
        c.setAuthorisationCode(authorisationCode == null || authorisationCode.isBlank() ? null : authorisationCode.strip());
        c.setAmountClaimed(amount);
        c.setCreatedBy(userId);
        claimRepository.save(c);
        audit(businessId, userId, AuditEventTypes.PHARMACY_CLAIM_SAVED, c.getId(), c.getStatus());
        return toResponses(businessId, List.of(c)).getFirst();
    }

    /**
     * Moves a claim on: submitted to the scheme, paid (in full or in part, by the total paid so
     * far), or rejected. A paid or rejected claim can still be corrected by setting it again.
     */
    @Transactional
    public ClaimResponse setStatus(String businessId, String claimId, ClaimStatusRequest req, String userId) {
        PharmacySchemeClaim c = claimRepository.findByIdAndBusinessId(claimId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found"));
        String note = req.note() == null || req.note().isBlank() ? null : req.note().strip();
        switch (req.status()) {
            case PharmacySchemeClaim.SUBMITTED -> {
                c.setStatus(PharmacySchemeClaim.SUBMITTED);
                if (c.getSubmittedAt() == null) {
                    c.setSubmittedAt(Instant.now());
                }
                c.setSettledAt(null);
                c.setAmountPaid(BigDecimal.ZERO);
            }
            case PharmacySchemeClaim.PAID -> {
                if (req.amountPaid() == null || req.amountPaid().signum() <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter how much the scheme has paid");
                }
                BigDecimal paid = req.amountPaid().setScale(2, RoundingMode.HALF_UP);
                if (paid.compareTo(c.getAmountClaimed()) > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is more than was claimed");
                }
                c.setAmountPaid(paid);
                boolean full = paid.compareTo(c.getAmountClaimed()) == 0;
                c.setStatus(full ? PharmacySchemeClaim.PAID : PharmacySchemeClaim.PART_PAID);
                c.setSettledAt(full ? Instant.now() : null);
            }
            case PharmacySchemeClaim.REJECTED -> {
                if (note == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the scheme rejected it");
                }
                c.setStatus(PharmacySchemeClaim.REJECTED);
                c.setSettledAt(Instant.now());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        c.setStatusNote(note);
        claimRepository.save(c);
        audit(businessId, userId, AuditEventTypes.PHARMACY_CLAIM_SAVED, c.getId(), c.getStatus());
        return toResponses(businessId, List.of(c)).getFirst();
    }

    /** Newest first, at most 500. Names patients, so the read is audited. */
    @Transactional(readOnly = true)
    public List<ClaimResponse> claims(String businessId, String onlyBranchId, String viewerUserId) {
        List<PharmacySchemeClaim> rows = claimRepository
                .findByBusinessIdOrderByCreatedAtDescIdDesc(businessId, PageRequest.of(0, MAX_ROWS)).stream()
                .filter(c -> onlyBranchId == null || onlyBranchId.equals(c.getBranchId()))
                .toList();
        List<ClaimResponse> out = toResponses(businessId, rows);
        if (!out.isEmpty()) {
            audit(businessId, viewerUserId, AuditEventTypes.PHARMACY_RECORD_VIEWED, null, String.valueOf(out.size()));
        }
        return out;
    }

    static boolean isOutstanding(String status) {
        return PharmacySchemeClaim.OPEN.equals(status)
                || PharmacySchemeClaim.SUBMITTED.equals(status)
                || PharmacySchemeClaim.PART_PAID.equals(status);
    }

    private List<ClaimResponse> toResponses(String businessId, List<PharmacySchemeClaim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        Set<String> recordIds = new LinkedHashSet<>();
        Set<String> saleIds = new LinkedHashSet<>();
        for (PharmacySchemeClaim c : claims) {
            if (c.getRecordId() != null) {
                recordIds.add(c.getRecordId());
            }
            saleIds.add(c.getSaleId());
        }
        Map<String, String> schemeNames = new HashMap<>();
        for (PharmacyScheme s : schemeRepository.findByBusinessIdOrderByNameAsc(businessId)) {
            schemeNames.put(s.getId(), s.getName());
        }
        Map<String, DispensingRecord> records = new HashMap<>();
        for (DispensingRecord r : recordIds.isEmpty()
                ? List.<DispensingRecord>of()
                : recordRepository.findByBusinessIdAndIdIn(businessId, recordIds)) {
            records.put(r.getId(), r);
        }
        Map<String, List<String>> medicines = new LinkedHashMap<>();
        for (DispensingRecordLine l : recordIds.isEmpty()
                ? List.<DispensingRecordLine>of()
                : lineRepository.findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(businessId, recordIds)) {
            medicines.computeIfAbsent(l.getRecordId(), k -> new ArrayList<>())
                    .add(l.getItemName() + " x " + l.getQuantity().stripTrailingZeros().toPlainString());
        }
        Map<String, Long> receiptNos = new HashMap<>();
        for (Sale s : saleRepository.findAllById(saleIds)) {
            if (businessId.equals(s.getBusinessId())) {
                receiptNos.put(s.getId(), s.getReceiptNo());
            }
        }
        Instant now = Instant.now();
        List<ClaimResponse> out = new ArrayList<>(claims.size());
        for (PharmacySchemeClaim c : claims) {
            DispensingRecord r = records.get(c.getRecordId());
            Instant until = c.getSettledAt() != null ? c.getSettledAt() : now;
            out.add(new ClaimResponse(
                    c.getId(), c.getSchemeId(), schemeNames.get(c.getSchemeId()), c.getRecordId(), c.getSaleId(),
                    receiptNos.get(c.getSaleId()), c.getBranchId(),
                    r == null ? null : r.getPatientName(),
                    r == null ? null : r.getPrescriberName(),
                    c.getRecordId() == null
                            ? VISIT_CLAIM_LABEL
                            : String.join(", ", medicines.getOrDefault(c.getRecordId(), List.of())),
                    c.getMemberNo(), c.getAuthorisationCode(), c.getAmountClaimed(), c.getAmountPaid(),
                    c.getStatus(), c.getStatusNote(), c.getCreatedAt(), c.getSubmittedAt(), c.getSettledAt(),
                    c.getCreatedAt() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(c.getCreatedAt(), until))));
        }
        return out;
    }

    /** Who, which claim, which status. Never the member number, the patient or the medicines. */
    private void audit(String businessId, String userId, String type, String claimId, String detail) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("detail", detail);
            var builder = auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, type, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(userId, AuditEventActorType.USER)
                    .source("pharmacy")
                    .newState(state);
            if (claimId != null) {
                builder.target("pharmacy_scheme_claim", claimId);
            }
            auditEventPublisher.publish(builder.build());
        } catch (Exception ex) {
            log.warn("Failed to audit scheme claim business={}: {}", businessId, ex.toString());
        }
    }
}
