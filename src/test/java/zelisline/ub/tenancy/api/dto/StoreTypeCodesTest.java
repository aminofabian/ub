package zelisline.ub.tenancy.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class StoreTypeCodesTest {

    @Test
    void serviceTypesAreAcceptedByThePattern() {
        for (String type : StoreTypeCodes.SERVICE_TYPES) {
            assertThat(type).matches(StoreTypeCodes.PATTERN);
        }
        assertThat("mini-mart").matches(StoreTypeCodes.PATTERN);
        assertThat("barber").doesNotMatch(StoreTypeCodes.PATTERN);
        assertThat("salon ").doesNotMatch(StoreTypeCodes.PATTERN);
        assertThat("").doesNotMatch(StoreTypeCodes.PATTERN);
    }

    @Test
    void aBusinessIsAServiceBusinessWhenAnyOfItsTypesIs() {
        assertThat(StoreTypeCodes.isServiceBusiness(List.of("barbershop"))).isTrue();
        assertThat(StoreTypeCodes.isServiceBusiness(List.of("cosmetics", " Salon "))).isTrue();
        assertThat(StoreTypeCodes.isServiceBusiness(List.of("mini-mart", "butchery"))).isFalse();
        assertThat(StoreTypeCodes.isServiceBusiness(List.of())).isFalse();
        assertThat(StoreTypeCodes.isServiceBusiness(null)).isFalse();
        assertThat(StoreTypeCodes.isServiceBusiness(Arrays.asList(null, "spa"))).isTrue();
    }
}
