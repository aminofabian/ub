package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerBalanceQuery;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.payroll.application.StaffCommissionPlan;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.sales.application.DrawerPaidOutService;
import zelisline.ub.serviceops.api.dto.CommissionDtos.LedgerEntryResponse;
import zelisline.ub.serviceops.api.dto.CommissionDtos.StatementResponse;
import zelisline.ub.serviceops.api.dto.CommissionDtos.StatementRow;
import zelisline.ub.serviceops.domain.ServiceOffering;
import zelisline.ub.serviceops.domain.StaffLedgerEntry;
import zelisline.ub.serviceops.domain.StaffLedgerKinds;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;
import zelisline.ub.serviceops.repository.StaffLedgerEntryRepository;

/**
 * The staff ledger (ADR 0014): what each staff member has earned in commission and tips, what
 * was reversed or adjusted, what was paid out, and therefore what is owed.
 *
 * <p>Rules that hold everywhere in this class:</p>
 * <ul>
 *   <li>Rows are only ever added. A correction is another row.</li>
 *   <li>Every write carries a dedupe key, so a replayed sale or a double tap writes nothing twice.</li>
 *   <li>Every row that moves money posts a journal through {@link LedgerPostingPort}; the sum of
 *       the ledger must equal the commissions-payable and tips-payable accounts.</li>
 *   <li>Commission is whole shillings, rounded half up; the remainder stays with the business.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StaffEarningsService {

    public static final String JOURNAL_SOURCE_COMMISSION = "staff_commission";
    public static final String JOURNAL_SOURCE_COMMISSION_REVERSAL = "staff_commission_reversal";
    public static final String JOURNAL_SOURCE_ADJUSTMENT = "staff_adjustment";
    public static final String JOURNAL_SOURCE_PAYOUT = "staff_payout";
    public static final String JOURNAL_SOURCE_PAYOUT_REVERSAL = "staff_payout_reversal";

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final List<String> PAYABLE_CODES =
            List.of(LedgerAccountCodes.COMMISSIONS_PAYABLE, LedgerAccountCodes.TIPS_PAYABLE);

    private final StaffLedgerEntryRepository ledgerRepository;
    private final ServiceOfferingRepository serviceRepository;
    private final StaffProfileService staffProfileService;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final LedgerBalanceQuery ledgerBalanceQuery;
    private final DrawerPaidOutService drawerPaidOutService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /** A completed sale line, as far as earnings are concerned. */
    public record SaleLine(
            String saleItemId,
            boolean service,
            String serviceId,
            String performerStaffProfileId,
            BigDecimal lineTotal,
            BigDecimal taxAmount,
            BigDecimal quantity
    ) {
    }

    // ------------------------------------------------------------------ earning

    /**
     * Records what staff earned on a sale, in the sale's transaction.
     *
     * <p>A service line always writes a commission row for its performer, even when the amount
     * is zero, so that "services done" and "revenue per barber" can be read from the ledger. A
     * product line writes one only when the seller's plan pays commission on products. Tips are
     * one row per staff member.</p>
     */
    @Transactional
    public void recordForSale(
            String businessId,
            String branchId,
            String saleId,
            String sellerUserId,
            List<SaleLine> lines,
            Map<String, BigDecimal> tipsByStaffProfile,
            Instant soldAt
    ) {
        Map<String, Optional<StaffCommissionPlan>> planCache = new LinkedHashMap<>();
        Optional<StaffCommissionPlan> sellerPlan = null;
        List<StaffLedgerEntry> commissionRows = new ArrayList<>();
        List<SaleLine> productLines = new ArrayList<>();

        for (SaleLine line : lines) {
            BigDecimal base = money(line.lineTotal()).subtract(money(line.taxAmount()));
            if (line.service()) {
                if (line.performerStaffProfileId() == null) {
                    continue;
                }
                Optional<StaffCommissionPlan> plan = planCache.computeIfAbsent(
                        line.performerStaffProfileId(),
                        id -> staffProfileService.commissionPlan(businessId, id));
                Rule rule = serviceRule(businessId, line.serviceId(), plan.orElse(null));
                BigDecimal amount = rule.apply(base, line.quantity());
                StaffLedgerEntry row = entry(businessId, branchId, line.performerStaffProfileId(),
                        StaffLedgerKinds.COMMISSION, StaffLedgerKinds.BUCKET_COMMISSION, amount,
                        "C:" + line.saleItemId(), soldAt, sellerUserId);
                row.setBaseAmount(base);
                row.setRule(rule.label());
                row.setSource(StaffLedgerKinds.SOURCE_SERVICE);
                row.setSaleId(saleId);
                row.setSaleItemId(line.saleItemId());
                commissionRows.add(row);
            } else {
                productLines.add(line);
            }
        }

        // Product commission is rounded once for the whole sale, then shared across the product
        // rows. Rounding each row would depend on how stock happened to be split across batches
        // and would pay nothing on a basket of small items.
        if (!productLines.isEmpty()) {
            sellerPlan = sellerUserId == null
                    ? Optional.empty()
                    : staffProfileService.commissionPlanForUser(businessId, sellerUserId);
            if (sellerPlan.isPresent()
                    && sellerPlan.get().productRate() != null
                    && sellerPlan.get().productRate().signum() > 0) {
                BigDecimal rate = sellerPlan.get().productRate();
                List<BigDecimal> bases = new ArrayList<>(productLines.size());
                BigDecimal baseTotal = ZERO;
                for (SaleLine line : productLines) {
                    BigDecimal base = money(line.lineTotal()).subtract(money(line.taxAmount())).max(ZERO);
                    bases.add(base);
                    baseTotal = baseTotal.add(base);
                }
                BigDecimal total = wholeShillings(baseTotal.multiply(rate).divide(HUNDRED, 4, RoundingMode.HALF_UP));
                List<BigDecimal> shares = shareOut(total, bases, baseTotal);
                for (int i = 0; i < productLines.size(); i++) {
                    if (shares.get(i).signum() <= 0) {
                        continue;
                    }
                    SaleLine line = productLines.get(i);
                    StaffLedgerEntry row = entry(businessId, branchId, sellerPlan.get().staffProfileId(),
                            StaffLedgerKinds.COMMISSION, StaffLedgerKinds.BUCKET_COMMISSION, shares.get(i),
                            "C:" + line.saleItemId(), soldAt, sellerUserId);
                    row.setBaseAmount(bases.get(i));
                    row.setRule("PERCENT " + rate.toPlainString() + " (product)");
                    row.setSource(StaffLedgerKinds.SOURCE_PRODUCT);
                    row.setSaleId(saleId);
                    row.setSaleItemId(line.saleItemId());
                    commissionRows.add(row);
                }
            }
        }

        BigDecimal commissionTotal = ZERO;
        for (StaffLedgerEntry row : commissionRows) {
            commissionTotal = commissionTotal.add(row.getAmount());
        }
        String journalId = null;
        if (commissionTotal.signum() > 0) {
            journalId = postTwoLine(businessId, branchId, JOURNAL_SOURCE_COMMISSION, saleId,
                    "Staff commission on sale " + saleId,
                    LedgerAccountCodes.COMMISSION_EXPENSE, LedgerAccountCodes.COMMISSIONS_PAYABLE, commissionTotal);
        }
        for (StaffLedgerEntry row : commissionRows) {
            row.setJournalEntryId(row.getAmount().signum() > 0 ? journalId : null);
        }
        ledgerRepository.saveAll(commissionRows);

        // Tips: the sale's own journal already credited tips payable.
        if (tipsByStaffProfile != null) {
            for (Map.Entry<String, BigDecimal> tip : tipsByStaffProfile.entrySet()) {
                BigDecimal amount = money(tip.getValue());
                if (amount.signum() <= 0) {
                    continue;
                }
                StaffLedgerEntry row = entry(businessId, branchId, tip.getKey(),
                        StaffLedgerKinds.TIP, StaffLedgerKinds.BUCKET_TIPS, amount,
                        "T:" + saleId + ":" + tip.getKey(), soldAt, sellerUserId);
                row.setSaleId(saleId);
                ledgerRepository.save(row);
            }
        }
        ledgerRepository.flush();
    }

    /**
     * A voided sale takes back everything staff earned on it, tips included. The commission
     * journal is reversed here; the tip liability is reversed by the void journal itself.
     */
    @Transactional
    public void reverseForSale(String businessId, String saleId, String userId) {
        BigDecimal commissionBack = ZERO;
        String branchId = null;
        for (StaffLedgerEntry original : ledgerRepository.findByBusinessIdAndSaleIdOrderByCreatedAtAsc(businessId, saleId)) {
            if (!StaffLedgerKinds.COMMISSION.equals(original.getKind())
                    && !StaffLedgerKinds.TIP.equals(original.getKind())) {
                continue;
            }
            BigDecimal remaining = original.getAmount().add(alreadyReversed(businessId, original.getId()));
            if (remaining.signum() <= 0 || ledgerRepository.existsByBusinessIdAndDedupeKey(businessId, "RV:" + original.getId())) {
                continue;
            }
            StaffLedgerEntry reversal = entry(businessId, original.getBranchId(), original.getStaffProfileId(),
                    StaffLedgerKinds.REVERSAL, original.getBucket(), remaining.negate(),
                    "RV:" + original.getId(), Instant.now(), userId);
            reversal.setSaleId(saleId);
            reversal.setSaleItemId(original.getSaleItemId());
            reversal.setReversesId(original.getId());
            reversal.setSource(original.getSource());
            reversal.setReason("Sale voided");
            ledgerRepository.save(reversal);
            branchId = original.getBranchId();
            if (StaffLedgerKinds.BUCKET_COMMISSION.equals(original.getBucket())) {
                commissionBack = commissionBack.add(remaining);
            }
        }
        if (commissionBack.signum() > 0) {
            postTwoLine(businessId, branchId, JOURNAL_SOURCE_COMMISSION_REVERSAL, saleId,
                    "Commission reversed: sale " + saleId + " voided",
                    LedgerAccountCodes.COMMISSIONS_PAYABLE, LedgerAccountCodes.COMMISSION_EXPENSE, commissionBack);
        }
        ledgerRepository.flush();
    }

    /**
     * A refunded product line takes back the matching share of the seller's commission on it.
     *
     * @param returnedSoFar everything returned on this line including the current refund. The
     *        reversal is worked out against that running total, so several partial refunds never
     *        leave a rounding remainder behind and a fully returned line reverses in full.
     */
    @Transactional
    public void reverseForRefundedLine(
            String businessId,
            String saleItemId,
            BigDecimal returnedSoFar,
            BigDecimal lineQuantity,
            String refundId,
            String userId
    ) {
        if (lineQuantity == null || lineQuantity.signum() <= 0 || returnedSoFar == null) {
            return;
        }
        for (StaffLedgerEntry original : ledgerRepository.findByBusinessIdAndSaleItemIdOrderByCreatedAtAsc(businessId, saleItemId)) {
            if (!StaffLedgerKinds.COMMISSION.equals(original.getKind()) || original.getAmount().signum() <= 0) {
                continue;
            }
            String key = "RR:" + refundId + ":" + original.getId();
            if (ledgerRepository.existsByBusinessIdAndDedupeKey(businessId, key)) {
                continue;
            }
            BigDecimal reversedBefore = alreadyReversed(businessId, original.getId()).negate();
            BigDecimal remaining = original.getAmount().subtract(reversedBefore);
            BigDecimal shouldBeReversed = returnedSoFar.compareTo(lineQuantity) >= 0
                    ? original.getAmount()
                    : wholeShillings(original.getAmount().multiply(returnedSoFar)
                            .divide(lineQuantity, 4, RoundingMode.HALF_UP));
            BigDecimal share = shouldBeReversed.subtract(reversedBefore).min(remaining);
            if (share.signum() <= 0) {
                continue;
            }
            String journalId = postTwoLine(businessId, original.getBranchId(), JOURNAL_SOURCE_COMMISSION_REVERSAL,
                    refundId, "Commission reversed: refund " + refundId,
                    LedgerAccountCodes.COMMISSIONS_PAYABLE, LedgerAccountCodes.COMMISSION_EXPENSE, share);
            StaffLedgerEntry reversal = entry(businessId, original.getBranchId(), original.getStaffProfileId(),
                    StaffLedgerKinds.REVERSAL, StaffLedgerKinds.BUCKET_COMMISSION, share.negate(), key,
                    Instant.now(), userId);
            reversal.setSaleId(original.getSaleId());
            reversal.setSaleItemId(saleItemId);
            reversal.setReversesId(original.getId());
            reversal.setSource(original.getSource());
            reversal.setReason("Refund");
            reversal.setJournalEntryId(journalId);
            ledgerRepository.save(reversal);
        }
        ledgerRepository.flush();
    }

    // -------------------------------------------------------------- adjustments

    /** A bonus or a correction. Positive adds to what is owed, negative deducts. A reason is required. */
    @Transactional
    public LedgerEntryResponse adjust(
            String businessId,
            String staffProfileId,
            BigDecimal amount,
            String reason,
            String idempotencyKey,
            String userId
    ) {
        String key = "A:" + requireKey(idempotencyKey);
        // The row lock is the first statement of the transaction on purpose. Under InnoDB's
        // default isolation the snapshot is taken at the first plain read; locking first means
        // everything read afterwards includes whatever the previous holder committed.
        staffProfileService.lockForLedger(businessId, staffProfileId);
        Optional<StaffLedgerEntry> existing = ledgerRepository.findByBusinessIdAndDedupeKey(businessId, key);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        BigDecimal value = money(amount);
        if (value.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount other than zero");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why you are making this adjustment");
        }
        // The journal's source is the ledger row itself (journal source ids are 36 characters).
        String entryId = UUID.randomUUID().toString();
        String journalId = value.signum() > 0
                ? postTwoLine(businessId, null, JOURNAL_SOURCE_ADJUSTMENT, entryId, "Staff earnings adjustment",
                        LedgerAccountCodes.COMMISSION_EXPENSE, LedgerAccountCodes.COMMISSIONS_PAYABLE, value)
                : postTwoLine(businessId, null, JOURNAL_SOURCE_ADJUSTMENT, entryId, "Staff earnings adjustment",
                        LedgerAccountCodes.COMMISSIONS_PAYABLE, LedgerAccountCodes.COMMISSION_EXPENSE, value.negate());
        StaffLedgerEntry row = entry(businessId, null, staffProfileId, StaffLedgerKinds.ADJUSTMENT,
                StaffLedgerKinds.BUCKET_COMMISSION, value, key, Instant.now(), userId);
        row.setId(entryId);
        row.setReason(reason.trim().length() > 500 ? reason.trim().substring(0, 500) : reason.trim());
        row.setJournalEntryId(journalId);
        saveOnce(row);
        audit(businessId, null, userId, "staff.earnings.adjusted", staffProfileId,
                Map.of("amount", value.toPlainString(), "reason", row.getReason()));
        return toResponse(row);
    }

    // ------------------------------------------------------------------ payouts

    /**
     * Pays a staff member an amount against what they are owed. Partial payouts are normal. Tips
     * owed are settled first, then commission. Paying more than is owed is refused unless
     * {@code allowOverpay} is set, in which case the balance goes negative.
     */
    @Transactional
    public List<LedgerEntryResponse> payout(
            String businessId,
            String branchId,
            String tillDeviceKey,
            String staffProfileId,
            BigDecimal amount,
            String method,
            String reference,
            boolean allowOverpay,
            String idempotencyKey,
            String userId
    ) {
        String key = "P:" + requireKey(idempotencyKey);
        // Lock first (see adjust): two payouts for one person must not both read the same balance.
        staffProfileService.lockForLedger(businessId, staffProfileId);
        List<StaffLedgerEntry> existing = new ArrayList<>();
        ledgerRepository.findByBusinessIdAndDedupeKey(businessId, key + ":T").ifPresent(existing::add);
        ledgerRepository.findByBusinessIdAndDedupeKey(businessId, key + ":C").ifPresent(existing::add);
        if (!existing.isEmpty()) {
            return existing.stream().map(StaffEarningsService::toResponse).toList();
        }
        BigDecimal value = money(amount);
        if (value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount to pay");
        }
        String payoutMethod = normalizeMethod(method);
        // Only a drawer payout belongs to a branch.
        if (!StaffLedgerKinds.METHOD_DRAWER.equals(payoutMethod)) {
            branchId = null;
        }

        BigDecimal tipsOwed = money(ledgerRepository.balance(businessId, staffProfileId, StaffLedgerKinds.BUCKET_TIPS));
        BigDecimal commissionOwed =
                money(ledgerRepository.balance(businessId, staffProfileId, StaffLedgerKinds.BUCKET_COMMISSION));
        BigDecimal owed = tipsOwed.add(commissionOwed);
        if (value.compareTo(owed) > 0 && !allowOverpay) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only KSh " + owed.max(ZERO).toPlainString() + " is owed");
        }
        BigDecimal tipPart = value.min(tipsOwed.max(ZERO));
        BigDecimal commissionPart = value.subtract(tipPart);

        // One id for the whole payout: the journal's source and the cash drawer reference.
        String payoutId = UUID.randomUUID().toString();
        String shiftId = null;
        if (StaffLedgerKinds.METHOD_DRAWER.equals(payoutMethod)) {
            if (branchId == null || branchId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the branch whose till pays this out");
            }
            shiftId = drawerPaidOutService.payOut(businessId, branchId, tillDeviceKey, value, payoutId, userId);
        }

        JournalEntry journal = journal(businessId, branchId, JOURNAL_SOURCE_PAYOUT, payoutId, "Staff payout");
        if (tipPart.signum() > 0) {
            journal.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.TIPS_PAYABLE), tipPart);
        }
        if (commissionPart.signum() > 0) {
            journal.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.COMMISSIONS_PAYABLE),
                    commissionPart);
        }
        journal.credit(ledgerAccountResolver.resolveId(businessId, cashAccount(payoutMethod)), value);
        String journalId = ledgerPostingPort.post(journal);

        Instant now = Instant.now();
        List<StaffLedgerEntry> rows = new ArrayList<>();
        if (tipPart.signum() > 0) {
            rows.add(payoutRow(businessId, branchId, staffProfileId, StaffLedgerKinds.BUCKET_TIPS, tipPart,
                    key + ":T", payoutMethod, reference, shiftId, journalId, now, userId));
        }
        if (commissionPart.signum() > 0) {
            rows.add(payoutRow(businessId, branchId, staffProfileId, StaffLedgerKinds.BUCKET_COMMISSION,
                    commissionPart, key + ":C", payoutMethod, reference, shiftId, journalId, now, userId));
        }
        for (StaffLedgerEntry row : rows) {
            saveOnce(row);
        }
        audit(businessId, branchId, userId, "staff.earnings.paid_out", staffProfileId,
                Map.of("amount", value.toPlainString(), "method", payoutMethod));
        return rows.stream().map(StaffEarningsService::toResponse).toList();
    }

    /** Undoes a mistaken payout with mirror rows; the original rows stay. */
    @Transactional
    public List<LedgerEntryResponse> reversePayout(String businessId, String entryId, String userId) {
        StaffLedgerEntry first = ledgerRepository.findByIdAndBusinessId(entryId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payout not found"));
        if (!StaffLedgerKinds.PAYOUT.equals(first.getKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a payout can be reversed here");
        }
        staffProfileService.lockForLedger(businessId, first.getStaffProfileId());
        // A payout that settled both tips and commission is two rows sharing one journal.
        List<StaffLedgerEntry> parts = ledgerRepository
                .findForStaffBetween(businessId, first.getStaffProfileId(),
                        first.getOccurredAt().minusSeconds(1), first.getOccurredAt().plusSeconds(1))
                .stream()
                .filter(e -> StaffLedgerKinds.PAYOUT.equals(e.getKind())
                        && first.getJournalEntryId() != null
                        && first.getJournalEntryId().equals(e.getJournalEntryId()))
                .toList();
        if (parts.isEmpty()) {
            parts = List.of(first);
        }
        for (StaffLedgerEntry part : parts) {
            if (ledgerRepository.existsByBusinessIdAndDedupeKey(businessId, "RV:" + part.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This payout was already reversed");
            }
        }
        BigDecimal total = ZERO;
        for (StaffLedgerEntry part : parts) {
            total = total.add(part.getAmount().negate());
        }
        if (StaffLedgerKinds.METHOD_DRAWER.equals(first.getPayoutMethod()) && first.getShiftId() != null) {
            drawerPaidOutService.reverse(businessId, first.getShiftId(), total, first.getId(), userId);
        }
        JournalEntry journal = journal(businessId, first.getBranchId(), JOURNAL_SOURCE_PAYOUT_REVERSAL,
                first.getId(), "Staff payout reversed");
        journal.debit(ledgerAccountResolver.resolveId(businessId, cashAccount(first.getPayoutMethod())), total);
        for (StaffLedgerEntry part : parts) {
            String code = StaffLedgerKinds.BUCKET_TIPS.equals(part.getBucket())
                    ? LedgerAccountCodes.TIPS_PAYABLE
                    : LedgerAccountCodes.COMMISSIONS_PAYABLE;
            journal.credit(ledgerAccountResolver.resolveId(businessId, code), part.getAmount().negate());
        }
        String journalId = ledgerPostingPort.post(journal);
        List<StaffLedgerEntry> reversals = new ArrayList<>();
        for (StaffLedgerEntry part : parts) {
            StaffLedgerEntry reversal = entry(businessId, part.getBranchId(), part.getStaffProfileId(),
                    StaffLedgerKinds.REVERSAL, part.getBucket(), part.getAmount().negate(),
                    "RV:" + part.getId(), Instant.now(), userId);
            reversal.setReversesId(part.getId());
            reversal.setPayoutMethod(part.getPayoutMethod());
            reversal.setReason("Payout reversed");
            reversal.setJournalEntryId(journalId);
            saveOnce(reversal);
            reversals.add(reversal);
        }
        audit(businessId, first.getBranchId(), userId, "staff.earnings.payout_reversed", first.getStaffProfileId(),
                Map.of("amount", total.toPlainString()));
        return reversals.stream().map(StaffEarningsService::toResponse).toList();
    }

    // ---------------------------------------------------------------- statement

    /**
     * One row per staff member for the period (Africa/Nairobi days, both inclusive): opening
     * balance, what moved, closing balance. {@code reconciled} is false if the ledger no longer
     * agrees with the commissions-payable and tips-payable accounts.
     */
    @Transactional(readOnly = true)
    public StatementResponse statement(String businessId, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(NAIROBI).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        if (!end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The period must end on or after it starts");
        }
        Map<String, BigDecimal> opening = new LinkedHashMap<>();
        for (Object[] r : ledgerRepository.sumByStaffAndBucketBefore(businessId, start)) {
            opening.merge((String) r[0], money((BigDecimal) r[2]), BigDecimal::add);
        }
        Map<String, BigDecimal[]> moved = new LinkedHashMap<>();
        // [commission, tips, reversals, adjustments, payouts, serviceRevenue, servicesDone]
        for (Object[] r : ledgerRepository.sumByStaffKindBetween(businessId, start, end)) {
            String staffId = (String) r[0];
            String kind = (String) r[1];
            String source = (String) r[3];
            BigDecimal sum = money((BigDecimal) r[4]);
            BigDecimal[] acc = moved.computeIfAbsent(staffId, k -> new BigDecimal[] {
                    ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, BigDecimal.ZERO });
            switch (kind) {
                case StaffLedgerKinds.COMMISSION -> {
                    acc[0] = acc[0].add(sum);
                    if (StaffLedgerKinds.SOURCE_SERVICE.equals(source)) {
                        acc[5] = acc[5].add(money((BigDecimal) r[5]));
                        acc[6] = acc[6].add(BigDecimal.valueOf(((Number) r[6]).longValue()));
                    }
                }
                case StaffLedgerKinds.TIP -> acc[1] = acc[1].add(sum);
                case StaffLedgerKinds.REVERSAL -> acc[2] = acc[2].add(sum);
                case StaffLedgerKinds.ADJUSTMENT -> acc[3] = acc[3].add(sum);
                case StaffLedgerKinds.PAYOUT -> acc[4] = acc[4].add(sum);
                default -> { }
            }
        }
        List<StatementRow> rows = new ArrayList<>();
        for (StaffCommissionPlan plan : staffProfileService.commissionPlans(businessId)) {
            BigDecimal open = opening.getOrDefault(plan.staffProfileId(), ZERO);
            BigDecimal[] acc = moved.get(plan.staffProfileId());
            if (acc == null && open.signum() == 0 && !(plan.current() && plan.bookable())) {
                continue;
            }
            if (acc == null) {
                acc = new BigDecimal[] { ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, BigDecimal.ZERO };
            }
            BigDecimal closing = open.add(acc[0]).add(acc[1]).add(acc[2]).add(acc[3]).add(acc[4]);
            rows.add(new StatementRow(plan.staffProfileId(), plan.displayName(), plan.current(),
                    acc[6].longValue(), acc[5], open, acc[0], acc[1], acc[2], acc[3], acc[4].negate(), closing));
        }
        BigDecimal ledgerTotal = money(ledgerRepository.totalOwed(businessId));
        BigDecimal glTotal = ledgerBalanceQuery.creditBalance(businessId, PAYABLE_CODES);
        return new StatementResponse(from, to, rows, ledgerTotal, glTotal, ledgerTotal.compareTo(glTotal) == 0);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> entries(String businessId, String staffProfileId, LocalDate from, LocalDate to) {
        requireStaff(businessId, staffProfileId);
        Instant start = from.atStartOfDay(NAIROBI).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        return ledgerRepository.findForStaffBetween(businessId, staffProfileId, start, end).stream()
                .map(StaffEarningsService::toResponse)
                .toList();
    }

    /** What a staff member is owed right now, tips and commission together. */
    @Transactional(readOnly = true)
    public BigDecimal balance(String businessId, String staffProfileId) {
        return money(ledgerRepository.balance(businessId, staffProfileId, StaffLedgerKinds.BUCKET_TIPS))
                .add(money(ledgerRepository.balance(businessId, staffProfileId, StaffLedgerKinds.BUCKET_COMMISSION)));
    }

    // ------------------------------------------------------------------ helpers

    /** The commission rule for a service line: the service's own override, else the staff plan. */
    private Rule serviceRule(String businessId, String serviceId, StaffCommissionPlan plan) {
        if (serviceId != null) {
            ServiceOffering service = serviceRepository.findById(serviceId)
                    .filter(s -> businessId.equals(s.getBusinessId()))
                    .orElse(null);
            if (service != null && service.getCommissionType() != null && service.getCommissionValue() != null) {
                return new Rule(service.getCommissionType(), service.getCommissionValue(), "service");
            }
        }
        if (plan != null && plan.type() != null && plan.rate() != null) {
            return new Rule(plan.type(), plan.rate(), "staff");
        }
        return new Rule(null, null, null);
    }

    private record Rule(String type, BigDecimal value, String origin) {

        BigDecimal apply(BigDecimal base, BigDecimal quantity) {
            if (type == null || value == null || base.signum() <= 0) {
                return ZERO;
            }
            BigDecimal raw = "PERCENT".equals(type)
                    ? base.multiply(value).divide(HUNDRED, 4, RoundingMode.HALF_UP)
                    : value.multiply(quantity == null ? BigDecimal.ONE : quantity);
            // A fixed amount can never exceed what the customer paid for the line.
            return wholeShillings(raw).min(base.setScale(0, RoundingMode.DOWN).setScale(2, RoundingMode.HALF_UP));
        }

        String label() {
            return type == null ? "NONE" : type + " " + value.toPlainString() + " (" + origin + ")";
        }
    }

    /**
     * Splits a whole-shilling {@code total} across rows in proportion to their bases. Each row gets
     * its share rounded down; the shillings left over go one at a time to the rows that lost the
     * largest fraction, so the parts always add up to the total exactly.
     */
    static List<BigDecimal> shareOut(BigDecimal total, List<BigDecimal> bases, BigDecimal baseTotal) {
        List<BigDecimal> out = new ArrayList<>(bases.size());
        if (total.signum() <= 0 || baseTotal.signum() <= 0) {
            for (int i = 0; i < bases.size(); i++) {
                out.add(ZERO);
            }
            return out;
        }
        List<BigDecimal> fractions = new ArrayList<>(bases.size());
        BigDecimal given = BigDecimal.ZERO;
        for (BigDecimal base : bases) {
            BigDecimal exact = total.multiply(base).divide(baseTotal, 6, RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(0, RoundingMode.DOWN);
            out.add(floor.setScale(2, RoundingMode.HALF_UP));
            fractions.add(exact.subtract(floor));
            given = given.add(floor);
        }
        int left = total.subtract(given).intValue();
        while (left > 0) {
            int best = -1;
            for (int i = 0; i < fractions.size(); i++) {
                if (best < 0 || fractions.get(i).compareTo(fractions.get(best)) > 0) {
                    best = i;
                }
            }
            out.set(best, out.get(best).add(BigDecimal.ONE));
            fractions.set(best, BigDecimal.valueOf(-1));
            left--;
        }
        return out;
    }

    private BigDecimal alreadyReversed(String businessId, String entryId) {
        BigDecimal sum = ZERO;
        for (StaffLedgerEntry reversal : ledgerRepository.findByBusinessIdAndReversesId(businessId, entryId)) {
            sum = sum.add(reversal.getAmount());
        }
        return sum;
    }

    private void requireStaff(String businessId, String staffProfileId) {
        if (staffProfileService.commissionPlan(businessId, staffProfileId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        }
    }

    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 150) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is too long");
        }
        return key;
    }

    private static String normalizeMethod(String method) {
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!StaffLedgerKinds.METHOD_DRAWER.equals(m)
                && !StaffLedgerKinds.METHOD_MPESA.equals(m)
                && !StaffLedgerKinds.METHOD_OTHER.equals(m)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pay from DRAWER, MPESA or OTHER");
        }
        return m;
    }

    private static String cashAccount(String payoutMethod) {
        if (StaffLedgerKinds.METHOD_DRAWER.equals(payoutMethod)) {
            return LedgerAccountCodes.OPERATING_CASH;
        }
        return StaffLedgerKinds.METHOD_MPESA.equals(payoutMethod)
                ? LedgerAccountCodes.MPESA_CLEARING
                : LedgerAccountCodes.BANK_ACCOUNT;
    }

    private StaffLedgerEntry payoutRow(
            String businessId, String branchId, String staffProfileId, String bucket, BigDecimal amount,
            String dedupeKey, String method, String reference, String shiftId, String journalId,
            Instant at, String userId
    ) {
        StaffLedgerEntry row = entry(businessId, branchId, staffProfileId, StaffLedgerKinds.PAYOUT, bucket,
                amount.negate(), dedupeKey, at, userId);
        row.setPayoutMethod(method);
        row.setReference(reference == null || reference.isBlank() ? null
                : reference.trim().substring(0, Math.min(191, reference.trim().length())));
        row.setShiftId(shiftId);
        row.setJournalEntryId(journalId);
        return row;
    }

    private static StaffLedgerEntry entry(
            String businessId, String branchId, String staffProfileId, String kind, String bucket,
            BigDecimal amount, String dedupeKey, Instant occurredAt, String userId
    ) {
        StaffLedgerEntry row = new StaffLedgerEntry();
        row.setBusinessId(businessId);
        row.setBranchId(branchId == null || branchId.isBlank() ? null : branchId);
        row.setStaffProfileId(staffProfileId);
        row.setKind(kind);
        row.setBucket(bucket);
        row.setAmount(money(amount));
        row.setDedupeKey(dedupeKey);
        row.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
        row.setCreatedBy(userId);
        return row;
    }

    /** A second writer with the same dedupe key loses to the unique key; report it as a conflict. */
    private void saveOnce(StaffLedgerEntry row) {
        try {
            ledgerRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            String cause = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
            if (cause.contains("uq_staff_ledger_dedupe")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This was already recorded; refresh and check");
            }
            // Anything else is a real fault and must not be dressed up as a duplicate.
            throw ex;
        }
    }

    private String postTwoLine(
            String businessId, String branchId, String sourceType, String sourceId, String memo,
            String debitCode, String creditCode, BigDecimal amount
    ) {
        JournalEntry journal = journal(businessId, branchId, sourceType, sourceId, memo);
        journal.debit(ledgerAccountResolver.resolveId(businessId, debitCode), money(amount));
        journal.credit(ledgerAccountResolver.resolveId(businessId, creditCode), money(amount));
        return ledgerPostingPort.post(journal);
    }

    private static JournalEntry journal(
            String businessId, String branchId, String sourceType, String sourceId, String memo) {
        JournalEntry journal = new JournalEntry();
        journal.setBusinessId(businessId);
        journal.setBranchId(branchId == null || branchId.isBlank() ? null : branchId);
        journal.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        journal.setSourceType(sourceType);
        journal.setSourceId(sourceId);
        journal.setMemo(memo);
        return journal;
    }

    private void audit(
            String businessId, String branchId, String userId, String type, String staffProfileId,
            Map<String, Object> state
    ) {
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.STAFF, type, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(branchId)
                .actor(userId, AuditEventActorType.USER)
                .target("staff_profile", staffProfileId)
                .newState(new LinkedHashMap<>(state))
                .source("dashboard")
                .build());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal wholeShillings(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    private static LedgerEntryResponse toResponse(StaffLedgerEntry e) {
        return new LedgerEntryResponse(
                e.getId(), e.getStaffProfileId(), e.getBranchId(), e.getKind(), e.getBucket(), e.getAmount(),
                e.getBaseAmount(), e.getRule(), e.getSource(), e.getSaleId(), e.getSaleItemId(),
                e.getReversesId(), e.getPayoutMethod(), e.getReference(), e.getReason(), e.getOccurredAt());
    }
}
