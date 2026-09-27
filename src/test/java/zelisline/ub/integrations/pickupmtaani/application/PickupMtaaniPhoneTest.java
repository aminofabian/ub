package zelisline.ub.integrations.pickupmtaani.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PickupMtaaniPhoneTest {

    @Test
    void toApiFormat_normalisesKenyanMobiles() {
        assertThat(PickupMtaaniPhone.toApiFormat("0712345678")).isEqualTo("+254712345678");
        assertThat(PickupMtaaniPhone.toApiFormat("254712345678")).isEqualTo("+254712345678");
        assertThat(PickupMtaaniPhone.toApiFormat("+254712345678")).isEqualTo("+254712345678");
        assertThat(PickupMtaaniPhone.toApiFormat("254 712 345 678")).isEqualTo("+254712345678");
        assertThat(PickupMtaaniPhone.toApiFormat("0112345678")).isEqualTo("+254112345678");
    }

    @Test
    void toApiFormat_rejectsLandlinesAndGarbage() {
        assertThat(PickupMtaaniPhone.toApiFormat("0201234567")).isNull();
        assertThat(PickupMtaaniPhone.toApiFormat("12345")).isNull();
        assertThat(PickupMtaaniPhone.toApiFormat("")).isNull();
        assertThat(PickupMtaaniPhone.toApiFormat(null)).isNull();
    }
}
