package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.domain.ShiftAuditLog;
import zelisline.ub.sales.repository.ShiftAuditLogRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Lets a business run without cash-drawer shifts.
 *
 * <p>Every sale row still points at a shift ({@code sales.shift_id} is NOT NULL and the
 * drawer, drawout and summary maths all hang off it), so "no shifts" is implemented as
 * <em>no shift the cashier has to know about</em>: when {@code shifts.disabled} is on and a
 * till has no open shift, the first sale opens a hidden one ({@link Shift#isAutoManaged()})
 * and every later sale on that till reuses it.
 *
 * <ul>
 *   <li>The hidden shift never rolls over, because closing it would cancel the till's
 *       unfinished carts. It stays open until shifts are switched back on, at which point
 *       {@link ShiftService#closeAutoManagedShifts} closes it.</li>
 *   <li>It opens with a zero float and is never counted, so it carries no variance.</li>
 *   <li>It is silent on the realtime and ops-alert bus: nobody opened it, so nobody should
 *       be told a shift opened.</li>
 *   <li>The trusted-till rule still applies: a branch with registered tills only sells from
 *       them, shifts or no shifts.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AutoShiftService {

    static final String OPENING_NOTE = "Opened automatically: this business has shifts turned off.";

    private final FeatureFlagService featureFlagService;
    private final BranchRepository branchRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftAuditLogRepository shiftAuditLogRepository;
    private final OpenShiftResolver openShiftResolver;
    private final CashDrawerLedgerService cashDrawerLedgerService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    public boolean isShiftsDisabled(String businessId) {
        return featureFlagService.isShiftsDisabled(businessId);
    }

    /**
     * Called by a sale path that found no open shift for the till.
     *
     * <p>Joins the caller's transaction (or starts one) so the shift is committed or rolled
     * back together with the sale that needed it.
     *
     * @return the open shift to sell against, or empty when the business has shifts turned
     *         on, in which case the caller reports that a shift must be opened
     * @throws zelisline.ub.platform.web.SetupRequiredException (till) when the branch only
     *         trusts registered tills and this device is not one of them
     */
    @Transactional
    public Optional<Shift> openIfShiftsDisabled(
            String businessId,
            String branchId,
            String tillDeviceHeader,
            String userId
    ) {
        if (!featureFlagService.isShiftsDisabled(businessId)) {
            return Optional.empty();
        }
        String tillKey = openShiftResolver.resolveTillKeyForOpen(businessId, branchId, tillDeviceHeader);

        // Two tills making their first sale at once must not both open a hidden shift. The
        // branch row is the one thing they share, so queue on it, then look again: a locking
        // read sees what the winner just committed even from a repeatable-read snapshot.
        if (branchRepository.findByIdAndBusinessIdForUpdate(branchId, businessId).isEmpty()) {
            return Optional.empty();
        }
        Optional<Shift> alreadyOpen = openShiftResolver.findOpenForUpdate(businessId, branchId, tillDeviceHeader);
        if (alreadyOpen.isPresent()) {
            return alreadyOpen;
        }

        Shift s = new Shift();
        s.setBusinessId(businessId);
        s.setBranchId(branchId);
        s.setOpenedBy(userId);
        s.setTillDeviceKey(tillKey);
        s.setStatus(SalesConstants.SHIFT_STATUS_OPEN);
        s.setOpeningCash(BigDecimal.ZERO.setScale(2));
        s.setExpectedClosingCash(BigDecimal.ZERO.setScale(2));
        s.setOpeningNotes(OPENING_NOTE);
        s.setAutoManaged(true);
        shiftRepository.saveAndFlush(s);

        cashDrawerLedgerService.recordOpening(s.getId(), null, s.getOpeningCash(), userId);
        recordAudit(s, businessId, userId);
        return Optional.of(s);
    }

    private void recordAudit(Shift s, String businessId, String userId) {
        ShiftAuditLog log = new ShiftAuditLog();
        log.setShiftId(s.getId());
        log.setEventType(SalesConstants.AUDIT_SHIFT_OPENED);
        log.setPerformedBy(userId);
        log.setMetadata("{\"openingCash\":\"" + s.getOpeningCash() + "\",\"autoManaged\":true}");
        shiftAuditLogRepository.save(log);

        Map<String, Object> newState = new LinkedHashMap<>();
        newState.put("openingCash", s.getOpeningCash().toPlainString());
        newState.put("status", s.getStatus());
        newState.put("autoManaged", true);
        auditEventPublisher.publish(auditEventBuilder.builder(
                        AuditEventCategory.CASH_DRAWER, AuditEventTypes.SHIFT_OPENED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(s.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("shift", s.getId())
                .targetLabel("Shift " + s.getId().substring(0, 8))
                .shiftId(s.getId())
                .newState(newState)
                .source("auto_shift")
                .build());
    }
}
