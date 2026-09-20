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
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 * come from the Daraja app (platform or BYO) — the shortcode used on Go Live.
 * {@code PartyB} defaults to that same shortcode; per Safaricom FAQ a Buy Goods till
 * under the same Head Office may be sent instead, with {@code CustomerBuyGoodsOnline}.
 * Arbitrary bank paybills are not supported (and there is no B2B settle on this path).
 *
 * <p>The Daraja app must be subscribed to <em>Lipa Na M-Pesa Online</em>. An app with
 * only other products (e.g. Daraja Direct Payments) issues a token that reaches this
 * endpoint but never delivers a prompt.
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

    /**
     * The passkey Safaricom publishes in the sandbox simulator test data. Production
     * needs the passkey emailed after Go Live; the sandbox one against a live shortcode
     * is accepted by Daraja and then dropped, so no prompt ever reaches the handset.
     */
    private static final String SANDBOX_PASSKEY =
            "bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919";

    private static final int HTTP_CONNECT_TIMEOUT_MS = 5_000;
    private static final int HTTP_SOCKET_TIMEOUT_MS = 15_000;

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId MPESA_ZONE = ZoneId.of("Africa/Nairobi");

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
        if (isSandboxPasskey(passkey) && isProduction(creds)) {
            return StkPushResponse.rejected("SANDBOX_PASSKEY",
                    "This is Safaricom's sandbox passkey on a production shortcode. Daraja accepts "
                            + "the request but never sends the prompt. Use the passkey emailed "
                            + "after Go Live.");
        }

        shortcode = digitsOnly(shortcode);
        if (!isValidShortcode(shortcode)) {
            return StkPushResponse.rejected("INVALID_SHORTCODE",
                    "BusinessShortCode must be the 5-7 digit shortcode used on Go Live");
        }

        String phone = normalizeMsisdn(request.phoneNumber());
        if (phone == null) {
            return StkPushResponse.rejected("MISSING_PHONE", "phoneNumber is required");
        }
        if (!isValidMsisdn(phone)) {
            return StkPushResponse.rejected("INVALID_PHONE",
                    "phoneNumber must be a Safaricom M-Pesa number in the 2547XXXXXXXX format");
        }

        BigDecimal amount = request.amount().setScale(0, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            return StkPushResponse.rejected("INVALID_AMOUNT", "amount must be at least 1");
        }

        // Express sample: PartyB defaults to BusinessShortCode. Till-under-HO FAQ may
        // set PartyB to a store till (then type must be CustomerBuyGoodsOnline).
        String partyB = shortcode;
        String destination = digitsOnly(firstNonBlank(
                creds.get("partyB"), creds.get("PartyB"), creds.get("receivingShortcode")));
        if (destination != null) {
            if (!isValidShortcode(destination)) {
                return StkPushResponse.rejected("INVALID_PARTY_B",
                        "Destination till/paybill must be 5-7 digits");
            }
            partyB = destination;
        }

        // TransactionType follows the credit party, not the tenant AccountReference:
        //   PartyB == BusinessShortCode + paybill → CustomerPayBillOnline
        //   PartyB == BusinessShortCode + till    → CustomerBuyGoodsOnline
        //   PartyB is a different till (HO FAQ)   → CustomerBuyGoodsOnline
        String transactionType = resolveStkTransactionType(creds, shortcode, partyB);
        String timestamp = mpesaTimestamp();
        String password = stkPassword(shortcode, passkey, timestamp);
        String accountRef = firstNonBlank(creds.get("accountReference"), request.reference(), "Kiosk");
        String callback = request.callbackBaseUrl().replaceAll("/$", "") + "/webhooks/daraja/stk";

        Map<String, Object> body = buildStkRequestBody(
                shortcode, password, timestamp, transactionType, amount, phone, partyB,
                callback, accountRef, request.description());

        if (!isProduction(creds)) {
            log.warn("Daraja STK on sandbox shortcode={} — the prompt only reaches Safaricom "
                    + "test MSISDNs; {} will not ring", shortcode, phone);
        }
        // Daraja answers ResponseCode 0 for requests it later drops silently (sandbox app,
        // PartyB outside the Head Office), so the exact outbound body is logged to make a
        // missing prompt diagnosable. Password is a rotating secret and is redacted.
        if (log.isInfoEnabled()) {
            Map<String, Object> redacted = new LinkedHashMap<>(body);
            redacted.put("Password", "***");
            log.info("Daraja STK request env={} body={}",
                    isProduction(creds) ? "production" : "sandbox", redacted);
        }

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
                // env is logged because a sandbox app accepts the request and returns a
                // ws_CO_ id, but only ever pushes the prompt to Safaricom's test MSISDNs.
                log.info("Daraja STK accepted: checkoutId={} env={} shortcode={} partyB={} type={} accountRef={}",
                        checkoutId, isProduction(creds) ? "production" : "sandbox",
                        shortcode, partyB, transactionType, body.get("AccountReference"));
                return StkPushResponse.accepted(
                        checkoutId,
                        merchantId,
                        responseCode != null ? responseCode : "0",
                        responseDesc != null ? responseDesc : "Success");
            }

            String errorCode = text(root, "errorCode");
            String error = firstNonBlank(text(root, "errorMessage"), responseDesc, response.getBody());
            log.warn("Daraja STK rejected: status={} shortcode={} partyB={} body={}",
                    response.getStatus(), shortcode, partyB, response.getBody());
            return StkPushResponse.rejected(
                    firstNonBlank(errorCode, responseCode, String.valueOf(response.getStatus())),
                    stkErrorMessage(errorCode, error));
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

        shortcode = digitsOnly(shortcode);
        String timestamp = mpesaTimestamp();
        String password = stkPassword(shortcode, passkey, timestamp);

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

            return parseStkQueryResponse(response.getBody(), objectMapper);
        } catch (Exception e) {
            log.warn("Daraja STK query failed checkoutId={}: {}", checkoutRequestId, e.getMessage());
            return new StkStatusResponse("ERROR", e.getMessage(), false, false, null, null);
        }
    }

    /** Timestamp must be M-Pesa (Nairobi) wall clock, not the host's zone. */
    static String mpesaTimestamp() {
        return ZonedDateTime.now(MPESA_ZONE).format(TIMESTAMP_FMT);
    }

    static boolean isSandboxPasskey(String passkey) {
        return passkey != null && SANDBOX_PASSKEY.equalsIgnoreCase(passkey.trim());
    }

    static String stkPassword(String shortcode, String passkey, String timestamp) {
        return Base64.getEncoder().encodeToString(
                (shortcode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code CustomerBuyGoodsOnline} for tills, {@code CustomerPayBillOnline} for paybills. */
    static String stkTransactionType(Map<String, String> creds) {
        return isPaybill(creds) ? "CustomerPayBillOnline" : "CustomerBuyGoodsOnline";
    }

    /**
     * Express TransactionType rules (Safaricom docs + friend-style multi-tenant):
     * <ul>
     *   <li>Explicit {@code transactionType} / {@code transfer_type} in creds wins.</li>
     *   <li>If PartyB differs from BusinessShortCode → Buy Goods till under HO →
     *       {@code CustomerBuyGoodsOnline}.</li>
     *   <li>Else PartyB == BusinessShortCode → type of the Go Live shortcode
     *       ({@code shortcodeType} paybill|till).</li>
     * </ul>
     * Tenant destination in AccountReference must never flip this.
     */
    static String resolveStkTransactionType(Map<String, String> creds, String businessShortCode, String partyB) {
        String explicit = firstNonBlank(
                creds.get("transactionType"),
                creds.get("TransactionType"),
                creds.get("transfer_type"),
                creds.get("transferType"));
        if (explicit != null) {
            String t = explicit.trim();
            if ("CustomerBuyGoodsOnline".equalsIgnoreCase(t) || "buygoods".equalsIgnoreCase(t) || "till".equalsIgnoreCase(t)) {
                return "CustomerBuyGoodsOnline";
            }
            if ("CustomerPayBillOnline".equalsIgnoreCase(t) || "paybill".equalsIgnoreCase(t)) {
                return "CustomerPayBillOnline";
            }
        }
        if (partyB != null && businessShortCode != null && !partyB.equals(businessShortCode)) {
            return "CustomerBuyGoodsOnline";
        }
        return stkTransactionType(creds);
    }

    /**
     * Express AccountReference is alpha-numeric, max 12, and is rendered in the USSD
     * prompt. Spaces, dashes and '#' from a tenant account number are stripped rather
     * than passed through, since Daraja answers those with 400.002.02.
     */
    static String accountReference(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "");
        if (cleaned.isBlank()) {
            return "Kiosk";
        }
        return truncate(cleaned, 12);
    }

    static Map<String, Object> buildStkRequestBody(
            String shortcode,
            String password,
            String timestamp,
            String transactionType,
            BigDecimal amount,
            String phone,
            String partyB,
            String callbackUrl,
            String accountReference,
            String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", shortcode);
        body.put("Password", password);
        body.put("Timestamp", timestamp);
        body.put("TransactionType", transactionType);
        body.put("Amount", amount.setScale(0, RoundingMode.HALF_UP).intValueExact());
        body.put("PartyA", phone);
        body.put("PartyB", partyB);
        body.put("PhoneNumber", phone);
        body.put("CallBackURL", callbackUrl);
        body.put("AccountReference", accountReference(accountReference));
        body.put("TransactionDesc", truncate(firstNonBlank(description, "Payment"), 13));
        return body;
    }

    /**
     * Query replies with ResultCode 0 (paid) or a terminal code (1032 cancelled, 1037 timeout).
     * While the prompt is still on the handset Daraja answers with an {@code errorCode} instead,
     * so anything carrying one stays pending rather than failing the push.
     */
    static StkStatusResponse parseStkQueryResponse(String raw, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(raw != null && !raw.isBlank() ? raw : "{}");
        } catch (Exception e) {
            return new StkStatusResponse("PENDING", "Unreadable query response", false, false, null, raw);
        }

        String errorCode = text(root, "errorCode");
        if (errorCode != null) {
            return new StkStatusResponse(errorCode,
                    firstNonBlank(text(root, "errorMessage"), "Still processing"),
                    false, false, null, raw);
        }

        String resultCode = firstNonBlank(text(root, "ResultCode"), text(root, "ResponseCode"));
        String resultDesc = firstNonBlank(text(root, "ResultDesc"), text(root, "ResponseDescription"));

        if ("0".equals(resultCode)) {
            // The receipt only arrives on the callback, so leave it null here.
            return new StkStatusResponse(resultCode, resultDesc, true, false, null, raw);
        }
        if (resultCode == null || "1".equals(resultCode) || "4999".equals(resultCode)) {
            return new StkStatusResponse(
                    resultCode != null ? resultCode : "PENDING",
                    resultDesc != null ? resultDesc : "Pending",
                    false, false, null, raw);
        }
        return new StkStatusResponse(resultCode, resultDesc, false, true, null, raw);
    }

    /** Daraja error codes are terse — add the fix so cashiers see something actionable. */
    static String stkErrorMessage(String errorCode, String errorMessage) {
        String fallback = firstNonBlank(errorMessage, "STK request declined");
        if (errorCode == null) {
            return fallback;
        }
        String hint = switch (errorCode) {
            case "404.001.03" -> "Daraja access token was rejected — check the consumer key and secret.";
            case "400.002.02" -> "Daraja rejected a request field — check the shortcode and destination.";
            case "500.001.1001" -> "Daraja rejected the shortcode or passkey, or a prompt is already "
                    + "open on that phone. Wait a minute and retry.";
            case "500.003.02", "500.003.03" -> "Daraja is rate limiting or busy — retry shortly.";
            default -> null;
        };
        return hint == null ? fallback : fallback + " — " + hint;
    }

    /** Paybill / till / store numbers are 5-7 digits. */
    private static boolean isValidShortcode(String shortcode) {
        return shortcode != null && shortcode.matches("\\d{5,7}");
    }

    private static boolean isValidMsisdn(String phone) {
        return phone != null && phone.matches("254[17]\\d{8}");
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

    /**
     * {@code Body.stkCallback.ResultCode} from a raw STK callback, or {@code null} when the
     * payload is not an STK callback. {@link WebhookResult} carries the ResultDesc as its
     * failure message but not the code, and the code is what distinguishes a cancellation
     * (1032) from a timeout (1037) or insufficient funds (1).
     */
    public String stkCallbackResultCode(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return text(objectMapper.readTree(rawBody).path("Body").path("stkCallback"), "ResultCode");
        } catch (Exception e) {
            return null;
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
