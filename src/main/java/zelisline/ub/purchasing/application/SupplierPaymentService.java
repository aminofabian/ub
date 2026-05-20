package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.IdempotencyKey;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.ApAgingBuckets;
import zelisline.ub.purchasing.api.dto.ApAgingTotalsResponse;
import zelisline.ub.purchasing.api.dto.OpenSupplierInvoiceRow;
import zelisline.ub.purchasing.api.dto.PostSupplierPaymentAllocationLine;
import zelisline.ub.purchasing.api.dto.PostSupplierPaymentRequest;
import zelisline.ub.purchasing.api.dto.PostSupplierPaymentResponse;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierPayment;
import zelisline.ub.purchasing.domain.SupplierPaymentAllocation;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierPaymentService {

    private static final BigDecimal MONEY = new BigDecimal("0.01");

    private final SupplierPaymentRepository supplierPaymentRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierRepository supplierRepository;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public static String postPaymentRoute() {
        return "POST /api/v1/purchasing/supplier-payments";
    }

    @Transactional(readOnly = true)
    public ApAgingTotalsResponse apAging(String businessId, LocalDate asOf, String supplierIdFilter) {
        LocalDate day = asOf != null ? asOf : LocalDate.now(ZoneOffset.UTC);
        List<SupplierInvoice> invs = supplierInvoiceRepository.findByBusinessIdAndStatus(
                businessId, PurchasingConstants.INVOICE_POSTED);
        BigDecimal cur = BigDecimal.ZERO;
        BigDecimal d1 = BigDecimal.ZERO;
        BigDecimal d2 = BigDecimal.ZERO;
        BigDecimal d3 = BigDecimal.ZERO;
        BigDecimal d90 = BigDecimal.ZERO;
        for (SupplierInvoice inv : invs) {
            if (supplierIdFilter != null && !supplierIdFilter.isBlank()
                    && !supplierIdFilter.equals(inv.getSupplierId())) {
                continue;
            }
            BigDecimal open = openBalance(inv.getId(), inv.getGrandTotal());
            if (open.compareTo(MONEY) <= 0) {
                continue;
            }
            LocalDate due = inv.getDueDate() != null ? inv.getDueDate() : inv.getInvoiceDate();
            long past = day.toEpochDay() - due.toEpochDay();
            if (past <= 0) {
                cur = cur.add(open);
            } else if (past <= 30) {
                d1 = d1.add(open);
            } else if (past <= 60) {
                d2 = d2.add(open);
            } else if (past <= 90) {
                d3 = d3.add(open);
            } else {
                d90 = d90.add(open);
            }
        }
        BigDecimal totalOpen = cur.add(d1).add(d2).add(d3).add(d90).setScale(2, RoundingMode.HALF_UP);
        BigDecimal prepaySum = supplierRepository.sumPrepaymentBalanceByBusinessId(businessId)
                .setScale(2, RoundingMode.HALF_UP);
        return new ApAgingTotalsResponse(
                day,
                new ApAgingBuckets(
                        cur.setScale(2, RoundingMode.HALF_UP),
                        d1.setScale(2, RoundingMode.HALF_UP),
                        d2.setScale(2, RoundingMode.HALF_UP),
                        d3.setScale(2, RoundingMode.HALF_UP),
                        d90.setScale(2, RoundingMode.HALF_UP)),
                totalOpen,
                prepaySum);
    }

    @Transactional(readOnly = true)
    public List<OpenSupplierInvoiceRow> listOpenSupplierInvoices(String businessId, String supplierIdFilter) {
        List<SupplierInvoice> invs = supplierInvoiceRepository.findByBusinessIdAndStatus(
                businessId, PurchasingConstants.INVOICE_POSTED);
        List<OpenSupplierInvoiceRow> rows = new ArrayList<>();
        for (SupplierInvoice inv : invs) {
            if (supplierIdFilter != null && !supplierIdFilter.isBlank()
                    && !supplierIdFilter.equals(inv.getSupplierId())) {
                continue;
            }
            BigDecimal open = openBalance(inv.getId(), inv.getGrandTotal());
            if (open.compareTo(MONEY) <= 0) {
                continue;
            }
            rows.add(new OpenSupplierInvoiceRow(
                    inv.getId(),
                    inv.getSupplierId(),
                    inv.getInvoiceNumber(),
                    inv.getInvoiceDate(),
                    inv.getDueDate(),
                    inv.getGrandTotal().setScale(2, RoundingMode.HALF_UP),
                    open));
        }
        rows.sort(Comparator.comparing(OpenSupplierInvoiceRow::invoiceDate).reversed());
        return rows;
    }

    /**
     * Posts supplier payment after KopoKopo Send Money webhook confirms success.
     */
    @Transactional
    public PostSupplierPaymentResponse recordKopokopoDisbursement(
            String businessId,
            String supplierId,
            String supplierInvoiceId,
            BigDecimal amount,
            String reference,
            Instant paidAt
    ) {
        PostSupplierPaymentRequest req = new PostSupplierPaymentRequest(
                supplierId,
                paidAt,
                PurchasingConstants.PAY_METHOD_MPESA,
                amount.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO,
                reference,
                "Paid via KopoKopo Send Money",
                List.of(new PostSupplierPaymentAllocationLine(supplierInvoiceId, amount.setScale(2, RoundingMode.HALF_UP))));
        return executePayment(businessId, req);
    }

    @Transactional
    public PostSupplierPaymentResponse postPayment(String businessId, PostSupplierPaymentRequest req, String idemKey) {
        if (idemKey != null && !idemKey.isBlank()) {
            return postPaymentWithIdempotency(businessId, req, idemKey.trim());
        }
        return executePayment(businessId, req);
    }

    private PostSupplierPaymentResponse postPaymentWithIdempotency(
            String businessId,
            PostSupplierPaymentRequest req,
            String keyRaw
    ) {
        String route = postPaymentRoute();
        String keyHash = TokenHasher.sha256Hex(keyRaw);
        synchronized ((businessId + "|" + route + "|" + keyHash).intern()) {
            String bodyJson;
            try {
                bodyJson = objectMapper.writeValueAsString(req);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            String bodyHash = TokenHasher.sha256Hex(bodyJson);
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByBusinessIdAndKeyHashAndRoute(
                    businessId, keyHash, route);
            if (existing.isPresent()) {
                IdempotencyKey row = existing.get();
                if (!row.getBodyHash().equals(bodyHash)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Idempotency key already used with a different request body");
                }
                try {
                    return objectMapper.readValue(row.getResponseJson(), PostSupplierPaymentResponse.class);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            PostSupplierPaymentResponse response = executePayment(businessId, req);
            persistIdempotency(businessId, keyHash, bodyHash, route, response);
            return response;
        }
    }

    private void persistIdempotency(
            String businessId,
            String keyHash,
            String bodyHash,
            String route,
            PostSupplierPaymentResponse response
    ) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        IdempotencyKey row = new IdempotencyKey();
        row.setBusinessId(businessId);
        row.setKeyHash(keyHash);
        row.setRoute(route);
        row.setBodyHash(bodyHash);
        row.setHttpStatus(HttpStatus.OK.value());
        row.setResponseJson(json);
        try {
            idempotencyKeyRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            IdempotencyKey replay = idempotencyKeyRepository
                    .findByBusinessIdAndKeyHashAndRoute(businessId, keyHash, route)
                    .orElseThrow(() -> e);
            if (!replay.getBodyHash().equals(bodyHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Idempotency key already used with a different request body");
            }
        }
    }

    private PostSupplierPaymentResponse executePayment(String businessId, PostSupplierPaymentRequest req) {
        synchronized ((businessId + "|" + req.supplierId()).intern()) {
            assertPayMethod(req.paymentMethod());
            Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(req.supplierId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier not found"));
            BigDecimal credit = nz(req.creditApplied()).setScale(2, RoundingMode.HALF_UP);
            if (credit.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creditApplied must be >= 0");
            }
            BigDecimal prepayStart = nz(supplier.getPrepaymentBalance());
            if (credit.compareTo(prepayStart) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creditApplied exceeds supplier prepayment balance");
            }
            assertUniqueInvoiceLines(req);
            BigDecimal allocSum = req.allocations().stream()
                    .map(PostSupplierPaymentAllocationLine::amount)
                    .map(a -> a.setScale(2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cash = req.paymentAmount().setScale(2, RoundingMode.HALF_UP);
            if (cash.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentAmount must be >= 0");
            }
            BigDecimal totalIn = cash.add(credit);
            if (totalIn.compareTo(allocSum) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment and credit must cover total allocations");
            }
            for (PostSupplierPaymentAllocationLine line : req.allocations()) {
                if (line.amount().signum() <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocation amounts must be positive");
                }
                SupplierInvoice inv = supplierInvoiceRepository.findByIdAndBusinessId(line.supplierInvoiceId(), businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice not found"));
                if (!PurchasingConstants.INVOICE_POSTED.equals(inv.getStatus())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice is not payable");
                }
                if (!inv.getSupplierId().equals(req.supplierId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice does not belong to supplier");
                }
                BigDecimal open = openBalance(inv.getId(), inv.getGrandTotal());
                if (line.amount().setScale(2, RoundingMode.HALF_UP).compareTo(open) > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocation exceeds open balance on invoice");
                }
            }

            BigDecimal surplus = totalIn.subtract(allocSum);
            LocalDate entryDate = LocalDate.ofInstant(req.paidAt(), ZoneOffset.UTC);

            SupplierPayment payment = new SupplierPayment();
            payment.setId(UUID.randomUUID().toString());
            payment.setBusinessId(businessId);
            payment.setSupplierId(req.supplierId());
            payment.setPaidAt(req.paidAt());
            payment.setPaymentMethod(req.paymentMethod().trim().toLowerCase());
            payment.setAmount(cash);
            payment.setCreditApplied(credit);
            payment.setReference(blankToNull(req.reference()));
            payment.setNotes(blankToNull(req.notes()));
            payment.setStatus(PurchasingConstants.PAYMENT_POSTED);
            supplierPaymentRepository.save(payment);

            JournalEntry entry = new JournalEntry();
            entry.setBusinessId(businessId);
            entry.setEntryDate(entryDate);
            entry.setSourceType(PurchasingConstants.JOURNAL_SOURCE_SUPPLIER_PAYMENT);
            entry.setSourceId(payment.getId());
            entry.setMemo("Supplier payment " + payment.getId());
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.ACCOUNTS_PAYABLE), allocSum);
            if (surplus.compareTo(MONEY) > 0) {
                entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.SUPPLIER_ADVANCES), surplus);
            }
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.OPERATING_CASH), cash);
            if (credit.compareTo(MONEY) > 0) {
                entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.SUPPLIER_ADVANCES), credit);
            }
            String jeId = ledgerPostingPort.post(entry);

            BigDecimal prepayAfter = prepayStart.add(cash).subtract(allocSum).setScale(2, RoundingMode.HALF_UP);
            if (prepayAfter.signum() < 0 && prepayAfter.abs().compareTo(MONEY) > 0) {
                throw new IllegalStateException("Prepayment balance would go negative");
            }
            supplier.setPrepaymentBalance(prepayAfter);
            supplierRepository.save(supplier);

            for (PostSupplierPaymentAllocationLine line : req.allocations()) {
                SupplierPaymentAllocation a = new SupplierPaymentAllocation();
                a.setSupplierPaymentId(payment.getId());
                a.setSupplierInvoiceId(line.supplierInvoiceId());
                a.setAmount(line.amount().setScale(2, RoundingMode.HALF_UP));
                allocationRepository.save(a);
            }

            return new PostSupplierPaymentResponse(payment.getId(), jeId, allocSum, prepayAfter);
        }
    }

    private BigDecimal openBalance(String invoiceId, BigDecimal grandTotal) {
        BigDecimal paid = allocationRepository.sumAmountBySupplierInvoiceId(invoiceId);
        return grandTotal.subtract(paid).setScale(2, RoundingMode.HALF_UP);
    }

    private static void assertUniqueInvoiceLines(PostSupplierPaymentRequest req) {
        Set<String> seen = new HashSet<>();
        for (PostSupplierPaymentAllocationLine line : req.allocations()) {
            if (!seen.add(line.supplierInvoiceId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate invoice in allocations");
            }
        }
    }

    private static void assertPayMethod(String raw) {
        String m = raw == null ? "" : raw.trim().toLowerCase();
        if (!PurchasingConstants.PAY_METHOD_CASH.equals(m)
                && !PurchasingConstants.PAY_METHOD_BANK.equals(m)
                && !PurchasingConstants.PAY_METHOD_MPESA.equals(m)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentMethod must be cash, bank, or mpesa");
        }
    }



    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
