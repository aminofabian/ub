package zelisline.ub.airtime.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.api.dto.AirtimeAvailabilityResponse;
import zelisline.ub.airtime.api.dto.AirtimeSettingsResponse;
import zelisline.ub.airtime.api.dto.UpdateAirtimeSettingsRequest;
import zelisline.ub.airtime.domain.BusinessAirtimeSettings;
import zelisline.ub.airtime.domain.PlatformAirtimeSettings;
import zelisline.ub.airtime.repository.AirtimeOrderRepository;
import zelisline.ub.airtime.repository.BusinessAirtimeSettingsRepository;
import zelisline.ub.payments.api.dto.KioskPayAccountResponse;
import zelisline.ub.payments.application.KioskPayWalletService;

/**
 * Per-tenant airtime opt-in, and the availability answer the till and storefront
 * ask before offering airtime at all.
 */
@Service
@RequiredArgsConstructor
public class BusinessAirtimeSettingsService {

    /** Denominations Kenyan shoppers actually ask for. */
    private static final List<BigDecimal> QUICK_AMOUNTS = List.of(
            new BigDecimal("20"),
            new BigDecimal("50"),
            new BigDecimal("100"),
            new BigDecimal("250"),
            new BigDecimal("500"),
            new BigDecimal("1000"));

    private final BusinessAirtimeSettingsRepository repository;
    private final PlatformAirtimeSettingsService platformSettings;
    private final AirtimeOrderRepository orderRepository;
    private final KioskPayWalletService walletService;

    @Transactional(readOnly = true)
    public AirtimeSettingsResponse getSettings(String businessId) {
        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        BusinessAirtimeSettings row = repository.findByBusinessId(businessId).orElse(null);
        KioskPayAccountResponse wallet = walletService.getAccount(businessId);
        boolean hasCredentials = platformSettings.credentials().isPresent();

        String blocked = null;
        if (!platform.isEnabled()) {
            blocked = "Airtime is not enabled on this platform yet.";
        } else if (!hasCredentials) {
            blocked = "The platform airtime provider is not configured yet.";
        } else if (!"ACTIVE".equals(wallet.status())) {
            blocked = "Activate Kiosk Pay first — airtime is paid for from your wallet.";
        }

        return new AirtimeSettingsResponse(
                row != null && row.isEnabled(),
                row == null || row.isPosEnabled(),
                row != null && row.isStorefrontEnabled(),
                row != null ? row.getMaxSingleAmount() : null,
                platform.isEnabled(),
                platform.isPosEnabled(),
                platform.isStorefrontEnabled(),
                hasCredentials,
                platform.getTenantCommissionPercent(),
                platform.getMinAmount(),
                platform.getMaxAmount(),
                platform.getDailyTenantLimit(),
                platform.getCurrency(),
                "ACTIVE".equals(wallet.status()),
                wallet.availableBalance(),
                blocked);
    }

    @Transactional
    public AirtimeSettingsResponse updateSettings(String businessId, UpdateAirtimeSettingsRequest body) {
        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        BusinessAirtimeSettings row = getOrCreate(businessId);

        if (body.maxSingleAmount() != null) {
            BigDecimal max = body.maxSingleAmount();
            if (max.compareTo(BigDecimal.ZERO) <= 0) {
                row.setMaxSingleAmount(null);
            } else {
                if (max.compareTo(platform.getMinAmount()) < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Your limit cannot be below the platform minimum of " + platform.getMinAmount());
                }
                row.setMaxSingleAmount(max.min(platform.getMaxAmount()));
            }
        }
        if (body.posEnabled() != null) {
            row.setPosEnabled(body.posEnabled());
        }
        if (body.storefrontEnabled() != null) {
            row.setStorefrontEnabled(body.storefrontEnabled());
        }
        if (Boolean.TRUE.equals(body.enabled())) {
            if (!platform.isEnabled()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Airtime is not enabled on this platform yet");
            }
            if (platformSettings.credentials().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The platform airtime provider is not configured yet");
            }
            KioskPayAccountResponse wallet = walletService.getAccount(businessId);
            if (!"ACTIVE".equals(wallet.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Activate Kiosk Pay before selling airtime — your wallet is what pays for it");
            }
            row.setEnabled(true);
        } else if (Boolean.FALSE.equals(body.enabled())) {
            row.setEnabled(false);
        }

        repository.save(row);
        return getSettings(businessId);
    }

    @Transactional(readOnly = true)
    public AirtimeAvailabilityResponse availability(String businessId, boolean storefront) {
        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        BusinessAirtimeSettings row = repository.findByBusinessId(businessId).orElse(null);
        KioskPayAccountResponse wallet = walletService.getAccount(businessId);
        boolean hasCredentials = platformSettings.credentials().isPresent();
        boolean walletActive = "ACTIVE".equals(wallet.status());

        boolean channelOnPlatform = storefront ? platform.isStorefrontEnabled() : platform.isPosEnabled();
        boolean channelOnTenant = row != null && (storefront ? row.isStorefrontEnabled() : row.isPosEnabled());
        boolean businessEnabled = row != null && row.isEnabled() && channelOnTenant;

        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        BigDecimal used = orderRepository.sumCommittedSince(businessId, dayStart);
        BigDecimal earned = orderRepository.sumCommissionSince(businessId, dayStart);
        BigDecimal remaining = platform.getDailyTenantLimit().subtract(used).max(BigDecimal.ZERO);

        BigDecimal tenantMax = row != null && row.getMaxSingleAmount() != null
                ? row.getMaxSingleAmount()
                : platform.getMaxAmount();
        BigDecimal maxAmount = tenantMax.min(platform.getMaxAmount());
        BigDecimal maxSellableNow = maxAmount
                .min(wallet.availableBalance() == null ? BigDecimal.ZERO : wallet.availableBalance())
                .min(remaining)
                .setScale(0, RoundingMode.DOWN)
                .max(BigDecimal.ZERO);

        String reason = null;
        if (!platform.isEnabled()) {
            reason = "Airtime is not available on this platform";
        } else if (!hasCredentials) {
            reason = "The airtime provider is not configured yet";
        } else if (!channelOnPlatform) {
            reason = storefront
                    ? "Airtime is not available on storefronts"
                    : "Airtime is not available at the till";
        } else if (row == null || !row.isEnabled()) {
            reason = "Turn on airtime under Payments → Settings";
        } else if (!channelOnTenant) {
            reason = storefront
                    ? "Airtime is switched off for your storefront"
                    : "Airtime is switched off for the till";
        } else if (!walletActive) {
            reason = "Activate Kiosk Pay — airtime is paid from your wallet";
        } else if (platform.isFloatConstrained(Instant.now())) {
            reason = "Airtime is briefly unavailable — try again in a few minutes";
        } else if (remaining.compareTo(platform.getMinAmount()) < 0) {
            reason = "You have reached today's airtime limit";
        } else if (maxSellableNow.compareTo(platform.getMinAmount()) < 0) {
            reason = "Top up your Kiosk Pay wallet to sell airtime";
        }

        boolean available = platform.isEnabled()
                && hasCredentials
                && channelOnPlatform
                && businessEnabled
                && walletActive
                && !platform.isFloatConstrained(Instant.now())
                && maxSellableNow.compareTo(platform.getMinAmount()) >= 0;

        return new AirtimeAvailabilityResponse(
                available,
                platform.isEnabled(),
                businessEnabled,
                hasCredentials,
                walletActive,
                wallet.availableBalance(),
                platform.getMinAmount(),
                maxAmount,
                maxSellableNow,
                platform.getTenantCommissionPercent(),
                platform.getDailyTenantLimit(),
                used,
                remaining,
                earned,
                platform.getCurrency(),
                QUICK_AMOUNTS,
                reason);
    }

    @Transactional
    public BusinessAirtimeSettings getOrCreate(String businessId) {
        return repository.findByBusinessId(businessId).orElseGet(() -> {
            BusinessAirtimeSettings created = new BusinessAirtimeSettings();
            created.setBusinessId(businessId);
            try {
                return repository.save(created);
            } catch (DataIntegrityViolationException e) {
                return repository.findByBusinessId(businessId).orElseThrow(() -> e);
            }
        });
    }

    @Transactional(readOnly = true)
    public BusinessAirtimeSettings findOrNull(String businessId) {
        return repository.findByBusinessId(businessId).orElse(null);
    }
}
