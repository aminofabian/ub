package zelisline.ub.integrations.webhook.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import zelisline.ub.integrations.webhook.WebhookEventTypes;
import zelisline.ub.integrations.webhook.application.WebhookDeliveryTxnService;
import zelisline.ub.integrations.webhook.application.WebhookEnqueueService;
import zelisline.ub.integrations.webhook.application.WebhookSigner;
import zelisline.ub.integrations.webhook.domain.WebhookDelivery;
import zelisline.ub.integrations.webhook.domain.WebhookSubscription;
import zelisline.ub.integrations.webhook.repository.WebhookDeliveryRepository;
import zelisline.ub.integrations.webhook.repository.WebhookSubscriptionRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/** Phase 8 Slice 2 — signed outbound POST + dead-letter / subscription pause behaviour. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class WebhookOutboundIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbd2";
    private static final Pattern SIG_PATTERN = Pattern.compile("t=(\\d+),v1=([a-f0-9]+)");

    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private BranchRepository branchRepository;
    @Autowired
    private WebhookSubscriptionRepository webhookSubscriptionRepository;
    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Autowired
    private WebhookEnqueueService webhookEnqueueService;
    @Autowired
    private WebhookDeliveryTxnService webhookDeliveryTxnService;
    @Autowired
    private WebhookSigner webhookSigner;
    @Autowired
    private ObjectMapper objectMapper;

    private HttpServer httpServer;
    private String subscriberBaseUrl;
    private final AtomicReference<String> lastSignatureHeader = new AtomicReference<>();
    private final AtomicReference<String> lastBodyUtf8 = new AtomicReference<>();
    private final AtomicReference<Integer> forcedStatus = new AtomicReference<>();

    @BeforeEach
    void baseSeedAndServer() throws Exception {
        ReflectionTestUtils.setField(webhookDeliveryTxnService, "maxAttempts", 12);
        ReflectionTestUtils.setField(webhookDeliveryTxnService, "subscriptionDisableThreshold", 10);

        webhookDeliveryRepository.deleteAll();
        webhookSubscriptionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Webhook Co");
        b.setSlug("webhook-co");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        forcedStatus.set(null);
        lastSignatureHeader.set(null);
        lastBodyUtf8.set(null);

        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            lastSignatureHeader.set(firstHeader(exchange.getRequestHeaders().get(WebhookSigner.HEADER_SIGNATURE)));
            byte[] buf = exchange.getRequestBody().readAllBytes();
            lastBodyUtf8.set(new String(buf, StandardCharsets.UTF_8));

            int status = forcedStatus.get() != null ? forcedStatus.get() : 200;
            byte[] responseBody = new byte[0];
            exchange.sendResponseHeaders(status, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        httpServer.setExecutor(null);
        httpServer.start();
        int port = httpServer.getAddress().getPort();
        subscriberBaseUrl = "http://127.0.0.1:" + port + "/hook";
    }

    @AfterEach
    void shutdown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void delivery_postsJsonEnvelope_withValidHmacSignature() throws Exception {
        String secret = "whsec_integration_test_secret";
        seedSubscription(secret, List.of(WebhookEventTypes.SALE_COMPLETED));

        Map<String, Object> salePayload = new LinkedHashMap<>();
        salePayload.put("saleId", "sale-it-1");
        salePayload.put("grandTotal", "42.00");
        webhookEnqueueService.enqueue(TENANT, WebhookEventTypes.SALE_COMPLETED, salePayload, "sale.completed:sale-it-1");

        WebhookDelivery delivery = webhookDeliveryRepository.findAll().getFirst();
        webhookDeliveryTxnService.attemptDelivery(delivery.getId());

        WebhookDelivery updated = webhookDeliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDelivery.STATUS_SENT);

        String body = lastBodyUtf8.get();
        assertThat(body).isNotNull();
        JsonNode root = objectMapper.readTree(body);
        assertThat(root.path("event").asText()).isEqualTo(WebhookEventTypes.SALE_COMPLETED);
        assertThat(root.path("data").path("saleId").asText()).isEqualTo("sale-it-1");

        String sigHeader = lastSignatureHeader.get();
        assertThat(sigHeader).isNotNull();
        Matcher m = SIG_PATTERN.matcher(sigHeader);
        assertThat(m.matches()).isTrue();
        long t = Long.parseLong(m.group(1));
        String v1 = m.group(2);
        String expected = webhookSigner.sign(t, body, secret);
        assertThat(expected).isEqualToIgnoringCase(v1);
    }

    @Test
    void repeatedFailures_markDead_andPauseSubscription() {
        ReflectionTestUtils.setField(webhookDeliveryTxnService, "maxAttempts", 3);
        ReflectionTestUtils.setField(webhookDeliveryTxnService, "subscriptionDisableThreshold", 1);

        forcedStatus.set(401);
        String secret = "whsec_deadletter";
        WebhookSubscription sub = seedSubscription(secret, List.of(WebhookEventTypes.STOCK_LOW_STOCK));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itemId", "item-x");
        payload.put("currentStock", BigDecimal.ONE.toPlainString());
        webhookEnqueueService.enqueue(TENANT, WebhookEventTypes.STOCK_LOW_STOCK, payload, null);

        String deliveryId = webhookDeliveryRepository.findAll().getFirst().getId();
        for (int i = 0; i < 3; i++) {
            webhookDeliveryTxnService.attemptDelivery(deliveryId);
        }

        WebhookDelivery delivery = webhookDeliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDelivery.STATUS_DEAD);

        WebhookSubscription refreshed = webhookSubscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(refreshed.isActive()).isFalse();
    }

    private WebhookSubscription seedSubscription(String signingSecret, List<String> events) {
        WebhookSubscription s = new WebhookSubscription();
        s.setBusinessId(TENANT);
        s.setLabel("it-hook");
        s.setTargetUrl(subscriberBaseUrl);
        s.setSigningSecret(signingSecret);
        s.setEvents(new ArrayList<>(events));
        s.setActive(true);
        return webhookSubscriptionRepository.save(s);
    }

    private static String firstHeader(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }
}
