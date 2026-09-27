package zelisline.ub.integrations.pickupmtaani.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.integrations.pickupmtaani.config.PickupMtaaniProperties;

class PickupMtaaniClientTest {

    private final PickupMtaaniClient client = new PickupMtaaniClient(
            new PickupMtaaniProperties(null, 0, 0, 0), new ObjectMapper());

    @Test
    void errorFrom_newerErrorResponse_usesDisplayMessageAndKeepsCode() {
        String body = """
                {"code":"PACKAGES.VALIDATION","display":{"message":"Receiver agent is hidden"},
                 "request_id":"req-9","correlation_id":"corr-9"}
                """;

        var ex = client.errorFrom(422, body);

        assertThat(ex.getMessage()).isEqualTo("Receiver agent is hidden");
        assertThat(ex.getCode()).isEqualTo("PACKAGES.VALIDATION");
        assertThat(ex.getRequestId()).isEqualTo("req-9");
        assertThat(ex.getHttpStatus()).isEqualTo(422);
    }

    @Test
    void errorFrom_validationEnvelope_usesTopLevelMessage() {
        var ex = client.errorFrom(400, "{\"message\":\"customerPhoneNumber is invalid\",\"validationErrors\":[]}");

        assertThat(ex.getMessage()).isEqualTo("customerPhoneNumber is invalid");
    }

    @Test
    void errorFrom_validationErrorsArray_usesFirstEntry() {
        String body = """
                {"validationErrors":[{"message":"doorstepDestinationId is required"}]}
                """;

        var ex = client.errorFrom(400, body);

        assertThat(ex.getMessage()).isEqualTo("doorstepDestinationId is required");
    }

    @Test
    void errorFrom_successEnvelope_usesMessage() {
        var ex = client.errorFrom(409, "{\"success\":false,\"message\":\"Agent not available\"}");

        assertThat(ex.getMessage()).isEqualTo("Agent not available");
    }

    @Test
    void errorFrom_nonJsonBody_fallsBackToStatusMessage() {
        var ex = client.errorFrom(500, "<html>gateway</html>");

        assertThat(ex.getMessage()).contains("HTTP 500");
        assertThat(ex.getCode()).isNull();
    }

    @Test
    void errorFrom_authFailure_isFlagged() {
        assertThat(client.errorFrom(401, "{}").authFailure()).isTrue();
        assertThat(client.errorFrom(403, "{}").authFailure()).isTrue();
        assertThat(client.errorFrom(500, "{}").authFailure()).isFalse();
        assertThat(new PickupMtaaniApiException(null, null, null, "network").authFailure()).isFalse();
    }

    private BigDecimal fee(String json) {
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            return client.extractFee(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void extractFee_knownKeyInDataWrapper() {
        assertThat(fee("{\"data\":{\"delivery_fee\":350}}")).isEqualByComparingTo("350.00");
    }

    @Test
    void extractFee_nestedAmountObject() {
        assertThat(fee("{\"delivery_charge\":{\"amount\":120}}")).isEqualByComparingTo("120.00");
    }

    @Test
    void extractFee_currencyStringIsCoerced() {
        assertThat(fee("{\"data\":{\"note\":\"x\",\"fee\":\"KES 1,200\"}}"))
                .isEqualByComparingTo("1200.00");
    }

    @Test
    void extractFee_keyHintFallback() {
        assertThat(fee("{\"data\":{\"total_amount\":99}}")).isEqualByComparingTo("99.00");
    }

    @Test
    void extractFee_noAmount_returnsNull() {
        assertThat(fee("{\"data\":{\"status\":\"ok\",\"count\":5}}")).isNull();
        assertThat(fee("{}")).isNull();
    }

    @Test
    void cancel_refusesNullId_beforeAnyRequest() {
        assertThatThrownBy(() -> client.cancelAgentPackage("key", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without an id");
        assertThatThrownBy(() -> client.cancelDoorstepPackage("key", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without an id");
    }
}
