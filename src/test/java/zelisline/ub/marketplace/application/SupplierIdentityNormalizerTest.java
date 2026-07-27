package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupplierIdentityNormalizerTest {

    @Test
    void phoneTailIgnoresCountryAndLeadingZero() {
        assertThat(SupplierIdentityNormalizer.phoneTail("+254714282874")).isEqualTo("714282874");
        assertThat(SupplierIdentityNormalizer.phoneTail("0714282874")).isEqualTo("714282874");
        assertThat(SupplierIdentityNormalizer.phoneTail("254714282874")).isEqualTo("714282874");
        assertThat(SupplierIdentityNormalizer.phoneTail("714282874")).isEqualTo("714282874");
    }

    @Test
    void normalizePhoneUnifiesLocalForms() {
        assertThat(SupplierIdentityNormalizer.normalizePhone("0714282874")).isEqualTo("254714282874");
        assertThat(SupplierIdentityNormalizer.normalizePhone("+254 714 282 874")).isEqualTo("254714282874");
        assertThat(SupplierIdentityNormalizer.normalizePhone("714282874")).isEqualTo("254714282874");
    }

    @Test
    void phoneLookupFormsShareTail() {
        var a = SupplierIdentityNormalizer.phoneLookupForms("0714282874");
        var b = SupplierIdentityNormalizer.phoneLookupForms("+254714282874");
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a.phoneTail()).isEqualTo(b.phoneTail()).isEqualTo("714282874");
    }

    @Test
    void normalizeEmailIsCaseInsensitive() {
        assertThat(SupplierIdentityNormalizer.normalizeEmail(" Shop@Example.COM "))
                .isEqualTo("shop@example.com");
    }
}
