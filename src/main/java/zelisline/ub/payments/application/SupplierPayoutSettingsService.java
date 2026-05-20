package zelisline.ub.payments.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.SupplierPayoutGatewayOption;
import zelisline.ub.payments.api.dto.SupplierPayoutSettingsRequest;
import zelisline.ub.payments.api.dto.SupplierPayoutSettingsResponse;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.domain.SupplierPayoutSettings;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PlatformPaymentGatewayRepository;
import zelisline.ub.payments.repository.SupplierPayoutSettingsRepository;

@Service
@RequiredArgsConstructor
public class SupplierPayoutSettingsService {

    private final SupplierPayoutSettingsRepository settingsRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final PlatformPaymentGatewayRepository platformGatewayRepository;

    @Transactional(readOnly = true)
    public SupplierPayoutSettingsResponse getSettings(String businessId) {
        SupplierPayoutSettings settings = settingsRepository.findById(businessId)
                .orElseGet(() -> SupplierPayoutSettings.disabledFor(businessId));
        List<SupplierPayoutGatewayOption> selectable = listSelectableGateways(businessId);
        Optional<PaymentGatewayConfig> resolved = resolveActivePayoutConfig(businessId, settings);
        return toResponse(settings, selectable, resolved);
    }

    @Transactional
    public SupplierPayoutSettingsResponse updateSettings(String businessId, SupplierPayoutSettingsRequest request) {
        SupplierPayoutSettings settings = settingsRepository.findById(businessId)
                .orElseGet(() -> SupplierPayoutSettings.disabledFor(businessId));

        if (request.enabled() != null) {
            settings.setEnabled(request.enabled());
        }
        if (request.paymentGatewayConfigId() != null) {
            String configId = request.paymentGatewayConfigId().isBlank()
                    ? null
                    : request.paymentGatewayConfigId().trim();
            if (configId != null) {
                validateSelectableConfig(businessId, configId);
            }
            settings.setPaymentGatewayConfigId(configId);
        }

        if (settings.isEnabled()) {
            if (settings.getPaymentGatewayConfigId() == null || settings.getPaymentGatewayConfigId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Choose an active payment gateway for supplier payouts");
            }
            if (resolveActivePayoutConfig(businessId, settings).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Selected gateway is not active or not allowed for supplier payouts");
            }
        } else {
            settings.setPaymentGatewayConfigId(null);
        }

        settingsRepository.save(settings);
        List<SupplierPayoutGatewayOption> selectable = listSelectableGateways(businessId);
        return toResponse(settings, selectable, resolveActivePayoutConfig(businessId, settings));
    }

    /**
     * Resolved ACTIVE tenant gateway config when supplier payouts are enabled and valid.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentGatewayConfig> resolveActivePayoutConfig(String businessId) {
        SupplierPayoutSettings settings = settingsRepository.findById(businessId)
                .orElseGet(() -> SupplierPayoutSettings.disabledFor(businessId));
        return resolveActivePayoutConfig(businessId, settings);
    }

    @Transactional(readOnly = true)
    public boolean isSupplierPayoutToggleEnabled(String businessId) {
        return settingsRepository.findById(businessId)
                .map(SupplierPayoutSettings::isEnabled)
                .orElse(false);
    }

    private Optional<PaymentGatewayConfig> resolveActivePayoutConfig(
            String businessId,
            SupplierPayoutSettings settings
    ) {
        if (!settings.isEnabled()) {
            return Optional.empty();
        }
        String configId = settings.getPaymentGatewayConfigId();
        if (configId == null || configId.isBlank()) {
            return Optional.empty();
        }
        PaymentGatewayConfig cfg = configRepository.findById(configId.trim()).orElse(null);
        if (cfg == null || !businessId.equals(cfg.getBusinessId())) {
            return Optional.empty();
        }
        if (cfg.getStatus() != GatewayStatus.ACTIVE || cfg.getGatewayType() == GatewayType.MANUAL) {
            return Optional.empty();
        }
        PlatformPaymentGateway platform = platformGatewayRepository.findById(cfg.getGatewayType()).orElse(null);
        if (platform == null || !platform.isEnabled() || !platform.isSupplierPayoutSupported()) {
            return Optional.empty();
        }
        return Optional.of(cfg);
    }

    private List<SupplierPayoutGatewayOption> listSelectableGateways(String businessId) {
        List<SupplierPayoutGatewayOption> result = new ArrayList<>();
        for (PaymentGatewayConfig cfg : configRepository.findByBusinessId(businessId)) {
            if (cfg.getGatewayType() == GatewayType.MANUAL) {
                continue;
            }
            PlatformPaymentGateway platform = platformGatewayRepository.findById(cfg.getGatewayType()).orElse(null);
            if (platform == null || !platform.isEnabled() || !platform.isSupplierPayoutSupported()) {
                continue;
            }
            result.add(new SupplierPayoutGatewayOption(
                    cfg.getId(),
                    cfg.getGatewayType().name(),
                    cfg.getLabel(),
                    cfg.getStatus().name()));
        }
        return result;
    }

    private void validateSelectableConfig(String businessId, String configId) {
        PaymentGatewayConfig cfg = configRepository.findById(configId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gateway config not found"));
        if (!businessId.equals(cfg.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gateway config not found");
        }
        if (cfg.getGatewayType() == GatewayType.MANUAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manual methods cannot pay suppliers via API");
        }
        PlatformPaymentGateway platform = platformGatewayRepository.findById(cfg.getGatewayType())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gateway type not on platform"));
        if (!platform.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Gateway type is disabled by the platform administrator");
        }
        if (!platform.isSupplierPayoutSupported()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This gateway does not support supplier payouts");
        }
        if (cfg.getStatus() != GatewayStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Gateway must be activated before use for supplier payouts");
        }
    }

    private SupplierPayoutSettingsResponse toResponse(
            SupplierPayoutSettings settings,
            List<SupplierPayoutGatewayOption> selectable,
            Optional<PaymentGatewayConfig> resolved
    ) {
        return new SupplierPayoutSettingsResponse(
                settings.isEnabled(),
                settings.getPaymentGatewayConfigId(),
                resolved.map(c -> c.getGatewayType().name()).orElse(null),
                resolved.map(PaymentGatewayConfig::getLabel).orElse(null),
                resolved.isPresent(),
                selectable);
    }
}
