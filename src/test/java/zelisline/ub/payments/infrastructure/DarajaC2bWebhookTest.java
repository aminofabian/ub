package zelisline.ub.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class DarajaC2bWebhookTest {

    private final DarajaPaymentGateway gateway = new DarajaPaymentGateway();

    @Test
    void c2bConfirmation_exposesBillRefAmountAndReceipt() {
        String body = """
                {
                  "TransactionType": "Pay Bill",
                  "TransID": "NLJ7RT61SV",
                  "TransTime": "20191122063845",
                  "TransAmount": "10.00",
                  "BusinessShortCode": "600426",
                  "BillRefNumber": "55440000",
                  "InvoiceNumber": "",
                  "OrgAccountBalance": "49197.00",
                  "ThirdPartyTransID": "",
                  "MSISDN": "254708374149",
                  "FirstName": "John"
                }
                """;

        var result = gateway.processWebhook(java.util.Map.of(), body);

        assertThat(result.success()).isTrue();
        assertThat(result.topic()).isEqualTo("c2b_confirmation");
        assertThat(result.gatewayTransactionId()).isEqualTo("NLJ7RT61SV");
        assertThat(result.reference()).isEqualTo("55440000");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(result.webhookEventId()).isEqualTo("NLJ7RT61SV");
    }
}
