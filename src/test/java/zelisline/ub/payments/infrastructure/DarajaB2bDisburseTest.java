package zelisline.ub.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zelisline.ub.payments.infrastructure.DarajaPaymentGateway.B2BRequest;

/**
 * Daraja B2B disburse: command mapping, request body shape, and result parsing.
 */
class DarajaB2bDisburseTest {

    private static final Map<String, String> CREDS = Map.of(
            "consumerKey", "ck",
            "consumerSecret", "cs",
            "shortcode", "600000",
            "initiatorName", "kioskapi",
            "initiatorPassword", "pw");

    @Test
    void commandId_mapsPaybillAndTill() {
        assertThat(DarajaPaymentGateway.b2bCommandId("paybill", CREDS)).isEqualTo("BusinessPayBill");
        assertThat(DarajaPaymentGateway.b2bCommandId("till", CREDS)).isEqualTo("BusinessBuyGoods");
        assertThat(DarajaPaymentGateway.b2bCommandId(null, CREDS)).isEqualTo("BusinessPayBill");
    }

    @Test
    void buildBody_paybillCarriesAccountReferenceAndPartyFields() {
        B2BRequest request = new B2BRequest(
                CREDS, "https://api.example.com", "paybill", "247247", "INV-9",
                new BigDecimal("500.00"), "KES", "Custody settle");

        Map<String, Object> body = DarajaPaymentGateway.buildB2BRequestBody(
                request, "600000", "BusinessPayBill", "SECCRED", "600000",
                "https://api.example.com/webhooks/daraja/b2b/result",
                "https://api.example.com/webhooks/daraja/b2b/timeout");

        assertThat(body.get("CommandID")).isEqualTo("BusinessPayBill");
        assertThat(body.get("Initiator")).isEqualTo("kioskapi");
        assertThat(body.get("SecurityCredential")).isEqualTo("SECCRED");
        assertThat(body.get("PartyA")).isEqualTo("600000");
        assertThat(body.get("PartyB")).isEqualTo("247247");
        assertThat(body.get("AccountReference")).isEqualTo("INV-9");
        assertThat(body.get("Amount")).isEqualTo(500);
        assertThat(body.get("SenderIdentifierType")).isEqualTo("4");
        assertThat(body.get("RecieverIdentifierType")).isEqualTo("4");
        assertThat(body.get("ResultURL")).isEqualTo("https://api.example.com/webhooks/daraja/b2b/result");
        assertThat(body.get("QueueTimeOutURL")).isEqualTo("https://api.example.com/webhooks/daraja/b2b/timeout");
    }

    @Test
    void parseResult_readsConversationIdAndSuccess() {
        String payload = """
                {
                  "Result": {
                    "ResultCode": 0,
                    "ResultDesc": "The service request is processed successfully.",
                    "OriginatorConversationID": "orig-1",
                    "ConversationID": "conv-1",
                    "TransactionID": "QK12345"
                  }
                }
                """;

        var result = new DarajaPaymentGateway().parseB2BResult(payload);

        assertThat(result.accepted()).isTrue();
        assertThat(result.conversationId()).isEqualTo("conv-1");
        assertThat(result.originatorConversationId()).isEqualTo("orig-1");
    }

    @Test
    void parseResult_readsFailure() {
        String payload = """
                {
                  "Result": {
                    "ResultCode": 2001,
                    "ResultDesc": "The initiator information is invalid.",
                    "ConversationID": "conv-2"
                  }
                }
                """;

        var result = new DarajaPaymentGateway().parseB2BResult(payload);

        assertThat(result.accepted()).isFalse();
        assertThat(result.conversationId()).isEqualTo("conv-2");
        assertThat(result.message()).contains("initiator");
    }

    @Test
    void parseResult_readsImmediateInitiationShape() {
        String payload = """
                {
                  "ConversationID": "conv-9",
                  "OriginatorConversationID": "orig-9",
                  "ResponseCode": "0",
                  "ResponseDescription": "Accept the service request successfully."
                }
                """;

        var result = new DarajaPaymentGateway().parseB2BResult(payload);

        assertThat(result.accepted()).isTrue();
        assertThat(result.conversationId()).isEqualTo("conv-9");
    }
}
