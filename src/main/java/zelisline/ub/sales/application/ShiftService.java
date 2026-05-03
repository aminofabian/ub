package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.CashDrawerSummaryService;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.PostCloseShiftRequest;
import zelisline.ub.sales.api.dto.PostOpenShiftRequest;
import zelisline.ub.sales.api.dto.ShiftResponse;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class ShiftService {

    private static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");

    private final ShiftRepository shiftRepository;
    private final BranchRepository branchRepository;
    private final LedgerBootstrapService ledgerBootstrapService;
    private final CashDrawerSummaryService cashDrawerSummaryService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    @Transactional
    public ShiftResponse openShift(String businessId, PostOpenShiftRequest req, String userId) {
        String branchId = req.branchId();
        requireBranch(businessId, branchId);
        if (shiftRepository.findByBusinessIdAndBranchIdAndStatus(
                businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shift already open for branch");
        }
        BigDecimal opening = req.openingCash().setScale(2, RoundingMode.HALF_UP);
        Shift s = new Shift();
        s.setBusinessId(businessId);
        s.setBranchId(branchId);
        s.setOpenedBy(userId);
        s.setStatus(SalesConstants.SHIFT_STATUS_OPEN);
        s.setOpeningCash(opening);
        s.setExpectedClosingCash(opening);
        s.setOpeningNotes(blankToNull(req.notes()));
        shiftRepository.save(s);
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public ShiftResponse getCurrentOpenShift(String businessId, String branchId) {
        requireBranch(businessId, branchId);
        Shift s = shiftRepository
                .findByBusinessIdAndBranchIdAndStatus(businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No open shift"));
        return toDto(s);
    }

    @Transactional
    public ShiftResponse closeShift(String businessId, String shiftId, PostCloseShiftRequest req, String userId) {
        Shift s = shiftRepository.findByIdAndBusinessIdForUpdate(shiftId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift not found"));
        if (!SalesConstants.SHIFT_STATUS_OPEN.equals(s.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shift is not open");
        }
        BigDecimal counted = req.countedClosingCash().setScale(2, RoundingMode.HALF_UP);
        BigDecimal expected = s.getExpectedClosingCash().setScale(2, RoundingMode.HALF_UP);
        BigDecimal variance = counted.subtract(expected).setScale(2, RoundingMode.HALF_UP);
        s.setCountedClosingCash(counted);
        s.setClosingVariance(variance);
        s.setClosingNotes(blankToNull(req.notes()));
        s.setClosedBy(userId);
        s.setClosedAt(Instant.now());
        s.setStatus(SalesConstants.SHIFT_STATUS_CLOSED);
        if (variance.abs().compareTo(MONEY_TOLERANCE) > 0) {
            s.setCloseJournalEntryId(postVarianceJournal(businessId, s.getId(), variance));
        }
        shiftRepository.save(s);
        cashDrawerSummaryService.upsertForClosedShift(s);
        return toDto(s);
    }

    private String postVarianceJournal(String businessId, String shiftId, BigDecimal variance) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        LedgerAccount cash = ledger(businessId, LedgerAccountCodes.OPERATING_CASH);
        LedgerAccount oos = ledger(businessId, LedgerAccountCodes.CASH_OVER_SHORT);
        BigDecimal amt = variance.abs().setScale(2, RoundingMode.HALF_UP);
        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        je.setSourceType(SalesConstants.JOURNAL_SOURCE_SHIFT_CLOSE);
        je.setSourceId(shiftId);
        je.setMemo("Shift close variance " + shiftId);
        journalEntryRepository.save(je);
        List<JournalLine> lines = new ArrayList<>();
        if (variance.signum() < 0) {
            lines.add(journalDebit(je.getId(), oos.getId(), amt));
            lines.add(journalCredit(je.getId(), cash.getId(), amt));
        } else {
            lines.add(journalDebit(je.getId(), cash.getId(), amt));
            lines.add(journalCredit(je.getId(), oos.getId(), amt));
        }
        journalLineRepository.saveAll(lines);
        assertBalanced(lines);
        return je.getId();
    }

    private LedgerAccount ledger(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + code));
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private static ShiftResponse toDto(Shift s) {
        return new ShiftResponse(
                s.getId(),
                s.getBranchId(),
                s.getStatus(),
                s.getOpeningCash(),
                s.getExpectedClosingCash(),
                s.getCountedClosingCash(),
                s.getClosingVariance(),
                s.getOpeningNotes(),
                s.getClosingNotes(),
                s.getOpenedBy(),
                s.getClosedBy(),
                s.getOpenedAt(),
                s.getClosedAt(),
                s.getCloseJournalEntryId()
        );
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static void assertBalanced(List<JournalLine> lines) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine l : lines) {
            dr = dr.add(l.getDebit());
            cr = cr.add(l.getCredit());
        }
        if (dr.subtract(cr).abs().compareTo(MONEY_TOLERANCE) > 0) {
            throw new IllegalStateException("Unbalanced journal");
        }
    }

    private static JournalLine journalDebit(String entryId, String accId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accId);
        l.setDebit(amount.setScale(2, RoundingMode.HALF_UP));
        l.setCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        return l;
    }

    private static JournalLine journalCredit(String entryId, String accId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accId);
        l.setDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        l.setCredit(amount.setScale(2, RoundingMode.HALF_UP));
        return l;
    }
}
