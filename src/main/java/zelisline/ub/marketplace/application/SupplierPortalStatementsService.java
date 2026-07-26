package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalLedgerEntry;
import zelisline.ub.marketplace.api.dto.SupplierPortalStatementResponse;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.application.PathBAssociatedCostService;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierPayment;
import zelisline.ub.purchasing.domain.SupplierPaymentAllocation;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalStatementsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessRepository businessRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final PathBAssociatedCostService pathBAssociatedCostService;
    private final PlatformSupplierPortalSettingsService portalSettingsService;

    @Transactional(readOnly = true)
    public SupplierPortalStatementResponse statement(
            String marketplaceSupplierId,
            String localSupplierId,
            int year,
            int month
    ) {
        if (month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Month must be 1–12");
        }
        BusinessSupplierConnection link = requireActiveLink(marketplaceSupplierId, localSupplierId);
        Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(localSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        Business business = businessRepository.findById(link.getBusinessId()).orElse(null);
        String shopName = business != null && business.getName() != null ? business.getName().trim() : "Shop";
        String currency = business != null && business.getCurrency() != null && !business.getCurrency().isBlank()
                ? business.getCurrency().trim()
                : "KES";

        YearMonth ym = YearMonth.of(year, month);
        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd = ym.atEndOfMonth();

        List<SupplierInvoice> invoices = supplierInvoiceRepository.findByBusinessIdAndSupplierIdAndStatus(
                link.getBusinessId(), local.getId(), PurchasingConstants.INVOICE_POSTED);
        List<SupplierPayment> payments = supplierPaymentRepository.findBySupplierIdInOrderByPaidAtDesc(
                List.of(local.getId()),
                org.springframework.data.domain.PageRequest.of(0, 5000));

        BigDecimal opening = ZERO;
        BigDecimal periodInvoices = ZERO;
        BigDecimal periodPayments = ZERO;
        List<RawMove> periodMoves = new ArrayList<>();

        for (SupplierInvoice inv : invoices) {
            BigDecimal grand = money(pathBAssociatedCostService.payableGrandTotal(link.getBusinessId(), inv));
            LocalDate date = inv.getInvoiceDate();
            if (date == null) {
                continue;
            }
            if (date.isBefore(periodStart)) {
                opening = opening.add(grand);
            } else if (!date.isAfter(periodEnd)) {
                periodInvoices = periodInvoices.add(grand);
                periodMoves.add(new RawMove(
                        date,
                        "INVOICE",
                        inv.getInvoiceNumber(),
                        "Invoice " + inv.getInvoiceNumber(),
                        grand,
                        ZERO));
            }
        }

        // Amounts settled against invoices (allocations), not raw cash: payments can
        // apply prepayment credit or overpay into credit, and the invoices page
        // computes open balances from allocations — the ledger must reconcile with it.
        java.util.Map<String, BigDecimal> allocatedByPayment = allocationsByPayment(payments);

        for (SupplierPayment pay : payments) {
            if (!PurchasingConstants.PAYMENT_POSTED.equals(pay.getStatus())) {
                continue;
            }
            LocalDate date = pay.getPaidAt() == null
                    ? null
                    : LocalDate.ofInstant(pay.getPaidAt(), ZoneOffset.UTC);
            if (date == null) {
                continue;
            }
            BigDecimal amount = money(allocatedByPayment.getOrDefault(pay.getId(), BigDecimal.ZERO));
            if (amount.signum() <= 0) {
                continue;
            }
            if (date.isBefore(periodStart)) {
                opening = opening.subtract(amount);
            } else if (!date.isAfter(periodEnd)) {
                periodPayments = periodPayments.add(amount);
                periodMoves.add(new RawMove(
                        date,
                        "PAYMENT",
                        pay.getReference() == null ? pay.getId() : pay.getReference(),
                        "Payment via " + pay.getPaymentMethod(),
                        ZERO,
                        amount));
            }
        }

        periodMoves.sort(Comparator
                .comparing(RawMove::date)
                .thenComparing(RawMove::type)
                .thenComparing(RawMove::reference, Comparator.nullsLast(String::compareTo)));

        List<SupplierPortalLedgerEntry> entries = new ArrayList<>();
        BigDecimal running = money(opening);
        for (RawMove move : periodMoves) {
            running = running.add(move.debit()).subtract(move.credit());
            entries.add(new SupplierPortalLedgerEntry(
                    move.date(),
                    move.type(),
                    move.reference(),
                    move.description(),
                    move.debit(),
                    move.credit(),
                    money(running)));
        }

        return new SupplierPortalStatementResponse(
                local.getId(),
                shopName,
                currency,
                year,
                month,
                periodStart,
                periodEnd,
                money(opening),
                money(running),
                money(periodInvoices),
                money(periodPayments),
                List.copyOf(entries));
    }

    public String toCsv(SupplierPortalStatementResponse statement) {
        StringBuilder sb = new StringBuilder();
        sb.append("Date,Type,Reference,Description,Debit,Credit,Balance\n");
        sb.append(csv(statement.periodStart().toString())).append(",OPENING,,,")
                .append(statement.openingBalance()).append(",,")
                .append(statement.openingBalance()).append('\n');
        for (SupplierPortalLedgerEntry e : statement.entries()) {
            sb.append(csv(e.date().toString())).append(',')
                    .append(csv(e.type())).append(',')
                    .append(csv(e.reference())).append(',')
                    .append(csv(e.description())).append(',')
                    .append(e.debit()).append(',')
                    .append(e.credit()).append(',')
                    .append(e.balance()).append('\n');
        }
        sb.append(csv(statement.periodEnd().toString())).append(",CLOSING,,,")
                .append(",,")
                .append(statement.closingBalance()).append('\n');
        return sb.toString();
    }

    public byte[] toPdf(SupplierPortalStatementResponse statement) {
        return SupplierPortalStatementPdfRenderer.render(statement);
    }

    private java.util.Map<String, BigDecimal> allocationsByPayment(List<SupplierPayment> payments) {
        List<String> ids = payments.stream().map(SupplierPayment::getId).toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, BigDecimal> sums = new java.util.HashMap<>();
        for (SupplierPaymentAllocation alloc : allocationRepository.findBySupplierPaymentIdIn(ids)) {
            sums.merge(alloc.getSupplierPaymentId(), money(alloc.getAmount()), BigDecimal::add);
        }
        return sums;
    }

    /** Gates only CSV/PDF exports — the on-screen JSON statement is always viewable. */
    public void assertDownloadsAllowed() {
        if (!portalSettingsService.loadSingleton().isAllowStatementDownloads()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Statement downloads are disabled");
        }
    }

    private BusinessSupplierConnection requireActiveLink(String marketplaceSupplierId, String localSupplierId) {
        BusinessSupplierConnection link = connectionRepository
                .findByMarketplaceSupplierIdAndLocalSupplierId(marketplaceSupplierId, localSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found"));
        if (!BusinessSupplierConnectionStatuses.ACTIVE.equals(link.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found");
        }
        return link;
    }

    private static BigDecimal money(BigDecimal v) {
        if (v == null) {
            return ZERO;
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private record RawMove(
            LocalDate date,
            String type,
            String reference,
            String description,
            BigDecimal debit,
            BigDecimal credit
    ) {
    }
}
