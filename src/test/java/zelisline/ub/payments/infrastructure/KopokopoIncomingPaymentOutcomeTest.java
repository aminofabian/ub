package zelisline.ub.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class KopokopoIncomingPaymentOutcomeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successWithMpesaReceipt_isCompleted() throws Exception {
        var attrs = mapper.readTree("""
                {
                  "status": "Success",
                  "event": {
                    "type": "Incoming Payment Request",
                    "resource": {
                      "id": "f39-0bff-44ef4-0629-481f-83cd-d101f",
                      "reference": "OJL7OW3J59",
                      "amount": "15.0",
                      "status": "Received"
                    },
                    "errors": null
                  }
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateIncomingPaymentAttributes(attrs);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.mpesaReceipt()).isEqualTo("OJL7OW3J59");
    }

    @Test
    void failedWithErrors_isFailedNotCompleted() throws Exception {
        var attrs = mapper.readTree("""
                {
                  "status": "Failed",
                  "event": {
                    "type": "Incoming Payment Request",
                    "resource": null,
                    "errors": ["The initiator information is invalid."]
                  }
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateIncomingPaymentAttributes(attrs);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.mpesaReceipt()).isNull();
        assertThat(outcome.description()).contains("initiator");
    }

    @Test
    void successWithoutReceipt_staysPending() throws Exception {
        var attrs = mapper.readTree("""
                {
                  "status": "Success",
                  "event": {
                    "type": "Incoming Payment Request",
                    "resource": null,
                    "errors": null
                  }
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateIncomingPaymentAttributes(attrs);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.mpesaReceipt()).isNull();
    }

    @Test
    void receivedAttributeAlone_isNotCompleted() throws Exception {
        // attributes.status for STK is Success/Failed/Pending — "Received" alone must not pay.
        var attrs = mapper.readTree("""
                {
                  "status": "Received",
                  "event": {
                    "resource": null,
                    "errors": null
                  }
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateIncomingPaymentAttributes(attrs);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failed()).isFalse();
    }

    @Test
    void pending_isNeitherCompletedNorFailed() throws Exception {
        var attrs = mapper.readTree("""
                {
                  "status": "Pending",
                  "event": {
                    "resource": null,
                    "errors": null
                  }
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateIncomingPaymentAttributes(attrs);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failed()).isFalse();
    }

    @Test
    void cancelledStatus_isFailed() throws Exception {
        var attrs = mapper.readTree("""
                {
                  "status": "Cancelled",
                  "event": {
                    "resource": null,
                    "errors": ["Request cancelled by user"]
                  }
                }
                """);

        var outcome = KopokopoPaymentGateway.evaluateIncomingPaymentAttributes(attrs);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.mpesaReceipt()).isNull();
    }

    @Test
    void processWebhook_successPayload_exposesMpesaReceiptAsTxnId() throws Exception {
        var gateway = new KopokopoPaymentGateway();
        String body = """
                {
                  "data": {
                    "id": "49b2bf39-0bff-4f37-8b19-43ca21ab3bf2",
                    "type": "incoming_payment",
                    "attributes": {
                      "status": "Success",
                      "event": {
                        "type": "Incoming Payment Request",
                        "resource": {
                          "id": "f39-0bff-44ef4",
                          "reference": "OJL7OW3J59",
                          "sender_phone_number": "+254712345678",
                          "amount": "15.0",
                          "status": "Received"
                        },
                        "errors": null
                      },
                      "metadata": {
                        "reference": "merchant-idempotency-key"
                      }
                    }
                  }
                }
                """;

        var result = gateway.processWebhook(java.util.Map.of(), body);

        assertThat(result.success()).isTrue();
        assertThat(result.terminalFailure()).isFalse();
        assertThat(result.gatewayTransactionId()).isEqualTo("OJL7OW3J59");
        assertThat(result.gatewayCheckoutId()).isEqualTo("49b2bf39-0bff-4f37-8b19-43ca21ab3bf2");
        assertThat(result.reference()).isEqualTo("merchant-idempotency-key");
    }

    @Test
    void processWebhook_failedDecline_isTerminalFailure() throws Exception {
        var gateway = new KopokopoPaymentGateway();
        String body = """
                {
                  "data": {
                    "id": "b01ac495-0969-4a7d-b35a-57ccbf541f1d",
                    "type": "incoming_payment",
                    "attributes": {
                      "status": "Failed",
                      "event": {
                        "type": "Incoming Payment Request",
                        "resource": null,
                        "errors": ["Request cancelled by user"]
                      },
                      "metadata": {
                        "reference": "merchant-idempotency-key"
                      }
                    }
                  }
                }
                """;

        var result = gateway.processWebhook(java.util.Map.of(), body);

        assertThat(result.success()).isFalse();
        assertThat(result.terminalFailure()).isTrue();
        assertThat(result.gatewayTransactionId()).isNull();
    }
}
