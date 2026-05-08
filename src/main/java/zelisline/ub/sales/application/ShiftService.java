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
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
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
    private final CashDrawerSummaryService cashDrawerSummaryService;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;

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
        BigDecimal amt = variance.abs().setScale(2, RoundingMode.HALF_UP);
        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        entry.setSourceType(SalesConstants.JOURNAL_SOURCE_SHIFT_CLOSE);
        entry.setSourceId(shiftId);
        entry.setMemo("Shift close variance " + shiftId);
        if (variance.signum() < 0) {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.CASH_OVER_SHORT), amt);
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.OPERATING_CASH), amt);
        } else {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.OPERATING_CASH), amt);
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.CASH_OVER_SHORT), amt);
        }
        return ledgerPostingPort.post(entry);
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
}
