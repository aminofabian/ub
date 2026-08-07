package zelisline.ub.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class KopokopoSendMoneyWebhookTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final KopokopoPaymentGateway gateway = new KopokopoPaymentGateway();

    private static final String SUCCESS_PAYLOAD = """
            {
              "data": {
                "id": "716481bc-635c-4d35-92c9-65bb0797196a",
                "type": "send_money",
                "attributes": {
                  "status": "Processed",
                  "created_at": "2025-09-23T20:05:46.522+03:00",
                  "source_identifier": "4321",
                  "currency": "KES",
                  "transfer_batches": [
                    {
                      "status": "Transferred",
                      "amount": "1500.0",
                      "disbursements": [
                        {
                          "amount": "1500.0",
                          "errors": null,
                          "status": "Transferred",
                          "phone_number": "254712345678",
                          "destination_type": "mobile_wallet",
                          "transaction_reference": "QCL160124A7"
                        }
                      ]
                    }
                  ],
                  "errors": null,
                  "metadata": {
                    "supplierInvoiceId": "inv-123",
                    "businessId": "biz-1",
                    "reference": "SUP-001"
                  }
                }
              }
            }
            """;

    @Test
    void detectsSendMoneyTypeOnDataNode() throws Exception {
        var root = mapper.readTree(SUCCESS_PAYLOAD);
        assertThat(KopokopoPaymentGateway.isSendMoneyPayload(root)).isTrue();
    }

    @Test
    void successCallback_routesAsSendMoneyWithMpesaRef() {
        var result = gateway.processWebhook(Map.of(), SUCCESS_PAYLOAD);

        assertThat(result.topic()).isEqualTo("send_money");
        assertThat(result.success()).isTrue();
        assertThat(result.terminalFailure()).isFalse();
        assertThat(result.gatewayCheckoutId()).isEqualTo("716481bc-635c-4d35-92c9-65bb0797196a");
        assertThat(result.gatewayTransactionId()).isEqualTo("QCL160124A7");
        assertThat(result.reference()).isEqualTo("inv-123");
        assertThat(result.amount()).isEqualByComparingTo("1500.0");
        assertThat(result.webhookEventId()).isEqualTo("716481bc-635c-4d35-92c9-65bb0797196a");
    }

    @Test
    void processedWithoutTransferred_staysPending() throws Exception {
        var attrs = mapper.readTree("""
                {
                  "status": "Processed",
                  "transfer_batches": [],
                  "errors": null
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateSendMoneyAttributes(attrs);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.transactionReference()).isNull();
    }

    @Test
    void failedStatus_isTerminalFailure() throws Exception {
        var attrs = mapper.readTree("""
                {
                  "status": "Failed",
                  "transfer_batches": [],
                  "errors": ["Insufficient balance"]
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateSendMoneyAttributes(attrs);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.description()).contains("Insufficient");
    }

    @Test
    void failedDisbursement_isTerminalFailure() {
        String payload = """
                {
                  "data": {
                    "id": "sm-failed-1",
                    "type": "send_money",
                    "attributes": {
                      "status": "Processed",
                      "transfer_batches": [
                        {
                          "status": "Failed",
                          "amount": "100.0",
                          "disbursements": [
                            {
                              "amount": "100.0",
                              "errors": ["Invalid phone number"],
                              "status": "Failed",
                              "phone_number": "254700000000",
                              "destination_type": "mobile_wallet",
                              "transaction_reference": null
                            }
                          ]
                        }
                      ],
                      "errors": null,
                      "metadata": {
                        "supplierInvoiceId": "inv-fail"
                      }
                    }
                  }
                }
                """;

        var result = gateway.processWebhook(Map.of(), payload);

        assertThat(result.topic()).isEqualTo("send_money");
        assertThat(result.success()).isFalse();
        assertThat(result.terminalFailure()).isTrue();
        assertThat(result.gatewayCheckoutId()).isEqualTo("sm-failed-1");
        assertThat(result.reference()).isEqualTo("inv-fail");
    }

    @Test
    void pendingStatus_isNeitherSuccessNorFailure() throws Exception {
        var attrs = mapper.readTree("""
                {
                  "status": "Pending",
                  "transfer_batches": null,
                  "errors": null
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateSendMoneyAttributes(attrs);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failed()).isFalse();
    }

    @Test
    void doesNotMisrouteSendMoneyAsIncomingPayment() {
        var result = gateway.processWebhook(Map.of(), SUCCESS_PAYLOAD);
        // Regression: previously data.type=send_money fell through to incoming_payment parsing.
        assertThat(result.topic()).isNotEqualTo("incoming_payment");
        assertThat(result.topic()).isEqualTo("send_money");
        assertThat(result.success()).isTrue();
    }
}
