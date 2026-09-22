package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.ProfitPocketSendRailOption;
import zelisline.ub.payments.api.dto.ProfitPocketSettingsRequest;
import zelisline.ub.payments.api.dto.ProfitPocketSettingsResponse;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.domain.ProfitPocketSettings;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PlatformPaymentGatewayRepository;
import zelisline.ub.payments.repository.ProfitPocketSettingsRepository;

@Service
@RequiredArgsConstructor
public class ProfitPocketSettingsService {

    private static final Set<String> TYPES = Set.of(
            ProfitPocketSettings.TYPE_BANK,
            ProfitPocketSettings.TYPE_TILL,
            ProfitPocketSettings.TYPE_PAYBILL);

    private static final Set<String> RAILS = Set.of(
            ProfitPocketSettings.RAIL_DARAJA,
            ProfitPocketSettings.RAIL_KOPOKOPO);

    private static final Set<String> GUARD_MODES = Set.of(
            ProfitPocketSettings.GUARD_WARN,
            ProfitPocketSettings.GUARD_APPROVE,
            ProfitPocketSettings.GUARD_HARD);

    private final ProfitPocketSettingsRepository settingsRepository;
    private final CustomerPayEndpointService customerPayEndpointService;
    private final PaymentGatewayConfigRepository configRepository;
    private final PlatformPaymentGatewayRepository platformGatewayRepository;
    private final ObjectProvider<PlatformDarajaSettingsService> platformDarajaSettingsService;
    private final SupplierPayoutSettingsService supplierPayoutSettingsService;

    @Transactional(readOnly = true)
    public ProfitPocketSettingsResponse getSettings(String businessId) {
        return toResponse(businessId, settingsRepository.findById(businessId)
                .orElseGet(() -> ProfitPocketSettings.disabledFor(businessId)));
    }

    /** Effective margin-guard mode for the till (defaults to warn). */
    @Transactional(readOnly = true)
    public String marginGuardMode(String businessId) {
        return normalizeGuardMode(settingsRepository.findById(businessId)
                .map(ProfitPocketSettings::getMarginGuardMode)
                .orElse(ProfitPocketSettings.GUARD_WARN));
    }

    @Transactional
    public ProfitPocketSettingsResponse updateSettings(String businessId, ProfitPocketSettingsRequest request) {
        ProfitPocketSettings settings = settingsRepository.findById(businessId)
                .orElseGet(() -> ProfitPocketSettings.disabledFor(businessId));

        if (request.enabled() != null) {
            settings.setEnabled(request.enabled());
        }
        if (request.destinationType() != null) {
            String type = blankToNull(request.destinationType());
            if (type != null && !TYPES.contains(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "destinationType must be bank, till, or paybill");
            }
            settings.setDestinationType(type);
        }
        if (request.destinationLabel() != null) {
            settings.setDestinationLabel(blankToNull(request.destinationLabel()));
        }
        if (request.destinationAccount() != null) {
            settings.setDestinationAccount(blankToNull(request.destinationAccount()));
        }
        if (request.destinationBankName() != null) {
            settings.setDestinationBankName(blankToNull(request.destinationBankName()));
        }
        if (request.destinationPaybill() != null) {
            settings.setDestinationPaybill(blankToNull(request.destinationPaybill()));
        }
        if (request.destinationPaybillAccount() != null) {
            settings.setDestinationPaybillAccount(blankToNull(request.destinationPaybillAccount()));
        }
        if (request.defaultFloat() != null) {
            if (request.defaultFloat().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "defaultFloat must be >= 0");
            }
            settings.setDefaultFloat(request.defaultFloat().setScale(2, RoundingMode.HALF_UP));
        }
        if (request.marginGuardMode() != null) {
            String mode = blankToNull(request.marginGuardMode());
            if (mode == null) {
                settings.setMarginGuardMode(ProfitPocketSettings.GUARD_WARN);
            } else if (!GUARD_MODES.contains(mode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "marginGuardMode must be warn, approve, or hard");
            } else {
                settings.setMarginGuardMode(mode);
            }
        }
        if (request.fridayReminderEnabled() != null) {
            settings.setFridayReminderEnabled(request.fridayReminderEnabled());
        }
        if (request.profitJarPct() != null) {
            BigDecimal pct = request.profitJarPct().setScale(2, RoundingMode.HALF_UP);
            if (pct.compareTo(BigDecimal.ONE) < 0 || pct.compareTo(new BigDecimal("100.00")) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profitJarPct must be between 1 and 100");
            }
            // 100% = full surplus (store null)
            settings.setProfitJarPct(pct.compareTo(new BigDecimal("100.00")) >= 0 ? null : pct);
        }
        if (request.marginBudgetDaily() != null) {
            if (request.marginBudgetDaily().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "marginBudgetDaily must be >= 0");
            }
            BigDecimal budget = request.marginBudgetDaily().setScale(2, RoundingMode.HALF_UP);
            settings.setMarginBudgetDaily(budget.signum() == 0 ? null : budget);
        }
        if (request.sendRail() != null) {
            String rail = blankToNull(request.sendRail());
            if (rail != null && !RAILS.contains(rail)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sendRail must be daraja or kopokopo");
            }
            settings.setSendRail(rail);
        }
        if (request.stkPhone() != null) {
            String phone = blankToNull(request.stkPhone());
            if (phone != null) {
                String normalized = StkPhoneNormalizer.normalize(phone);
                if (normalized == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid Kenyan M-Pesa phone");
                }
                settings.setStkPhone(normalized);
            } else {
                settings.setStkPhone(null);
            }
        }

        if (settings.isEnabled()) {
            normalizeAndRequireDestination(settings);
            String collision = customerPayEndpointService.collisionMessage(businessId, settings);
            if (collision != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, collision);
            }
            normalizeSendRail(businessId, settings);
            if (ProfitPocketSettings.RAIL_DARAJA.equals(settings.getSendRail())
                    && blankToNull(settings.getStkPhone()) == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "M-Pesa phone is required for Daraja Express (same as receive test)");
            }
        }

        settingsRepository.save(settings);
        return toResponse(businessId, settings);
    }

    @Transactional(readOnly = true)
    public ProfitPocketSettings requireConfigured(String businessId) {
        ProfitPocketSettings settings = settingsRepository.findById(businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Set a Profit Pocket destination in Payments settings first"));
        if (!settings.isEnabled() || !isConfigured(settings)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Set a Profit Pocket destination in Payments settings first");
        }
        String collision = customerPayEndpointService.collisionMessage(businessId, settings);
        if (collision != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, collision);
        }
        return settings;
    }

    private void normalizeAndRequireDestination(ProfitPocketSettings settings) {
        String type = settings.getDestinationType();
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a destination type");
        }
        switch (type) {
            case ProfitPocketSettings.TYPE_BANK -> {
                if (blankToNull(settings.getDestinationBankName()) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bank name is required");
                }
                String bankPaybill = blankToNull(settings.getDestinationPaybill());
                if (bankPaybill == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Bank M-Pesa business number (paybill) is required");
                }
                String digits = bankPaybill.replaceAll("\\D", "");
                if (digits.length() < 5 || digits.length() > 7) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Bank business number must be 5–7 digits");
                }
                settings.setDestinationPaybill(digits);
                if (blankToNull(settings.getDestinationAccount()) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bank account number is required");
                }
            }
            case ProfitPocketSettings.TYPE_TILL -> {
                if (blankToNull(settings.getDestinationAccount()) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Till number is required");
                }
            }
            case ProfitPocketSettings.TYPE_PAYBILL -> {
                if (blankToNull(settings.getDestinationPaybill()) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paybill number is required");
                }
                if (blankToNull(settings.getDestinationPaybillAccount()) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paybill account is required");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown destination type");
        }
    }

    private static boolean isConfigured(ProfitPocketSettings s) {
        if (s.getDestinationType() == null || s.getDestinationType().isBlank()) {
            return false;
        }
        // Legacy mpesa_phone destinations are no longer supported.
        if (ProfitPocketSettings.TYPE_MPESA_PHONE.equals(s.getDestinationType())) {
            return false;
        }
        return switch (s.getDestinationType()) {
            case ProfitPocketSettings.TYPE_BANK ->
                    blankToNull(s.getDestinationAccount()) != null
                            && blankToNull(s.getDestinationBankName()) != null
                            && blankToNull(s.getDestinationPaybill()) != null;
            case ProfitPocketSettings.TYPE_TILL ->
                    blankToNull(s.getDestinationAccount()) != null;
            case ProfitPocketSettings.TYPE_PAYBILL ->
                    blankToNull(s.getDestinationPaybill()) != null
                            && blankToNull(s.getDestinationPaybillAccount()) != null;
            default -> false;
        };
    }

    private ProfitPocketSettingsResponse toResponse(String businessId, ProfitPocketSettings s) {
        boolean configured = isConfigured(s);
        String collision = configured
                ? customerPayEndpointService.collisionMessage(businessId, s)
                : null;
        List<ProfitPocketSendRailOption> rails = listAvailableSendRails(businessId);
        String sendRail = effectiveSendRail(s.getSendRail(), rails);
        return new ProfitPocketSettingsResponse(
                s.isEnabled(),
                configured,
                s.getDestinationType(),
                s.getDestinationLabel(),
                s.getDestinationAccount(),
                s.getDestinationBankName(),
                s.getDestinationPaybill(),
                s.getDestinationPaybillAccount(),
                s.getDefaultFloat() == null
                        ? new BigDecimal("5000.00")
                        : s.getDefaultFloat().setScale(2, RoundingMode.HALF_UP),
                configured ? summarize(s) : null,
                normalizeGuardMode(s.getMarginGuardMode()),
                s.isFridayReminderEnabled(),
                collision != null,
                collision,
                s.getProfitJarPct(),
                s.getMarginBudgetDaily(),
                sendRail,
                rails,
                s.getStkPhone());
    }

    /** Resolved rail for outbound send (may be null when none ready). */
    @Transactional(readOnly = true)
    public String resolveSendRail(String businessId, ProfitPocketSettings settings) {
        List<ProfitPocketSendRailOption> rails = listAvailableSendRails(businessId);
        return effectiveSendRail(settings != null ? settings.getSendRail() : null, rails);
    }

    @Transactional(readOnly = true)
    public List<ProfitPocketSendRailOption> listAvailableSendRails(String businessId) {
        List<ProfitPocketSendRailOption> out = new ArrayList<>();

        PlatformDarajaSettingsService daraja = platformDarajaSettingsService.getIfAvailable();
        if (daraja != null) {
            boolean enabled = daraja.loadSingleton().isEnabled();
            boolean stkReady = daraja.isEnabledAndConfigured();
            if (enabled || stkReady) {
                out.add(new ProfitPocketSendRailOption(
                        ProfitPocketSettings.RAIL_DARAJA,
                        "Daraja (platform Express)",
                        stkReady,
                        stkReady
                                ? "Same as customer receive: STK to your phone, money lands on bank / till / paybill (PartyB)"
                                : "Platform Daraja is on, but STK credentials (passkey / shortcode) are missing"));
            }
        }

        boolean platformKk = platformGatewayRepository.findById(GatewayType.KOPOKOPO)
                .map(PlatformPaymentGateway::isEnabled)
                .orElse(false);
        Optional<PaymentGatewayConfig> kk = resolveKopokopoConfig(businessId);
        if (platformKk || kk.isPresent()) {
            String detail;
            boolean ready = platformKk && kk.isPresent();
            if (!platformKk) {
                detail = "KopoKopo is disabled by the platform administrator";
            } else if (kk.isEmpty()) {
                detail = "Connect and activate KopoKopo under Accept payments, then select it in Pay suppliers";
            } else {
                detail = "Sends via your KopoKopo account ("
                        + (kk.get().getLabel() != null ? kk.get().getLabel() : "KopoKopo")
                        + ")";
            }
            out.add(new ProfitPocketSendRailOption(
                    ProfitPocketSettings.RAIL_KOPOKOPO,
                    "KopoKopo",
                    ready,
                    detail));
        }
        return out;
    }

    /** Active tenant KopoKopo config preferred by Pay suppliers, else any ACTIVE KopoKopo. */
    @Transactional(readOnly = true)
    public Optional<PaymentGatewayConfig> resolveKopokopoConfig(String businessId) {
        Optional<PaymentGatewayConfig> preferred = supplierPayoutSettingsService.resolveActivePayoutConfig(businessId);
        if (preferred.isPresent() && preferred.get().getGatewayType() == GatewayType.KOPOKOPO) {
            return preferred;
        }
        PlatformPaymentGateway platform = platformGatewayRepository.findById(GatewayType.KOPOKOPO).orElse(null);
        if (platform == null || !platform.isEnabled()) {
            return Optional.empty();
        }
        return configRepository
                .findByBusinessIdAndGatewayTypeAndStatus(businessId, GatewayType.KOPOKOPO, GatewayStatus.ACTIVE)
                .stream()
                .findFirst();
    }

    private void normalizeSendRail(String businessId, ProfitPocketSettings settings) {
        List<ProfitPocketSendRailOption> rails = listAvailableSendRails(businessId);
        String rail = blankToNull(settings.getSendRail());
        if (rail == null) {
            settings.setSendRail(firstReadyRail(rails));
            return;
        }
        ProfitPocketSendRailOption match = rails.stream()
                .filter(r -> rail.equals(r.id()))
                .findFirst()
                .orElse(null);
        if (match == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send rail “" + rail + "” is not available for this shop");
        }
        if (!match.ready()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, match.detail());
        }
    }

    private static String effectiveSendRail(String preferred, List<ProfitPocketSendRailOption> rails) {
        if (preferred != null && !preferred.isBlank()) {
            for (ProfitPocketSendRailOption r : rails) {
                if (preferred.equals(r.id()) && r.ready()) {
                    return preferred;
                }
            }
        }
        return firstReadyRail(rails);
    }

    private static String firstReadyRail(List<ProfitPocketSendRailOption> rails) {
        for (ProfitPocketSendRailOption r : rails) {
            if (r.ready()) {
                return r.id();
            }
        }
        return null;
    }

    /** Effective jar share 1–100 (defaults to 100). */
    public static BigDecimal effectiveJarPct(BigDecimal raw) {
        if (raw == null || raw.signum() <= 0) {
            return new BigDecimal("100.00");
        }
        if (raw.compareTo(new BigDecimal("100.00")) > 0) {
            return new BigDecimal("100.00");
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public BigDecimal marginBudgetDaily(String businessId) {
        return settingsRepository.findById(businessId)
                .map(ProfitPocketSettings::getMarginBudgetDaily)
                .orElse(null);
    }

    public static String summarize(ProfitPocketSettings s) {
        String label = blankToNull(s.getDestinationLabel());
        return switch (s.getDestinationType()) {
            case ProfitPocketSettings.TYPE_BANK -> {
                String bank = blankToNull(s.getDestinationBankName());
                String paybill = blankToNull(s.getDestinationPaybill());
                String acct = maskTail(s.getDestinationAccount());
                yield (label != null ? label + " · " : "")
                        + (bank != null ? bank + " · " : "")
                        + (paybill != null ? "Paybill " + paybill + " · " : "")
                        + acct;
            }
            case ProfitPocketSettings.TYPE_TILL ->
                    (label != null ? label + " · " : "") + "Till " + s.getDestinationAccount();
            case ProfitPocketSettings.TYPE_PAYBILL ->
                    (label != null ? label + " · " : "")
                            + "Paybill " + s.getDestinationPaybill()
                            + " · " + s.getDestinationPaybillAccount();
            default -> label != null ? label : s.getDestinationType();
        };
    }

    public static String normalizeGuardMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProfitPocketSettings.GUARD_WARN;
        }
        String mode = raw.trim().toLowerCase();
        if (GUARD_MODES.contains(mode)) {
            return mode;
        }
        return ProfitPocketSettings.GUARD_WARN;
    }

    private static String maskTail(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String t = raw.trim();
        if (t.length() <= 4) {
            return t;
        }
        return "****" + t.substring(t.length() - 4);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
