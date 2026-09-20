package zelisline.ub.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.domain.spi.StkStatusResponse;

/**
 * Lipa Na M-Pesa Express (STK push): request body shape, till/paybill routing, query parsing.
 */
class DarajaStkPushTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void transactionType_followsShortcodeType() {
        assertThat(DarajaPaymentGateway.stkTransactionType(Map.of("shortcodeType", "till")))
                .isEqualTo("CustomerBuyGoodsOnline");
        assertThat(DarajaPaymentGateway.stkTransactionType(Map.of("shortcodeType", "paybill")))
                .isEqualTo("CustomerPayBillOnline");
        assertThat(DarajaPaymentGateway.stkTransactionType(Map.of()))
                .isEqualTo("CustomerPayBillOnline");
    }

    @Test
    void resolveTransactionType_partyBEqualsShortcodeUsesCollectionType() {
        // PartyB = collection account (= BusinessShortCode). Type must match the
        // Go Live shortcode, not the tenant destination in AccountReference.
        assertThat(DarajaPaymentGateway.resolveStkTransactionType(
                Map.of("shortcodeType", "paybill"), "174379", "174379"))
                .isEqualTo("CustomerPayBillOnline");
        assertThat(DarajaPaymentGateway.resolveStkTransactionType(
                Map.of("shortcodeType", "till"), "123456", "123456"))
                .isEqualTo("CustomerBuyGoodsOnline");
    }

    @Test
    void resolveTransactionType_tillUnderHoForcesBuyGoods() {
        assertThat(DarajaPaymentGateway.resolveStkTransactionType(
                Map.of("shortcodeType", "paybill"), "123456", "556677"))
                .isEqualTo("CustomerBuyGoodsOnline");
    }

    @Test
    void resolveTransactionType_explicitTransferTypeWins() {
        assertThat(DarajaPaymentGateway.resolveStkTransactionType(
                Map.of("shortcodeType", "till", "transfer_type", "CustomerPayBillOnline"),
                "174379", "174379"))
                .isEqualTo("CustomerPayBillOnline");
    }

    @Test
    void buildBody_paybillCollectionMatchesExpressSample() {
        Map<String, Object> body = DarajaPaymentGateway.buildStkRequestBody(
                "174379", "PWD", "20210628092408", "CustomerPayBillOnline",
                BigDecimal.ONE, "254722000000", "174379",
                "https://api.example.com/webhooks/daraja/stk", "5552830017", "Payment");

        assertThat(body.get("BusinessShortCode")).isEqualTo("174379");
        assertThat(body.get("PartyB")).isEqualTo("174379");
        assertThat(body.get("TransactionType")).isEqualTo("CustomerPayBillOnline");
        assertThat(body.get("AccountReference")).isEqualTo("5552830017");
    }

    @Test
    void accountReference_isAlphanumericAndCapped() {
        assertThat(DarajaPaymentGateway.accountReference("555 283-0017")).isEqualTo("5552830017");
        assertThat(DarajaPaymentGateway.accountReference("#ACC/2026 000111222")).isEqualTo("ACC202600011");
        assertThat(DarajaPaymentGateway.accountReference("  ")).isEqualTo("Kiosk");
        assertThat(DarajaPaymentGateway.accountReference("---")).isEqualTo("Kiosk");
        assertThat(DarajaPaymentGateway.accountReference(null)).isEqualTo("Kiosk");
    }

    @Test
    void password_isBase64OfShortcodePasskeyTimestamp() {
        String password = DarajaPaymentGateway.stkPassword("174379", "passkey", "20210628092408");

        assertThat(new String(Base64.getDecoder().decode(password), StandardCharsets.UTF_8))
                .isEqualTo("174379passkey20210628092408");
    }

    @Test
    void sandboxPasskey_isRecognised() {
        assertThat(DarajaPaymentGateway.isSandboxPasskey(
                "bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919")).isTrue();
        assertThat(DarajaPaymentGateway.isSandboxPasskey(
                "  BFB279F9AA9BDBCF158E97DD71A467CD2E0C893059B10F78E6B72ADA1ED2C919 ")).isTrue();
        assertThat(DarajaPaymentGateway.isSandboxPasskey("our-live-passkey")).isFalse();
        assertThat(DarajaPaymentGateway.isSandboxPasskey(null)).isFalse();
    }

    @Test
    void timestamp_isFourteenDigits() {
        assertThat(DarajaPaymentGateway.mpesaTimestamp()).matches("\\d{14}");
    }

    @Test
    void buildBody_tillDestinationKeepsAppShortcodeAsBusinessShortCode() {
        Map<String, Object> body = DarajaPaymentGateway.buildStkRequestBody(
                "174379", "PWD", "20210628092408", "CustomerBuyGoodsOnline",
                new BigDecimal("150.49"), "254722000000", "556677",
                "https://api.example.com/webhooks/daraja/stk", "ORDER-123456789", "Kiosk sale payment");

        assertThat(body.get("BusinessShortCode")).isEqualTo("174379");
        assertThat(body.get("PartyB")).isEqualTo("556677");
        assertThat(body.get("TransactionType")).isEqualTo("CustomerBuyGoodsOnline");
        assertThat(body.get("Amount")).isEqualTo(150);
        assertThat(body.get("PartyA")).isEqualTo("254722000000");
        assertThat(body.get("PhoneNumber")).isEqualTo("254722000000");
        assertThat(body.get("AccountReference")).isEqualTo("ORDER1234567");
        assertThat(body.get("TransactionDesc")).isEqualTo("Kiosk sale pa");
    }

    @Test
    void buildBody_fallsBackForBlankReferenceAndDescription() {
        Map<String, Object> body = DarajaPaymentGateway.buildStkRequestBody(
                "174379", "PWD", "20210628092408", "CustomerPayBillOnline",
                BigDecimal.ONE, "254722000000", "174379",
                "https://api.example.com/webhooks/daraja/stk", null, null);

        assertThat(body.get("AccountReference")).isEqualTo("Kiosk");
        assertThat(body.get("TransactionDesc")).isEqualTo("Payment");
        assertThat(body.keySet()).containsExactly(
                "BusinessShortCode", "Password", "Timestamp", "TransactionType", "Amount",
                "PartyA", "PartyB", "PhoneNumber", "CallBackURL", "AccountReference", "TransactionDesc");
    }

    @Test
    void query_resultCodeZeroIsPaid() {
        StkStatusResponse status = DarajaPaymentGateway.parseStkQueryResponse("""
                {
                  "ResponseCode": "0",
                  "ResultCode": "0",
                  "ResultDesc": "The service request is processed successfully."
                }
                """, MAPPER);

        assertThat(status.completed()).isTrue();
        assertThat(status.failed()).isFalse();
    }

    @Test
    void query_cancelledByUserIsTerminalFailure() {
        StkStatusResponse status = DarajaPaymentGateway.parseStkQueryResponse("""
                { "ResultCode": "1032", "ResultDesc": "Request cancelled by user" }
                """, MAPPER);

        assertThat(status.failed()).isTrue();
        assertThat(status.completed()).isFalse();
    }

    @Test
    void query_errorCodeStaysPending() {
        StkStatusResponse status = DarajaPaymentGateway.parseStkQueryResponse("""
                {
                  "requestId": "1c5b-4ba8",
                  "errorCode": "500.001.1001",
                  "errorMessage": "The transaction is being processed"
                }
                """, MAPPER);

        assertThat(status.completed()).isFalse();
        assertThat(status.failed()).isFalse();
        assertThat(status.resultCode()).isEqualTo("500.001.1001");
    }

    @Test
    void errorMessage_addsMitigationForKnownCodes() {
        assertThat(DarajaPaymentGateway.stkErrorMessage("404.001.03", "Invalid Access Token"))
                .contains("Invalid Access Token")
                .contains("consumer key");
        assertThat(DarajaPaymentGateway.stkErrorMessage(null, "Boom")).isEqualTo("Boom");
    }
}
