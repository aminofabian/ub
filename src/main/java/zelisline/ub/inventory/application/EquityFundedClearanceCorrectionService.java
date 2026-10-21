package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.inventory.InventoryConstants;

/**
 * One-off repair for equity-funded supply-batch clearances that wrongly debited
 * inventory shrinkage ({@code 5210}).
 *
 * <p>Opening-balance and stock-count-gain lots were funded by
 * {@code Dr Inventory / Cr Opening balance equity}. Clearing them used to post
 * {@code Dr Shrinkage / Cr Inventory}, inventing operating loss. The forward fix
 * reverses equity instead; this service posts correcting journals for history:
 * {@code Dr Opening balance equity / Cr Shrinkage} on the original entry date
 * (inventory is already correctly reduced).
 *
 * <p>Idempotent: each correction is keyed
 * {@code source_type = equity_clearance_correction}, {@code source_id =} the
 * original clearance journal id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EquityFundedClearanceCorrectionService {

    private final JournalEntryRepository journalEntryRepository;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;

    @Transactional(readOnly = true)
    public CorrectionSummary preview(String businessId) {
        return run(businessId, true);
    }

    @Transactional
    public CorrectionSummary apply(String businessId) {
        return run(businessId, false);
    }

    private CorrectionSummary run(String businessId, boolean dryRun) {
        List<JournalEntryRepository.EquityClearanceMispost> rows =
                journalEntryRepository.findUncorrectedEquityClearanceShrinkage(
                        blankToNull(businessId),
                        InventoryConstants.JOURNAL_BATCH_CLEARANCE,
                        InventoryConstants.JOURNAL_EQUITY_CLEARANCE_CORRECTION,
                        InventoryConstants.BATCH_SOURCE_OPENING,
                        InventoryConstants.BATCH_SOURCE_STOCK_GAIN,
                        LedgerAccountCodes.INVENTORY_SHRINKAGE);

        int corrected = 0;
        int skipped = 0;
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<CorrectionLine> lines = new ArrayList<>();

        for (JournalEntryRepository.EquityClearanceMispost row : rows) {
            BigDecimal amount = money(row.getShrinkageDebit());
            if (amount.signum() <= 0) {
                skipped++;
                continue;
            }
            if (journalEntryRepository.existsByBusinessIdAndSourceTypeAndSourceId(
                    row.getBusinessId(),
                    InventoryConstants.JOURNAL_EQUITY_CLEARANCE_CORRECTION,
                    row.getJournalEntryId())) {
                skipped++;
                continue;
            }

            String correctionJeId = null;
            if (!dryRun) {
                correctionJeId = postCorrection(row, amount);
            }
            corrected++;
            total = total.add(amount);
            lines.add(new CorrectionLine(
                    row.getJournalEntryId(),
                    correctionJeId,
                    row.getBusinessId(),
                    row.getBranchId(),
                    row.getEntryDate(),
                    row.getSupplyBatchId(),
                    row.getBatchNumber(),
                    row.getBatchSourceType(),
                    amount));
        }

        CorrectionSummary summary = new CorrectionSummary(
                dryRun,
                rows.size(),
                corrected,
                skipped,
                total,
                List.copyOf(lines));
        log.info("Equity-funded clearance correction (businessId={}, dryRun={}): candidates={}, corrected={}, total={}",
                businessId, dryRun, rows.size(), corrected, total);
        return summary;
    }

    private String postCorrection(
            JournalEntryRepository.EquityClearanceMispost row,
            BigDecimal amount
    ) {
        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(row.getBusinessId());
        entry.setBranchId(row.getBranchId());
        entry.setEntryDate(row.getEntryDate() != null ? row.getEntryDate() : LocalDate.now());
        entry.setSourceType(InventoryConstants.JOURNAL_EQUITY_CLEARANCE_CORRECTION);
        entry.setSourceId(row.getJournalEntryId());
        entry.setMemo("Correct equity clearance "
                + row.getBatchNumber()
                + " — reverse shrinkage to opening equity");

        entry.debit(
                ledgerAccountResolver.resolveId(
                        row.getBusinessId(), LedgerAccountCodes.OPENING_BALANCE_EQUITY),
                amount);
        entry.credit(
                ledgerAccountResolver.resolveId(
                        row.getBusinessId(), LedgerAccountCodes.INVENTORY_SHRINKAGE),
                amount);
        return ledgerPostingPort.post(entry);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public record CorrectionLine(
            String originalJournalEntryId,
            String correctionJournalEntryId,
            String businessId,
            String branchId,
            LocalDate entryDate,
            String supplyBatchId,
            String batchNumber,
            String batchSourceType,
            BigDecimal amount
    ) {
    }

    public record CorrectionSummary(
            boolean dryRun,
            int candidates,
            int corrected,
            int skipped,
            BigDecimal totalAmount,
            List<CorrectionLine> lines
    ) {
    }
}
