package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.SettingsRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.SettingsResponse;
import zelisline.ub.restaurant.domain.RestaurantSettings;
import zelisline.ub.restaurant.repository.RestaurantSettingsRepository;

/**
 * How one restaurant runs (ADR 0020, amendment 3): service charge, pooled tips, packaging and
 * delivery charges, when its day starts, and whether customers may order from a table's QR code.
 * A business with no row has none of them, which is how every restaurant starts.
 */
@Service
@RequiredArgsConstructor
public class RestaurantSettingsService {

    private static final BigDecimal MAX_SERVICE_CHARGE_PERCENT = new BigDecimal("30");
    private static final BigDecimal MAX_FIXED_CHARGE = new BigDecimal("100000");

    private final RestaurantSettingsRepository repository;
    private final RestaurantGate gate;

    /** The settings, or a blank set for a business that has saved none. Never null. */
    @Transactional(readOnly = true)
    public RestaurantSettings get(String businessId) {
        return repository.findById(businessId).orElseGet(() -> {
            RestaurantSettings blank = new RestaurantSettings();
            blank.setBusinessId(businessId);
            return blank;
        });
    }

    @Transactional(readOnly = true)
    public SettingsResponse read(String businessId) {
        gate.requireEnabled(businessId);
        return toResponse(get(businessId));
    }

    /** Null leaves a field as it is; zero switches a charge off. */
    @Transactional
    public SettingsResponse update(String businessId, String userId, SettingsRequest request) {
        gate.requireEnabled(businessId);
        RestaurantSettings settings = get(businessId);
        if (request.serviceChargePercent() != null) {
            if (request.serviceChargePercent().compareTo(MAX_SERVICE_CHARGE_PERCENT) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A service charge is at most 30 percent");
            }
            settings.setServiceChargePercent(zeroToNull(request.serviceChargePercent()));
        }
        if (request.tipsPooled() != null) {
            settings.setTipsPooled(request.tipsPooled());
        }
        if (request.serviceChargeTo() != null) {
            settings.setServiceChargeTo(request.serviceChargeTo());
        }
        if (request.packagingCharge() != null) {
            settings.setPackagingCharge(fixed(request.packagingCharge(), "packaging charge"));
        }
        if (request.deliveryFee() != null) {
            settings.setDeliveryFee(fixed(request.deliveryFee(), "delivery fee"));
        }
        if (request.dayStartsAtMinute() != null) {
            settings.setDayStartsAtMinute(request.dayStartsAtMinute());
        }
        if (request.qrOrdering() != null) {
            settings.setQrOrdering(request.qrOrdering());
            if (request.qrOrdering()) {
                // Orders opened from a QR code are recorded as opened by whoever allowed it.
                settings.setQrEnabledBy(userId);
            }
        }
        if (request.menuOnline() != null) {
            settings.setMenuOnline(request.menuOnline());
        }
        // A blank string clears the time to the code default; a null leaves it unchanged (ADR 0031).
        if (request.checkInTime() != null) {
            settings.setCheckInTime(parseTime(request.checkInTime()));
        }
        if (request.checkOutTime() != null) {
            settings.setCheckOutTime(parseTime(request.checkOutTime()));
        }
        if (request.roomsOnline() != null) {
            settings.setRoomsOnline(request.roomsOnline());
        }
        return toResponse(repository.save(settings));
    }

    /** "HH:mm" to a LocalTime, or null for a blank (meaning "use the default"). */
    private static java.time.LocalTime parseTime(String raw) {
        return raw == null || raw.isBlank() ? null : java.time.LocalTime.parse(raw);
    }

    private static String timeOrNull(java.time.LocalTime t) {
        return t == null ? null : String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static BigDecimal fixed(BigDecimal value, String what) {
        if (value.compareTo(MAX_FIXED_CHARGE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the " + what);
        }
        return zeroToNull(value);
    }

    private static BigDecimal zeroToNull(BigDecimal value) {
        return value.signum() <= 0 ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static SettingsResponse toResponse(RestaurantSettings s) {
        return new SettingsResponse(s.getServiceChargePercent(), s.isTipsPooled(), s.getPackagingCharge(),
                s.getDeliveryFee(), s.getDayStartsAtMinute(), s.isQrOrdering(),
                s.getServiceChargeTo() == null ? RestaurantSettings.CHARGE_TO_BUSINESS : s.getServiceChargeTo(),
                s.isMenuOnline(), timeOrNull(s.getCheckInTime()), timeOrNull(s.getCheckOutTime()),
                s.isRoomsOnline());
    }
}
