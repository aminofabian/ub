package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.api.dto.PlatformSmsCreditSettingsDtos;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.domain.PlatformSmsTierAllowance;
import zelisline.ub.messaging.repository.PlatformSmsCreditSettingsRepository;
import zelisline.ub.messaging.repository.PlatformSmsTierAllowanceRepository;

/**
 * Super Admin SMS credit configuration: the platform singleton (price, limits,
 * kill switch) and the per-tier included allowance table (SMS_CREDITS_SCOPE.md §5).
 */
@Service
@RequiredArgsConstructor
public class SmsCreditSettingsService {

    private final PlatformSmsCreditSettingsRepository settingsRepository;
    private final PlatformSmsTierAllowanceRepository tierRepository;

    @Transactional
    public PlatformSmsCreditSettings loadSingleton() {
        return settingsRepository.findFirstByOrderById()
                .orElseGet(() -> {
                    PlatformSmsCreditSettings row = new PlatformSmsCreditSettings();
                    row.setId(PlatformSmsCreditSettings.SINGLETON_ID);
                    return settingsRepository.save(row);
                });
    }

    @Transactional(readOnly = true)
    public boolean isEnabled() {
        return loadSingleton().isEnabled();
    }

    @Transactional(readOnly = true)
    public PlatformSmsCreditSettingsDtos.SettingsResponse getSettings() {
        return toSettingsResponse(loadSingleton());
    }

    @Transactional
    public PlatformSmsCreditSettingsDtos.SettingsResponse updateSettings(
            PlatformSmsCreditSettingsDtos.UpdateSettingsRequest body
    ) {
        PlatformSmsCreditSettings row = loadSingleton();
        if (body.enabled() != null) {
            row.setEnabled(body.enabled());
        }
        if (body.unitPriceKes() != null) {
            BigDecimal price = body.unitPriceKes();
            if (price.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unit price must be positive");
            }
            row.setUnitPriceKes(price);
        }
        if (body.minPurchaseCredits() != null) {
            row.setMinPurchaseCredits(nonNegative(body.minPurchaseCredits(), "min purchase"));
        }
        if (body.maxPurchaseCredits() != null) {
            row.setMaxPurchaseCredits(nonNegative(body.maxPurchaseCredits(), "max purchase"));
        }
        if (body.lowBalanceThreshold() != null) {
            row.setLowBalanceThreshold(nonNegative(body.lowBalanceThreshold(), "low balance threshold"));
        }
        if (body.cycleTimezone() != null && !body.cycleTimezone().isBlank()) {
            row.setCycleTimezone(body.cycleTimezone().trim());
        }
        row.setUpdatedAt(Instant.now());
        return toSettingsResponse(settingsRepository.save(row));
    }

    /** Included allowance for a tier code; empty when no active row exists. */
    @Transactional(readOnly = true)
    public Integer resolveAllowance(String tierCode) {
        if (tierCode == null || tierCode.isBlank()) {
            return null;
        }
        return tierRepository.findByTierCodeAndActiveTrue(tierCode)
                .map(PlatformSmsTierAllowance::getIncludedSmsPerMonth)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public PlatformSmsCreditSettingsDtos.TiersResponse getTiers() {
        List<PlatformSmsCreditSettingsDtos.TierAllowanceResponse> tiers =
                tierRepository.findAll().stream()
                        .map(t -> new PlatformSmsCreditSettingsDtos.TierAllowanceResponse(
                                t.getTierCode(), t.getIncludedSmsPerMonth(), t.isActive()))
                        .toList();
        return new PlatformSmsCreditSettingsDtos.TiersResponse(tiers);
    }

    @Transactional
    public PlatformSmsCreditSettingsDtos.TiersResponse upsertTier(
            String tierCode,
            PlatformSmsCreditSettingsDtos.UpdateTierAllowanceRequest body
    ) {
        if (tierCode == null || tierCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier code is required");
        }
        PlatformSmsTierAllowance row = tierRepository.findByTierCode(tierCode)
                .orElseGet(() -> {
                    PlatformSmsTierAllowance created = new PlatformSmsTierAllowance();
                    created.setTierCode(tierCode.trim().toLowerCase());
                    return created;
                });
        if (body.includedSmsPerMonth() != null) {
            if (body.includedSmsPerMonth() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "included SMS cannot be negative");
            }
            row.setIncludedSmsPerMonth(body.includedSmsPerMonth());
        }
        if (body.active() != null) {
            row.setActive(body.active());
        }
        row.setUpdatedAt(Instant.now());
        tierRepository.save(row);
        return getTiers();
    }

    private static int nonNegative(int value, String label) {
        if (value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " cannot be negative");
        }
        return value;
    }

    private static PlatformSmsCreditSettingsDtos.SettingsResponse toSettingsResponse(
            PlatformSmsCreditSettings row
    ) {
        return new PlatformSmsCreditSettingsDtos.SettingsResponse(
                row.isEnabled(),
                row.getUnitPriceKes(),
                row.getMinPurchaseCredits(),
                row.getMaxPurchaseCredits(),
                row.getLowBalanceThreshold(),
                row.getCycleTimezone(),
                row.getUpdatedAt());
    }
}
