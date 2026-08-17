package zelisline.ub.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KopokopoBuygoodsWebhookTest {

    private final KopokopoPaymentGateway gateway = new KopokopoPaymentGateway();

    @Test
    void rootJsonApiBuygoods_keepsNameAndMaskedPhone() {
        String body = """
                {
                  "id": "7dc1a9e4-e0e6-4bf4-bd47-0e698cb57db9",
                  "type": "buygoods_transaction_received",
                  "attributes": {
                    "amount": "100.00",
                    "currency": "KES",
                    "system": "Lipa Na M-PESA",
                    "status": "Success",
                    "reference": "OAG81M7W3K",
                    "till_number": "123456",
                    "first_name": "JOHN",
                    "last_name": "DOE",
                    "phone_number": "+2547XXXXX123"
                  }
                }
                """;

        var result = gateway.processWebhook(java.util.Map.of(), body);

        assertThat(result.success()).isTrue();
        assertThat(result.topic()).isEqualTo("buygoods_transaction_received");
        assertThat(result.gatewayTransactionId()).isEqualTo("OAG81M7W3K");
        assertThat(result.firstName()).isEqualTo("JOHN");
        assertThat(result.lastName()).isEqualTo("DOE");
        assertThat(result.phoneIsMasked()).isTrue();
        assertThat(result.maskedPhone()).isEqualTo("2547XXXXX123");
        assertThat(result.phoneNumber()).isNull();
        assertThat(result.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void k2ConnectBuygoods_doesNotStripMaskedSender() {
        String body = """
                {"topic":"buygoods_transaction_received","id":"evt-1","event":{"resource":{
                  "amount":"150.00","status":"Received","reference":"OJL7OW3J59",
                  "sender_phone_number":"+2547XXXXX874","first_name":"JANE","last_name":"DOE",
                  "till_number":"K000123"
                }}}
                """;

        var result = gateway.processWebhook(java.util.Map.of(), body);

        assertThat(result.success()).isTrue();
        assertThat(result.phoneIsMasked()).isTrue();
        assertThat(result.maskedPhone()).isEqualTo("2547XXXXX874");
        assertThat(result.phoneNumber()).isNull();
        assertThat(result.firstName()).isEqualTo("JANE");
        assertThat(result.lastName()).isEqualTo("DOE");
    }

    @Test
    void unmaskedSenderStillNormalizesTo254() {
        String body = """
                {"topic":"buygoods_transaction_received","id":"evt-1","event":{"resource":{
                  "amount":"150.00","status":"Received","reference":"OJL7OW3J59",
                  "sender_phone_number":"+254714282874"
                }}}
                """;

        var result = gateway.processWebhook(java.util.Map.of(), body);

        assertThat(result.success()).isTrue();
        assertThat(result.phoneIsMasked()).isFalse();
        assertThat(result.phoneNumber()).isEqualTo("254714282874");
        assertThat(result.maskedPhone()).isNull();
    }
}
