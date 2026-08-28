package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShiftTillLabelTest {

    @Test
    void rawUuidIsNotATillName() {
        assertThat(ShiftService.isRawTillDeviceId("04dd48d2-575f-4bb6-ad01-3a21efeb2260"))
                .isTrue();
        assertThat(ShiftService.isRawTillDeviceId("Front counter")).isFalse();
        assertThat(ShiftService.shortTillName("04dd48d2-575f-4bb6-ad01-3a21efeb2260"))
                .isEqualTo("Till 04dd48d2");
    }
}
