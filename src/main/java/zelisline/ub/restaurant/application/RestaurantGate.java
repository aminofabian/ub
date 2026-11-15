package zelisline.ub.restaurant.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.application.FeatureFlagService;

/** The switch that turns the restaurant vertical off for a business (ADR 0020). On unless set to false. */
@Component
@RequiredArgsConstructor
public class RestaurantGate {

    private final FeatureFlagService featureFlagService;

    public boolean isEnabled(String businessId) {
        return featureFlagService.isEnabledDefaultTrue(businessId, FeatureFlagService.FLAG_RESTAURANT_ENABLED);
    }

    public void requireEnabled(String businessId) {
        if (!isEnabled(businessId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Restaurant features are switched off for this business");
        }
    }
}
