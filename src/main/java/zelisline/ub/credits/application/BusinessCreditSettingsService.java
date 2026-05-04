package zelisline.ub.credits.application;

import java.math.BigDecimal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.BusinessCreditSettings;
import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;

@Service
@RequiredArgsConstructor
public class BusinessCreditSettingsService {

    private final BusinessCreditSettingsRepository businessCreditSettingsRepository;

    /** Default earn rate matches migration (0 ⇒ earn disabled until configured). */
    @Transactional
    public BusinessCreditSettings resolveForBusiness(String businessId) {
        return businessCreditSettingsRepository.findById(businessId).orElseGet(() -> insertDefaults(businessId));
    }

    @Transactional
    public BusinessCreditSettings updateLoyaltyTunables(
            String businessId,
            BigDecimal loyaltyPointsPerKes,
            BigDecimal loyaltyKesPerPoint,
            int loyaltyMaxRedeemBps
    ) {
        if (loyaltyMaxRedeemBps < 0 || loyaltyMaxRedeemBps > 10_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max redeem must be between 0 and 10000 bps");
        }
        if (loyaltyKesPerPoint == null || loyaltyKesPerPoint.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "KES per point must be positive");
        }
        if (loyaltyPointsPerKes == null || loyaltyPointsPerKes.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Points per KES must be non-negative");
        }
        BusinessCreditSettings s = resolveForBusiness(businessId);
        s.setLoyaltyPointsPerKes(loyaltyPointsPerKes);
        s.setLoyaltyKesPerPoint(loyaltyKesPerPoint);
        s.setLoyaltyMaxRedeemBps(loyaltyMaxRedeemBps);
        return businessCreditSettingsRepository.save(s);
    }

    private BusinessCreditSettings insertDefaults(String businessId) {
        BusinessCreditSettings s = new BusinessCreditSettings();
        s.setBusinessId(businessId);
        try {
            return businessCreditSettingsRepository.save(s);
        } catch (DataIntegrityViolationException e) {
            return businessCreditSettingsRepository.findById(businessId)
                    .orElseThrow(() -> e);
        }
    }
}
