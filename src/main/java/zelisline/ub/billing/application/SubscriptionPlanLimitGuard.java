package zelisline.ub.billing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Blocks new products and staff seats once the current plan is full.
 * Fail-open when the catalogue is missing so catalog ITs keep working.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionPlanLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPlanLimitGuard.class);

    private final SubscriptionPlanFitService fitService;

    public void assertCanAddProduct(String businessId) {
        SubscriptionPlanFit.Result result = evaluateQuietly(businessId);
        if (result == null || result.current() == null) {
            return;
        }
        if (result.current().productLimit() == null) {
            return;
        }
        if (result.usage().productCount() < result.current().productLimit()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, SubscriptionPlanFit.blockProductMessage(result));
    }

    public void assertCanAddUser(String businessId) {
        SubscriptionPlanFit.Result result = evaluateQuietly(businessId);
        if (result == null || result.current() == null) {
            return;
        }
        if (result.current().cashierLimit() == null) {
            return;
        }
        if (result.usage().userCount() < result.current().cashierLimit()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, SubscriptionPlanFit.blockUserMessage(result));
    }

    private SubscriptionPlanFit.Result evaluateQuietly(String businessId) {
        try {
            return fitService.evaluate(businessId);
        } catch (RuntimeException ex) {
            log.debug("plan-fit guard skipped businessId={}", businessId, ex);
            return null;
        }
    }
}
