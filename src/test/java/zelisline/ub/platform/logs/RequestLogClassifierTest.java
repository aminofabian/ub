package zelisline.ub.platform.logs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Path-to-category mapping for the Super Admin log feed. */
class RequestLogClassifierTest {

    private final RequestLogClassifier classifier = new RequestLogClassifier();

    @Test
    void safaricomCallbackPathsAreMpesa() {
        assertThat(classifier.classify("/webhooks/daraja/stk")).isEqualTo(RequestLogCategory.MPESA);
        assertThat(classifier.classify("/webhooks/daraja/c2b/confirmation"))
                .isEqualTo(RequestLogCategory.MPESA);
        assertThat(classifier.classify("/webhooks/daraja/c2b/validation"))
                .isEqualTo(RequestLogCategory.MPESA);
        assertThat(classifier.classify("/webhooks/daraja/b2b/result"))
                .isEqualTo(RequestLogCategory.MPESA);
        assertThat(classifier.classify("/webhooks/daraja/b2b/timeout"))
                .isEqualTo(RequestLogCategory.MPESA);
        assertThat(classifier.classify("/webhooks/kopokopo")).isEqualTo(RequestLogCategory.MPESA);
    }

    @Test
    void airtimeAndKplcWinOverThePaymentBucket() {
        assertThat(classifier.classify("/webhooks/instalipa/airtime"))
                .isEqualTo(RequestLogCategory.AIRTIME);
        assertThat(classifier.classify("/api/v1/kplc/tokens")).isEqualTo(RequestLogCategory.KPLC);
    }

    @Test
    void cashierAndFallbackBuckets() {
        assertThat(classifier.classify("/api/v1/sales")).isEqualTo(RequestLogCategory.CASHIER);
        assertThat(classifier.classify("/api/v1/products")).isEqualTo(RequestLogCategory.OTHER);
        assertThat(classifier.classify(null)).isEqualTo(RequestLogCategory.OTHER);
    }
}
