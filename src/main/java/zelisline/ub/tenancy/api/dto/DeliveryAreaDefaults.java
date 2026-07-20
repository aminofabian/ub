package zelisline.ub.tenancy.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Seed areas when a tenant has never configured {@code storefront.deliveryAreas}.
 * Mirrors the former hardcoded Nairobi checkout wards (flattened unique names).
 */
public final class DeliveryAreaDefaults {

    private DeliveryAreaDefaults() {
    }

    public static List<DeliveryAreaDto> seed() {
        return List.of(
                area("Githurai"),
                area("Kahawa West"),
                area("Zimmerman"),
                area("Roysambu"),
                area("Kahawa"),
                area("Mirema"),
                area("USIU"),
                area("Thome"),
                area("Garden Estate"),
                area("Kasarani")
        );
    }

    private static DeliveryAreaDto area(String name) {
        // Stable ids from name so seeds stay consistent across reads.
        String id = UUID.nameUUIDFromBytes(("delivery-area:" + name).getBytes()).toString();
        return new DeliveryAreaDto(id, name, true);
    }
}