package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StkPhoneNormalizerTest {

    @Test
    void dropsTrunkZeroKeptAfterCountryCode() {
        assertThat(StkPhoneNormalizer.normalize("+254 0725393621")).isEqualTo("254725393621");
        assertThat(StkPhoneNormalizer.normalize("2540725393621")).isEqualTo("254725393621");
        assertThat(StkPhoneNormalizer.normalize("0725393621")).isEqualTo("254725393621");
        assertThat(StkPhoneNormalizer.normalize("725393621")).isEqualTo("254725393621");
        assertThat(StkPhoneNormalizer.normalize("254725393621")).isEqualTo("254725393621");
        assertThat(StkPhoneNormalizer.normalize("+254 0110123456")).isEqualTo("254110123456");
    }
}
