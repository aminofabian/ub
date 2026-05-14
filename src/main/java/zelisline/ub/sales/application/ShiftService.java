package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.DenominationEntry;
import zelisline.ub.sales.api.dto.DenominationResponse;
import zelisline.ub.sales.api.dto.PostCloseShiftRequest;
import zelisline.ub.sales.api.dto.PostOpenShiftRequest;
import zelisline.ub.sales.api.dto.ShiftAuditEntryResponse;
import zelisline.ub.sales.api.dto.ShiftDetailResponse;
import zelisline.ub.sales.api.dto.ShiftExpenseResponse;
import zelisline.ub.sales.api.dto.ShiftListResponse;
import zelisline.ub.sales.api.dto.ShiftListItemResponse;
import zelisline.ub.sales.api.dto.ShiftResponse;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.domain.ShiftAuditLog;
import zelisline.ub.sales.domain.ShiftDenomination;
import zelisline.ub.sales.domain.ShiftExpense;
import zelisline.ub.sales.repository.ShiftAuditLogRepository;
import zelisline.ub.sales.repository.ShiftDenominationRepository;
import zelisline.ub.sales.repository.ShiftExpenseRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class ShiftService {

    private static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");

    private final ShiftRepository shiftRepository;
    private final ShiftDenominationRepository shiftDenominationRepository;
    private final ShiftAuditLogRepository shiftAuditLogRepository;
    private final ShiftExpenseRepository shiftExpenseRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final CashDrawerSummaryService cashDrawerSummaryService;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final ApplicationEventPublisher eventPublisher;

    // ========================================================================
    // OPEN SHIFT
    // ========================================================================

    @Transactional
    public ShiftResponse openShift(String businessId, PostOpenShiftRequest req, String userId) {
        String branchId = req.branchId();
        Branch branch = requireBranch(businessId, branchId);

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

        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.ShiftOpenedEvent(
                businessId, branchId, s.getId(), userId, opening));

        // Save opening denominations if provided
        List<DenominationResponse> openingDenoms = Collections.emptyList();
        if (req.denominations() != null && !req.denominations().isEmpty()) {
            openingDenoms = saveDenominations(s.getId(), SalesConstants.DENOM_COUNT_TYPE_OPENING, req.denominations());
            // Recompute opening cash from denominations if they were provided
            BigDecimal denomTotal = openingDenoms.stream()
                    .map(DenominationResponse::total)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            s.setOpeningCash(denomTotal);
            s.setExpectedClosingCash(denomTotal);
        }

        // Record audit log
        recordAudit(s.getId(), SalesConstants.AUDIT_SHIFT_OPENED, userId,
                "{\"openingCash\":\"" + s.getOpeningCash() + "\"}", null);

        return toDto(s, branch.getName(), userId, openingDenoms, null);
    }

    // ========================================================================
    // GET CURRENT OPEN SHIFT
    // ========================================================================

    @Transactional(readOnly = true)
    public ShiftResponse getCurrentOpenShift(String businessId, String branchId) {
        Branch branch = requireBranch(businessId, branchId);
        Shift s = shiftRepository
                .findByBusinessIdAndBranchIdAndStatus(businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No open shift"));

        List<DenominationResponse> openingDenoms = shiftDenominationRepository
                .findByShiftIdAndCountTypeOrderByDenominationDesc(s.getId(), SalesConstants.DENOM_COUNT_TYPE_OPENING)
                .stream()
                .map(ShiftService::toDenomDto)
                .toList();
        List<DenominationResponse> closingDenoms = shiftDenominationRepository
                .findByShiftIdAndCountTypeOrderByDenominationDesc(s.getId(), SalesConstants.DENOM_COUNT_TYPE_CLOSING)
                .stream()
                .map(ShiftService::toDenomDto)
                .toList();

        return toDto(s, branch.getName(), s.getOpenedBy(), openingDenoms, closingDenoms.isEmpty() ? null : closingDenoms);
    }

    // ========================================================================
    // CLOSE SHIFT
    // ========================================================================

    @Transactional
    public ShiftResponse closeShift(String businessId, String shiftId, PostCloseShiftRequest req, String userId) {
        Shift s = shiftRepository.findByIdAndBusinessIdForUpdate(shiftId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift not found"));
        if (!SalesConstants.SHIFT_STATUS_OPEN.equals(s.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shift is not open");
        }

        String branchName = getBranchName(businessId, s.getBranchId());

        BigDecimal counted = req.countedClosingCash().setScale(2, RoundingMode.HALF_UP);
        BigDecimal expected = s.getExpectedClosingCash().setScale(2, RoundingMode.HALF_UP);

        // Save closing denominations if provided
        List<DenominationResponse> closingDenoms = Collections.emptyList();
        if (req.denominations() != null && !req.denominations().isEmpty()) {
            closingDenoms = saveDenominations(s.getId(), SalesConstants.DENOM_COUNT_TYPE_CLOSING, req.denominations());
            BigDecimal denomTotal = closingDenoms.stream()
                    .map(DenominationResponse::total)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            counted = denomTotal;
            s.setCountedClosingCash(counted);
        }

        BigDecimal variance = counted.subtract(expected).setScale(2, RoundingMode.HALF_UP);
        s.setCountedClosingCash(counted);
        s.setClosingVariance(variance);
        s.setClosingNotes(blankToNull(req.notes()));
        s.setVarianceReason(blankToNull(req.varianceReason()));
        s.setClosedBy(userId);
        s.setClosedAt(Instant.now());
        s.setStatus(SalesConstants.SHIFT_STATUS_CLOSED);

        if (variance.abs().compareTo(MONEY_TOLERANCE) > 0) {
            s.setCloseJournalEntryId(postVarianceJournal(businessId, s.getId(), variance));
        }
        shiftRepository.save(s);

        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.ShiftClosedEvent(
                businessId, s.getBranchId(), shiftId, userId, expected, counted, variance));

        cashDrawerSummaryService.upsertForClosedShift(s);

        // Record audit log
        String meta = String.format(
                "{\"counted\":\"%s\",\"expected\":\"%s\",\"variance\":\"%s\"}",
                counted, expected, variance);
        recordAudit(s.getId(), SalesConstants.AUDIT_SHIFT_CLOSED, userId, meta, null);

        List<DenominationResponse> openingDenoms = shiftDenominationRepository
                .findByShiftIdAndCountTypeOrderByDenominationDesc(s.getId(), SalesConstants.DENOM_COUNT_TYPE_OPENING)
                .stream()
                .map(ShiftService::toDenomDto)
                .toList();

        return toDto(s, branchName, s.getOpenedBy(), openingDenoms, closingDenoms);
    }

    // ========================================================================
    // LIST SHIFTS
    // ========================================================================

    @Transactional(readOnly = true)
    public ShiftListResponse listShifts(
            String businessId,
            String branchId,
            String status,
            String openedBy,
            int page,
            int size
    ) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "openedAt"));
        Page<Shift> shiftPage;

        if (openedBy != null && !openedBy.isBlank()) {
            if (branchId != null && !branchId.isBlank()) {
                shiftPage = shiftRepository.findByBusinessIdAndOpenedByAndBranchIdOrderByOpenedAtDesc(
                        businessId, openedBy, branchId, pageRequest);
            } else {
                shiftPage = shiftRepository.findByBusinessIdAndOpenedByOrderByOpenedAtDesc(
                        businessId, openedBy, pageRequest);
            }
        } else if (branchId != null && !branchId.isBlank() || status != null && !status.isBlank()) {
            shiftPage = shiftRepository.findByBusinessIdFiltered(
                    businessId,
                    blankToNull(branchId),
                    blankToNull(status),
                    pageRequest);
        } else {
            shiftPage = shiftRepository.findByBusinessIdOrderByOpenedAtDesc(businessId, pageRequest);
        }

        // Cache branch and user names
        Map<String, String> branchNames = new HashMap<>();
        Map<String, String> userNames = new HashMap<>();

        List<ShiftListItemResponse> items = shiftPage.getContent().stream()
                .map(s -> toListItem(s, branchNames, userNames))
                .toList();

        return new ShiftListResponse(
                items,
                (int) shiftPage.getTotalElements(),
                page,
                size,
                shiftPage.hasNext()
        );
    }

    // ========================================================================
    // GET SHIFT DETAIL
    // ========================================================================

    @Transactional(readOnly = true)
    public ShiftDetailResponse getShiftDetail(String businessId, String shiftId) {
        Shift s = shiftRepository.findByIdAndBusinessId(shiftId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift not found"));

        String branchName = getBranchName(businessId, s.getBranchId());
        String openedByName = getUserName(businessId, s.getOpenedBy());
        String closedByName = s.getClosedBy() != null ? getUserName(businessId, s.getClosedBy()) : null;
        String reconciledByName = s.getReconciledBy() != null ? getUserName(businessId, s.getReconciledBy()) : null;

        List<DenominationResponse> openingDenoms = shiftDenominationRepository
                .findByShiftIdAndCountTypeOrderByDenominationDesc(s.getId(), SalesConstants.DENOM_COUNT_TYPE_OPENING)
                .stream()
                .map(ShiftService::toDenomDto)
                .toList();

        List<DenominationResponse> closingDenoms = shiftDenominationRepository
                .findByShiftIdAndCountTypeOrderByDenominationDesc(s.getId(), SalesConstants.DENOM_COUNT_TYPE_CLOSING)
                .stream()
                .map(ShiftService::toDenomDto)
                .toList();

        List<ShiftExpenseResponse> expenses = shiftExpenseRepository
                .findByShiftIdOrderByCreatedAtDesc(s.getId())
                .stream()
                .map(e -> {
                    String authName = e.getAuthorisedBy() != null
                            ? getUserName(businessId, e.getAuthorisedBy()) : null;
                    return new ShiftExpenseResponse(
                            e.getId(), e.getType(), e.getAmount(),
                            e.getDescription(), e.getAuthorisedBy(), authName, e.getCreatedAt());
                })
                .toList();

        List<ShiftAuditEntryResponse> auditLog = shiftAuditLogRepository
                .findByShiftIdOrderByCreatedAtAsc(s.getId())
                .stream()
                .map(a -> {
                    String performerName = a.getPerformedBy() != null
                            ? getUserName(businessId, a.getPerformedBy()) : null;
                    return new ShiftAuditEntryResponse(
                            a.getId(), a.getEventType(), a.getPerformedBy(),
                            performerName, a.getMetadata(), a.getIpAddress(), a.getCreatedAt());
                })
                .toList();

        return new ShiftDetailResponse(
                s.getId(), s.getBranchId(), branchName, s.getStatus(),
                s.getOpeningCash(), s.getExpectedClosingCash(),
                s.getCountedClosingCash(), s.getClosingVariance(),
                s.getOpeningNotes(), s.getClosingNotes(), s.getVarianceReason(),
                s.getOpenedBy(), openedByName,
                s.getClosedBy(), closedByName,
                reconciledByName,
                s.getOpenedAt(), s.getClosedAt(), s.getReconciledAt(),
                s.isBlindClosing(),
                openingDenoms, closingDenoms,
                Collections.emptyList(), // sales summary - would be queried from transactions
                expenses, auditLog
        );
    }

    // ========================================================================
    // DENOMINATION HELPERS
    // ========================================================================

    /**
     * Determines whether a denomination is a NOTE or COIN based on KES currency.
     */
    public static String determineDenominationType(int denomination) {
        return switch (denomination) {
            case 1000, 500, 200, 100, 50 -> SalesConstants.DENOM_TYPE_NOTE;
            case 40, 20, 10, 5, 1 -> SalesConstants.DENOM_TYPE_COIN;
            default -> SalesConstants.DENOM_TYPE_COIN;
        };
    }

    private List<DenominationResponse> saveDenominations(
            String shiftId, String countType, List<DenominationEntry> entries) {
        // Delete any existing entries for this count type
        shiftDenominationRepository.deleteByShiftIdAndCountType(shiftId, countType);

        List<ShiftDenomination> entities = new ArrayList<>();
        for (DenominationEntry entry : entries) {
            if (entry.quantity() <= 0) {
                continue; // skip zero-quantity entries
            }
            BigDecimal denomValue = BigDecimal.valueOf(entry.denomination());
            BigDecimal total = denomValue.multiply(BigDecimal.valueOf(entry.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            String denomType = entry.denominationType() != null
                    ? entry.denominationType()
                    : determineDenominationType(entry.denomination());

            ShiftDenomination sd = new ShiftDenomination();
            sd.setShiftId(shiftId);
            sd.setCountType(countType);
            sd.setDenomination(entry.denomination());
            sd.setDenominationType(denomType);
            sd.setQuantity(entry.quantity());
            sd.setTotal(total);
            entities.add(sd);
        }
        shiftDenominationRepository.saveAll(entities);

        return entities.stream()
                .map(ShiftService::toDenomDto)
                .toList();
    }

    // ========================================================================
    // AUDIT LOGGING
    // ========================================================================

    private void recordAudit(String shiftId, String eventType, String performedBy,
                              String metadata, String ipAddress) {
        ShiftAuditLog log = new ShiftAuditLog();
        log.setShiftId(shiftId);
        log.setEventType(eventType);
        log.setPerformedBy(performedBy);
        log.setMetadata(metadata);
        log.setIpAddress(ipAddress);
        shiftAuditLogRepository.save(log);
    }

    // ========================================================================
    // LEDGER POSTING
    // ========================================================================

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

    // ========================================================================
    // LOOKUP HELPERS
    // ========================================================================

    private Branch requireBranch(String businessId, String branchId) {
        return branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private String getBranchName(String businessId, String branchId) {
        return branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .map(Branch::getName)
                .orElse("Unknown Branch");
    }

    private String getUserName(String businessId, String userId) {
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .map(User::getName)
                .orElse("Unknown User");
    }

    // ========================================================================
    // DTO CONVERSION
    // ========================================================================

    private ShiftResponse toDto(Shift s, String branchName, String currentUserId,
                                 List<DenominationResponse> openingDenoms,
                                 List<DenominationResponse> closingDenoms) {
        String openedByName = s.getOpenedBy().equals(currentUserId)
                ? "You" : getUserName(s.getBusinessId(), s.getOpenedBy());
        String closedByName = s.getClosedBy() != null
                ? (s.getClosedBy().equals(currentUserId) ? "You" : getUserName(s.getBusinessId(), s.getClosedBy()))
                : null;

        return new ShiftResponse(
                s.getId(), s.getBranchId(), branchName, s.getStatus(),
                s.getOpeningCash(), s.getExpectedClosingCash(),
                s.getCountedClosingCash(), s.getClosingVariance(),
                s.getOpeningNotes(), s.getClosingNotes(), s.getVarianceReason(),
                s.getOpenedBy(), openedByName,
                s.getClosedBy(), closedByName,
                s.getOpenedAt(), s.getClosedAt(),
                s.getCloseJournalEntryId(),
                openingDenoms, closingDenoms
        );
    }

    private ShiftListItemResponse toListItem(Shift s, Map<String, String> branchNames,
                                              Map<String, String> userNames) {
        String branchName = branchNames.computeIfAbsent(s.getBranchId(),
                k -> getBranchName(s.getBusinessId(), s.getBranchId()));
        String cashierName = userNames.computeIfAbsent(s.getOpenedBy(),
                k -> getUserName(s.getBusinessId(), s.getOpenedBy()));

        return new ShiftListItemResponse(
                s.getId(), s.getBranchId(), branchName, s.getStatus(),
                cashierName, s.getOpenedBy(),
                s.getOpenedAt(), s.getClosedAt(),
                s.getOpeningCash(), s.getCountedClosingCash(),
                s.getExpectedClosingCash(), s.getClosingVariance(),
                0, BigDecimal.ZERO,
                null, null
        );
    }

    private static DenominationResponse toDenomDto(ShiftDenomination sd) {
        return new DenominationResponse(
                sd.getId(), sd.getCountType(), sd.getDenomination(),
                sd.getDenominationType(), sd.getQuantity(), sd.getTotal());
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
