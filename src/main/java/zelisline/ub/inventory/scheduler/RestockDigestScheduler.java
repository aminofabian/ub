package zelisline.ub.inventory.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.application.RestockDigestNotificationService;
import zelisline.ub.inventory.application.RestockDigestService;
import zelisline.ub.inventory.domain.RestockRun;
import zelisline.ub.inventory.repository.RestockRunRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Minute tick that generates + notifies the nightly restock digest per branch at each
 * branch's configured local time. Mirrors {@code SupplierAutoPayScheduler} (minute tick,
 * per-tenant HH:mm claim) and {@code DailyStockAuditScheduler} (property-gate convention).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.inventory.restock-digest.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RestockDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(RestockDigestScheduler.class);
    private static final int TENANT_PAGE_SIZE = 200;
    /**
     * How long after the configured time the slot stays claimable. Without a window a
     * deploy or a slow tick straddling the exact minute would silently drop that
     * branch's digest for the whole day; the unique {@code (branch_id, run_date)}
     * constraint still guarantees exactly one run.
     */
    private static final int CATCH_UP_MINUTES = 90;

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final RestockRunRepository restockRunRepository;
    private final RestockDigestService restockDigestService;
    private final RestockDigestNotificationService restockDigestNotificationService;

    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    public void tick() {
        int page = 0;
        int generated = 0;
        int failed = 0;
        while (true) {
            var batch = businessRepository.findByDeletedAtIsNull(PageRequest.of(page, TENANT_PAGE_SIZE));
            for (Business business : batch.getContent()) {
                if (business.getTenantStatus() == TenantStatus.SUSPENDED
                        || business.getTenantStatus() == TenantStatus.INACTIVE) {
                    continue;
                }
                for (Branch branch :
                        branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(business.getId())) {
                    try {
                        if (claimRestockSlotIfDue(business, branch)) {
                            generated++;
                        }
                    } catch (RuntimeException ex) {
                        failed++;
                        log.warn(
                                "restock digest generation failed businessId={} branchId={}",
                                business.getId(),
                                branch.getId(),
                                ex);
                    }
                }
            }
            if (!batch.hasNext()) {
                break;
            }
            page++;
        }
        if (generated > 0 || failed > 0) {
            log.info("restock digest tick: generated={} failed={}", generated, failed);
        }
    }

    /**
     * Generate + notify once the business-local wall clock has reached
     * {@code branch.restockRunTime} (within {@link #CATCH_UP_MINUTES}) and no run exists
     * for the local date. The unique {@code (branch_id, run_date)} constraint makes this
     * idempotent under races. Notify runs even when the run already exists but was never
     * delivered, so a transient WhatsApp / outbox failure doesn't lose the digest.
     */
    private boolean claimRestockSlotIfDue(Business business, Branch branch) {
        if (!branch.isActive() || !branch.isRestockEnabled()) {
            return false;
        }
        ZoneId zone = resolveZone(business);
        LocalDateTime now = LocalDateTime.now(zone).withSecond(0).withNano(0);
        LocalTime runTime = branch.getRestockRunTime();
        if (runTime == null) {
            runTime = LocalTime.of(20, 0);
        }
        if (!isDue(now.toLocalTime(), runTime)) {
            return false;
        }
        LocalDate runDate = now.toLocalDate();
        Optional<RestockRun> existing =
                restockRunRepository.findByBranchIdAndRunDate(branch.getId(), runDate);
        if (existing.isPresent()) {
            // notifyRun is a no-op unless the run is still `generated`, so this only
            // retries a delivery that never completed.
            if (InventoryConstants.DIGEST_RUN_GENERATED.equals(existing.get().getStatus())) {
                restockDigestNotificationService.notifyRun(business.getId(), existing.get().getId());
            }
            return false;
        }
        var run = restockDigestService.generateForBranch(
                business.getId(), branch.getId(), runDate, InventoryConstants.DIGEST_TRIGGER_SCHEDULED);
        restockDigestNotificationService.notifyRun(business.getId(), run.id());
        return true;
    }

    /**
     * True while the local clock sits in {@code [runTime, runTime + CATCH_UP_MINUTES]}.
     *
     * <p>Compared in whole minutes from midnight, which buys two things over an exact
     * {@code LocalTime.equals} check: a {@code restock_run_time} carrying seconds (it's a
     * MySQL {@code TIME}) still matches, and a deploy or slow tick that straddles the
     * exact minute no longer drops the branch's digest for the whole day. Subtracting
     * minutes-of-day rather than calling {@code plusMinutes} keeps a late run time (say
     * {@code 23:30}) from wrapping the window past midnight onto the next run date.
     */
    static boolean isDue(LocalTime localNow, LocalTime runTime) {
        int elapsed = minutesOfDay(localNow) - minutesOfDay(runTime);
        return elapsed >= 0 && elapsed <= CATCH_UP_MINUTES;
    }

    private static int minutesOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static ZoneId resolveZone(Business business) {
        String tz = business.getTimezone();
        if (tz == null || tz.isBlank()) {
            return ZoneId.of("Africa/Nairobi");
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception ex) {
            return ZoneId.of("Africa/Nairobi");
        }
    }
}
