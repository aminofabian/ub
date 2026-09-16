package zelisline.ub.tenancy.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Soft-deletes self-serve shops that never got an owner: {@code onboardBusiness}
 * creates the tenant, then the browser closes before register. Reclaims the slug
 * via {@link BusinessDeletionService} so the next attempt is not stuck on a
 * ghost subdomain.
 */
@Service
@RequiredArgsConstructor
public class AbandonedBusinessGcService {

    private static final Logger log = LoggerFactory.getLogger(AbandonedBusinessGcService.class);

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final BusinessDeletionService businessDeletionService;

    @Value("${app.tenancy.abandoned-business-gc-enabled:true}")
    private boolean enabled;

    /** Aligns with the frontend pending-onboard draft TTL (7 days). */
    @Value("${app.tenancy.abandoned-business-gc-age-days:7}")
    private int ageDays;

    @Value("${app.tenancy.abandoned-business-gc-batch-size:50}")
    private int batchSize;

    public int sweep() {
        if (!enabled) {
            return 0;
        }
        int days = Math.max(1, ageDays);
        int limit = Math.max(1, Math.min(batchSize, 200));
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Business> aged = businessRepository.findLiveCreatedOnOrBefore(
                cutoff, PageRequest.of(0, limit));
        int purged = 0;
        for (Business business : aged) {
            if (!isOwnerlessOrphan(business.getId())) {
                continue;
            }
            try {
                businessDeletionService.deleteBusinessAndUsers(business.getId());
                purged++;
                log.info(
                        "Abandoned business GC soft-deleted businessId={} slug={} createdAt={}",
                        business.getId(),
                        business.getSlug(),
                        business.getCreatedAt());
            } catch (RuntimeException ex) {
                log.error(
                        "Abandoned business GC failed businessId={} error={}",
                        business.getId(),
                        ex.getMessage());
            }
        }
        if (purged > 0) {
            log.info(
                    "Abandoned business GC: purged {} of {} aged candidates (cutoff={})",
                    purged,
                    aged.size(),
                    cutoff);
        }
        return purged;
    }

    /** Live tenant with zero non-deleted users — never finished register. */
    boolean isOwnerlessOrphan(String businessId) {
        return userRepository.countByBusinessIdAndDeletedAtIsNull(businessId) == 0;
    }
}
