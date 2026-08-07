package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

class SupplierAutoPayTimesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsWhenBlank() {
        assertThat(SupplierAutoPayTimes.parseOrDefault(null, mapper))
                .containsExactly("00:00", "18:00");
        assertThat(SupplierAutoPayTimes.parseOrDefault("[]", mapper))
                .containsExactly("00:00", "18:00");
    }

    @Test
    void normalizesAndSorts() {
        assertThat(SupplierAutoPayTimes.normalize(List.of("18:00", "9:30", "09:30", "00:00")))
                .containsExactly("00:00", "09:30", "18:00");
    }

    @Test
    void matchesMinute() {
        assertThat(SupplierAutoPayTimes.matchesMinute(List.of("00:00", "18:00"), LocalTime.of(18, 0)))
                .isTrue();
        assertThat(SupplierAutoPayTimes.matchesMinute(List.of("00:00", "18:00"), LocalTime.of(18, 1)))
                .isFalse();
    }

    @Test
    void requireValidRejectsGarbage() {
        assertThatThrownBy(() -> SupplierAutoPayTimes.requireValid(List.of("noon")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
