package zelisline.ub.credits.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
