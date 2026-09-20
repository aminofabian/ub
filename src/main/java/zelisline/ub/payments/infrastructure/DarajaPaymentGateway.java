package zelisline.ub.payments.infrastructure;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.DisplayInstructions;
import zelisline.ub.payments.domain.spi.PaymentGateway;
import zelisline.ub.payments.domain.spi.StkPushRequest;
import zelisline.ub.payments.domain.spi.StkPushResponse;
import zelisline.ub.payments.domain.spi.StkStatusResponse;
import zelisline.ub.payments.domain.spi.ValidationResult;
import zelisline.ub.payments.domain.spi.WebhookResult;

/**
 * Safaricom Daraja Lipa Na M-Pesa Online (STK Push) + query + callback parse.
 *
 * <p>Credentials (never from env at runtime — tenant BYO or platform SA encrypted store):
 * {@code consumerKey}, {@code consumerSecret}, {@code passkey}, {@code shortcode},
 * {@code shortcodeType} ({@code paybill}|{@code till}), {@code environment}.
 *
 * <p>Party A = customer MSISDN. {@code BusinessShortCode} and the STK password always
 * come from the Daraja app (platform or BYO). When {@code partyB} is set (till/paybill-only
 * tenants), that number is sent as Party B with {@code CustomerBuyGoodsOnline} or
 * {@code CustomerPayBillOnline} so the prompt names the shop destination.
 */
@Component
public class DarajaPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(DarajaPaymentGateway.class);

    private static final String SANDBOX_BASE = "https://sandbox.safaricom.co.ke";
    private static final String PRODUCTION_BASE = "https://api.safaricom.co.ke";

    private static final String OAUTH_PATH = "/oauth/v1/generate?grant_type=client_credentials";
    private static final String STK_PUSH_PATH = "/mpesa/stkpush/v1/processrequest";
    private static final String STK_QUERY_PATH = "/mpesa/stkpushquery/v1/query";
    /** Business-to-business transfer — used for custody settlement to a paybill/till. */
    private static final String B2B_PATH = "/mpesa/b2b/v1/paymentrequest";

    private static final int HTTP_CONNECT_TIMEOUT_MS = 5_000;
    private static final int HTTP_SOCKET_TIMEOUT_MS = 15_000;

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /** Safaricom public certificate (PEM) used to encrypt the B2B initiator password. */
    @Value("${app.payments.daraja.security-certificate-pem:}")
    private String securityCertificatePem;

    @Value("${app.payments.daraja.security-certificate-pem-production:}")
    private String securityCertificatePemProduction;

    @Override
    public String gatewayType() {
        return GatewayType.DARAJA.name();
    }

    @Override
    public StkPushResponse initiateStkPush(StkPushRequest request) {
        Map<String, String> creds = request.credentials();
        if (creds == null || creds.isEmpty()) {
            return StkPushResponse.rejected("NO_CREDENTIALS", "Daraja credentials are required");
        }

        String shortcode = firstNonBlank(creds.get("shortcode"), creds.get("tillNumber"), creds.get("businessShortCode"));
        String passkey = creds.get("passkey");
        if (shortcode == null || shortcode.isBlank()) {
            return StkPushResponse.rejected("MISSING_SHORTCODE", "shortcode is required in credentials");
        }
        if (passkey == null || passkey.isBlank()) {
            return StkPushResponse.rejected("MISSING_PASSKEY", "passkey is required in credentials");
        }

        String phone = normalizeMsisdn(request.phoneNumber());
        if (phone == null) {
            return StkPushResponse.rejected("MISSING_PHONE", "phoneNumber is required");
        }

        BigDecimal amount = request.amount().setScale(0, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            return StkPushResponse.rejected("INVALID_AMOUNT", "amount must be at least 1");
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String password = Base64.getEncoder().encodeToString(
                (shortcode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));

        boolean paybill = isPaybill(creds);
        String partyB = shortcode;
        String overridePartyB = firstNonBlank(creds.get("partyB"), creds.get("PartyB"), creds.get("receivingShortcode"));
        if (overridePartyB != null && !overridePartyB.isBlank()) {
            partyB = overridePartyB.replaceAll("\\D", "");
            paybill = isPaybill(creds);
        }
        String transactionType = paybill ? "CustomerPayBillOnline" : "CustomerBuyGoodsOnline";
        // AccountReference must stay short — Daraja caps ~12 chars for some shortcodes.
        String accountRef = truncate(
                firstNonBlank(creds.get("accountReference"), request.reference(), "Kiosk"), 12);
        String description = truncate(
                request.description() != null ? request.description() : "Payment", 13);

        String callback = request.callbackBaseUrl().replaceAll("/$", "") + "/webhooks/daraja/stk";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", shortcode);
        body.put("Password", password);
        body.put("Timestamp", timestamp);
        body.put("TransactionType", transactionType);
        body.put("Amount", amount.intValueExact());
        body.put("PartyA", phone);
        body.put("PartyB", partyB);
        body.put("PhoneNumber", phone);
        body.put("CallBackURL", callback);
        body.put("AccountReference", accountRef);
        body.put("TransactionDesc", description);

        try {
            String accessToken = obtainAccessToken(creds);
            String json = objectMapper.writeValueAsString(body);
            HttpResponse<String> response = Unirest.post(baseUrl(creds) + STK_PUSH_PATH)
                    .connectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                    .socketTimeout(HTTP_SOCKET_TIMEOUT_MS)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(json)
                    .asString();

            JsonNode root = objectMapper.readTree(response.getBody() != null ? response.getBody() : "{}");
            String responseCode = text(root, "ResponseCode");
            String responseDesc = text(root, "ResponseDescription");
            String checkoutId = text(root, "CheckoutRequestID");
            String merchantId = text(root, "MerchantRequestID");

            if (response.getStatus() >= 200 && response.getStatus() < 300
                    && ("0".equals(responseCode) || checkoutId != null)) {
                log.info("Daraja STK accepted: checkoutId={} partyB={} type={}", checkoutId, partyB, transactionType);
                return StkPushResponse.accepted(
                        checkoutId,
                        merchantId,
                        responseCode != null ? responseCode : "0",
                        responseDesc != null ? responseDesc : "Success");
            }

            String error = firstNonBlank(text(root, "errorMessage"), responseDesc, response.getBody());
            log.warn("Daraja STK rejected: status={} body={}", response.getStatus(), response.getBody());
            return StkPushResponse.rejected(
                    responseCode != null ? responseCode : String.valueOf(response.getStatus()),
                    error != null ? error : "STK request declined");
        } catch (Exception e) {
            log.error("Daraja STK failed", e);
            return StkPushResponse.rejected("NETWORK_ERROR", e.getMessage() != null ? e.getMessage() : "Daraja STK failed");
        }
    }

    @Override
    public StkStatusResponse queryStkStatus(String gatewayCheckoutRequestId) {
        return new StkStatusResponse("PENDING", "Status unknown without credentials",
                false, false, null, null);
    }

    /**
     * Query STK status with credentials (polling scheduler / reconcile).
     */
    public StkStatusResponse queryStkStatus(String checkoutRequestId, Map<String, String> creds) {
        if (checkoutRequestId == null || checkoutRequestId.isBlank() || creds == null) {
            return new StkStatusResponse("ERROR", "Missing checkout id or credentials",
                    false, false, null, null);
        }
        String shortcode = firstNonBlank(creds.get("shortcode"), creds.get("tillNumber"), creds.get("businessShortCode"));
        String passkey = creds.get("passkey");
        if (shortcode == null || passkey == null) {
            return new StkStatusResponse("ERROR", "shortcode/passkey required", false, false, null, null);
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String password = Base64.getEncoder().encodeToString(
                (shortcode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = Map.of(
                "BusinessShortCode", shortcode,
                "Password", password,
                "Timestamp", timestamp,
                "CheckoutRequestID", checkoutRequestId);

        try {
            String accessToken = obtainAccessToken(creds);
            String json = objectMapper.writeValueAsString(body);
            HttpResponse<String> response = Unirest.post(baseUrl(creds) + STK_QUERY_PATH)
                    .connectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                    .socketTimeout(HTTP_SOCKET_TIMEOUT_MS)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(json)
                    .asString();

            String raw = response.getBody();
            JsonNode root = objectMapper.readTree(raw != null ? raw : "{}");
            String resultCode = firstNonBlank(text(root, "ResultCode"), text(root, "ResponseCode"));
            String resultDesc = firstNonBlank(text(root, "ResultDesc"), text(root, "ResponseDescription"));

            if ("0".equals(resultCode)) {
                // Query success does not always include receipt — treat completed without receipt
                // only when ResultDesc indicates success; receipt may arrive via callback.
                return new StkStatusResponse(resultCode, resultDesc, true, false, null, raw);
            }
            // 1032 = cancelled by user; 1037 = timeout; 4999 = request still processing sometimes
            if (resultCode != null && !"4999".equals(resultCode) && !"1".equals(resultCode)) {
                // Daraja uses ResultCode 0 success; non-zero terminal failures for query
                // while request is still open often return ResponseCode "0" with ResultCode pending.
                if ("1032".equals(resultCode) || "1037".equals(resultCode) || "1001".equals(resultCode)) {
                    return new StkStatusResponse(resultCode, resultDesc, false, true, null, raw);
                }
            }
            // Still processing / unknown — leave pending
            if (resultCode == null || "1".equals(resultCode) || "4999".equals(resultCode)) {
                return new StkStatusResponse(
                        resultCode != null ? resultCode : "PENDING",
                        resultDesc != null ? resultDesc : "Pending",
                        false, false, null, raw);
            }
            // Other non-zero → failed
            return new StkStatusResponse(resultCode, resultDesc, false, true, null, raw);
        } catch (Exception e) {
            log.warn("Daraja STK query failed checkoutId={}: {}", checkoutRequestId, e.getMessage());
            return new StkStatusResponse("ERROR", e.getMessage(), false, false, null, null);
        }
    }

    @Override
    public WebhookResult processWebhook(Map<String, String> headers, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return WebhookResult.empty(rawBody);
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode stk = root.path("Body").path("stkCallback");
            if (stk.isMissingNode() || stk.isNull()) {
                // C2B confirmation shape
                if (root.has("TransID") || root.has("TransactionType")) {
                    return parseC2bConfirmation(root, rawBody);
                }
                return WebhookResult.empty(rawBody);
            }

            String checkoutId = text(stk, "CheckoutRequestID");
            String merchantId = text(stk, "MerchantRequestID");
            String resultCode = text(stk, "ResultCode");
            String resultDesc = text(stk, "ResultDesc");
            boolean success = "0".equals(resultCode);
            boolean failed = !success && resultCode != null && !resultCode.isBlank();

            BigDecimal amount = null;
            String receipt = null;
            String phone = null;
            JsonNode items = stk.path("CallbackMetadata").path("Item");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String name = text(item, "Name");
                    if (name == null) {
                        continue;
                    }
                    JsonNode value = item.get("Value");
                    if (value == null || value.isNull()) {
                        continue;
                    }
                    switch (name) {
                        case "Amount" -> amount = new BigDecimal(value.asText());
                        case "MpesaReceiptNumber" -> receipt = value.asText();
                        case "PhoneNumber" -> phone = normalizeMsisdn(value.asText());
                        default -> {
                        }
                    }
                }
            }

            String eventId = firstNonBlank(receipt, checkoutId, merchantId);
            return new WebhookResult(
                    null,
                    receipt,
                    phone,
                    amount,
                    merchantId,
                    success,
                    failed,
                    checkoutId,
                    eventId,
                    "stk_callback",
                    rawBody,
                    failed ? resultDesc : null);
        } catch (Exception e) {
            log.warn("Daraja webhook parse failed: {}", e.getMessage());
            return WebhookResult.empty(rawBody);
        }
    }

    private WebhookResult parseC2bConfirmation(JsonNode root, String rawBody) {
        String receipt = text(root, "TransID");
        String phone = normalizeMsisdn(text(root, "MSISDN"));
        BigDecimal amount = null;
        String amountRaw = text(root, "TransAmount");
        if (amountRaw != null) {
            try {
                amount = new BigDecimal(amountRaw);
            } catch (NumberFormatException ignored) {
                // leave null
            }
        }
        String billRef = firstNonBlank(text(root, "BillRefNumber"), text(root, "AccountReference"));
        boolean success = receipt != null && !receipt.isBlank();
        return new WebhookResult(
                null,
                receipt,
                phone,
                amount,
                billRef,
                success,
                false,
                null,
                receipt,
                "c2b_confirmation",
                rawBody);
    }

    @Override
    public DisplayInstructions getDisplayInstructions(String businessId) {
        return null;
    }

    @Override
    public ValidationResult validateConfiguration(PaymentGatewayConfig config) {
        String credsJson = config.getCredentialsJson();
        if (credsJson == null || credsJson.isBlank()) {
            return ValidationResult.failure("NO_CREDENTIALS", "No credentials configured", null);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> creds = objectMapper.readValue(credsJson, Map.class);
            return validateCredentials(creds);
        } catch (Exception e) {
            return ValidationResult.failure("INVALID_JSON", "Credentials are not valid JSON: " + e.getMessage(), null);
        }
    }

    public ValidationResult validateCredentials(Map<String, String> creds) {
        if (creds == null || !isPresent(creds, "consumerKey") || !isPresent(creds, "consumerSecret")) {
            return ValidationResult.failure("NO_CREDENTIALS", "consumerKey and consumerSecret are required", null);
        }
        try {
            String token = obtainAccessToken(creds);
            if (token != null && !token.isBlank()) {
                return ValidationResult.success();
            }
            return ValidationResult.failure("AUTH_FAILED", "Could not obtain Daraja access token", null);
        } catch (Exception e) {
            return ValidationResult.failure(
                    "AUTH_FAILED",
                    e.getMessage() != null ? e.getMessage() : "Daraja auth failed",
                    null);
        }
    }

    /**
     * C2B validation / confirmation ACK payloads Safaricom expects.
     */
    public static Map<String, Object> c2bAck(int resultCode, String resultDesc) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("ResultCode", resultCode);
        ack.put("ResultDesc", resultDesc != null ? resultDesc : "Accepted");
        return ack;
    }

    // ── B2B disburse (custody settle to a paybill / Buy Goods till) ────

    /** One B2B transfer. {@code destinationType} is {@code paybill} or {@code till}. */
    public record B2BRequest(
            Map<String, String> credentials,
            String callbackBaseUrl,
            String destinationType,
            String partyB,
            String accountReference,
            BigDecimal amount,
            String currency,
            String remarks
    ) {
    }

    public record B2BResult(
            boolean accepted,
            String conversationId,
            String originatorConversationId,
            String code,
            String message
    ) {
        static B2BResult rejected(String code, String message) {
            return new B2BResult(false, null, null, code, message);
        }
    }

    /**
     * Initiate a Business-to-Business transfer. For a paybill destination this is
     * {@code BusinessPayBill} (needs AccountReference); for a Buy Goods till it is
     * {@code BusinessBuyGoods}. Requires an initiator name/password in credentials and
     * the Safaricom public certificate to build the SecurityCredential.
     */
    public B2BResult sendB2B(B2BRequest request) {
        Map<String, String> creds = request.credentials();
        if (creds == null || creds.isEmpty()) {
            return B2BResult.rejected("NO_CREDENTIALS", "Daraja credentials are required");
        }
        String partyA = firstNonBlank(creds.get("b2bShortcode"), creds.get("shortcode"), creds.get("tillNumber"));
        if (partyA == null) {
            return B2BResult.rejected("MISSING_SHORTCODE", "A B2B shortcode is required");
        }
        String initiator = textOrNull(creds.get("initiatorName"));
        if (initiator == null) {
            return B2BResult.rejected("MISSING_INITIATOR", "initiatorName is required for Daraja B2B");
        }
        String initiatorPassword = textOrNull(creds.get("initiatorPassword"));
        if (initiatorPassword == null) {
            return B2BResult.rejected("MISSING_INITIATOR_PASSWORD", "initiatorPassword is required for Daraja B2B");
        }
        String partyB = digitsOnly(request.partyB());
        if (partyB == null) {
            return B2BResult.rejected("MISSING_PARTY_B", "The destination paybill/till number is required");
        }
        boolean paybill = isPaybillDestination(request.destinationType());
        if (paybill && textOrNull(request.accountReference()) == null) {
            return B2BResult.rejected("MISSING_ACCOUNT", "AccountReference is required for a paybill destination");
        }
        BigDecimal amount = request.amount() == null ? null : request.amount().setScale(0, RoundingMode.HALF_UP);
        if (amount == null || amount.signum() <= 0) {
            return B2BResult.rejected("INVALID_AMOUNT", "amount must be at least 1");
        }

        String securityCredential;
        try {
            securityCredential = buildSecurityCredential(initiatorPassword, creds);
        } catch (Exception e) {
            log.warn("Daraja B2B security credential failed: {}", e.getMessage());
            return B2BResult.rejected("SECURITY_CREDENTIAL_FAILED",
                    e.getMessage() != null ? e.getMessage() : "Could not build SecurityCredential");
        }

        String base = request.callbackBaseUrl() == null ? "" : request.callbackBaseUrl().replaceAll("/$", "");
        String commandId = b2bCommandId(request.destinationType(), creds);
        String requester = firstNonBlank(creds.get("b2bRequester"), partyA);
        Map<String, Object> body = buildB2BRequestBody(
                request, partyA, commandId, securityCredential, requester,
                base + "/webhooks/daraja/b2b/result", base + "/webhooks/daraja/b2b/timeout");

        try {
            String accessToken = obtainAccessToken(creds);
            HttpResponse<String> response = Unirest.post(baseUrl(creds) + B2B_PATH)
                    .connectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                    .socketTimeout(HTTP_SOCKET_TIMEOUT_MS)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .asString();

            JsonNode root = objectMapper.readTree(response.getBody() != null ? response.getBody() : "{}");
            String code = text(root, "ResponseCode");
            String conversationId = text(root, "ConversationID");
            String originator = text(root, "OriginatorConversationID");
            String desc = text(root, "ResponseDescription");
            if (response.getStatus() >= 200 && response.getStatus() < 300
                    && ("0".equals(code) || conversationId != null)) {
                log.info("Daraja B2B accepted: command={} conversationId={} partyB={}", commandId, conversationId, partyB);
                return new B2BResult(true, conversationId, originator,
                        code != null ? code : "0", desc != null ? desc : "Accepted");
            }
            String error = firstNonBlank(text(root, "errorMessage"), desc, response.getBody());
            log.warn("Daraja B2B rejected: status={} body={}", response.getStatus(), response.getBody());
            return B2BResult.rejected(code != null ? code : String.valueOf(response.getStatus()),
                    error != null ? error : "B2B request declined");
        } catch (Exception e) {
            log.error("Daraja B2B failed", e);
            return B2BResult.rejected("NETWORK_ERROR", e.getMessage() != null ? e.getMessage() : "Daraja B2B failed");
        }
    }

    /** Parse a B2B result/timeout callback, or an immediate initiation response. */
    public B2BResult parseB2BResult(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return B2BResult.rejected("EMPTY", "Empty B2B payload");
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode result = root.path("Result");
            if (!result.isMissingNode() && !result.isNull()) {
                String code = text(result, "ResultCode");
                return new B2BResult(
                        "0".equals(code),
                        text(result, "ConversationID"),
                        text(result, "OriginatorConversationID"),
                        code,
                        text(result, "ResultDesc"));
            }
            String code = text(root, "ResponseCode");
            return new B2BResult(
                    "0".equals(code),
                    text(root, "ConversationID"),
                    text(root, "OriginatorConversationID"),
                    code,
                    text(root, "ResponseDescription"));
        } catch (Exception e) {
            return B2BResult.rejected("PARSE_ERROR", e.getMessage());
        }
    }

    public static String b2bCommandId(String destinationType, Map<String, String> creds) {
        boolean paybill = isPaybillDestination(destinationType);
        if (paybill) {
            String override = creds != null ? textOrNull(creds.get("b2bPaybillCommand")) : null;
            return override != null ? override : "BusinessPayBill";
        }
        String override = creds != null ? textOrNull(creds.get("b2bTillCommand")) : null;
        return override != null ? override : "BusinessBuyGoods";
    }

    static Map<String, Object> buildB2BRequestBody(
            B2BRequest request,
            String partyA,
            String commandId,
            String securityCredential,
            String requester,
            String resultUrl,
            String timeoutUrl
    ) {
        boolean paybill = isPaybillDestination(request.destinationType());
        BigDecimal amount = request.amount().setScale(0, RoundingMode.HALF_UP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Initiator", request.credentials().get("initiatorName"));
        body.put("SecurityCredential", securityCredential);
        body.put("CommandID", commandId);
        body.put("SenderIdentifierType", "4");
        body.put("RecieverIdentifierType", "4");
        body.put("Amount", amount.intValueExact());
        body.put("PartyA", digitsOnly(partyA));
        body.put("PartyB", digitsOnly(request.partyB()));
        body.put("AccountReference", paybill
                ? request.accountReference().trim()
                : firstNonBlank(request.accountReference(), "Kiosk"));
        body.put("Requester", requester);
        body.put("Remarks", firstNonBlank(request.remarks(), "Kiosk custody settle"));
        body.put("QueueTimeOutURL", timeoutUrl);
        body.put("ResultURL", resultUrl);
        return body;
    }

    static boolean isPaybillDestination(String destinationType) {
        return destinationType == null || !"till".equalsIgnoreCase(destinationType.trim());
    }

    private String buildSecurityCredential(String initiatorPassword, Map<String, String> creds) throws Exception {
        String override = textOrNull(creds.get("securityCertificatePem"));
        String pem = override != null ? override
                : (isProduction(creds) ? securityCertificatePemProduction : securityCertificatePem);
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "Safaricom security certificate not configured (app.payments.daraja.security-certificate-pem)");
        }
        PublicKey publicKey = loadPublicKey(pem);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encrypted = cipher.doFinal(initiatorPassword.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private static PublicKey loadPublicKey(String pem) throws Exception {
        String normalized = pem.replace("\\n", "\n").trim();
        if (normalized.contains("BEGIN CERTIFICATE")) {
            try (ByteArrayInputStream in = new ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8))) {
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(in);
                return cert.getPublicKey();
            }
        }
        String base64 = normalized
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static String digitsOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }

    private static String textOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    // ── Internals ────────────────────────────────────────────────────

    private String obtainAccessToken(Map<String, String> creds) {
        String key = creds.get("consumerKey");
        String secret = creds.get("consumerSecret");
        if (key == null || secret == null) {
            throw new IllegalStateException("consumerKey/consumerSecret required");
        }
        String cacheKey = key + "|" + baseUrl(creds);
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
            return cached.token;
        }

        String basic = Base64.getEncoder().encodeToString((key + ":" + secret).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = Unirest.get(baseUrl(creds) + OAUTH_PATH)
                .connectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                .socketTimeout(HTTP_SOCKET_TIMEOUT_MS)
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json")
                .asString();

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException("Daraja OAuth failed HTTP " + response.getStatus()
                    + ": " + truncate(response.getBody(), 200));
        }
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String token = text(root, "access_token");
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Daraja OAuth response missing access_token");
            }
            long expiresIn = root.has("expires_in") ? root.get("expires_in").asLong(3599) : 3599L;
            tokenCache.put(cacheKey, new CachedToken(token, System.currentTimeMillis() + (expiresIn - 60) * 1000L));
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Daraja OAuth parse failed: " + e.getMessage(), e);
        }
    }

    private static String baseUrl(Map<String, String> creds) {
        return isProduction(creds) ? PRODUCTION_BASE : SANDBOX_BASE;
    }

    private static boolean isProduction(Map<String, String> creds) {
        String env = creds.get("environment");
        return env != null && "production".equalsIgnoreCase(env.trim());
    }

    private static boolean isPaybill(Map<String, String> creds) {
        String type = firstNonBlank(creds.get("shortcodeType"), creds.get("type"));
        if (type == null) {
            return true;
        }
        String t = type.trim().toLowerCase();
        return !"till".equals(t) && !"buygoods".equals(t) && !"buy_goods".equals(t);
    }

    static String normalizeMsisdn(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String phone = raw.replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) {
            phone = "254" + phone.substring(1);
        }
        if (!phone.startsWith("254") && phone.length() == 9) {
            phone = "254" + phone;
        }
        if (!phone.startsWith("254") || phone.length() < 12) {
            return phone.isBlank() ? null : phone;
        }
        return phone;
    }

    private static boolean isPresent(Map<String, String> map, String key) {
        String v = map.get(key);
        return v != null && !v.isBlank();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String v = node.get(field).asText();
        return v != null && !v.isBlank() ? v : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private record CachedToken(String token, long expiresAtMs) {
    }
}
