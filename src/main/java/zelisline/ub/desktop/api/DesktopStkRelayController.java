package zelisline.ub.desktop.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.desktop.application.CloudSyncSession;

/**
 * Desktop only: M-Pesa STK prompts for a till connected to an online shop.
 *
 * <p>Safaricom / KopoKopo deliver the payment result to a public callback URL,
 * which a till on {@code 127.0.0.1} cannot receive, and the gateway
 * credentials live in the online shop. So the till relays the cashier's three
 * calls — list rails, send prompt, poll status — to the online shop using the
 * stored cloud session. The online shop sends the prompt, receives the
 * callback and answers the poll; the till then records the sale locally with
 * the M-Pesa receipt as the payment reference, and the sale syncs up as usual.
 *
 * <p>Needs internet. Offline, the cashier uses the manual M-Pesa code tender.
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/payments/stk")
@RequiredArgsConstructor
public class DesktopStkRelayController {

    private static final Logger log = LoggerFactory.getLogger(DesktopStkRelayController.class);
    private static final String CLOUD_BASE = "/api/v1/payments/mpesa/stk";

    private final CloudSyncSession cloudSyncSession;

    @GetMapping("/rails")
    @PreAuthorize("hasPermission(null, 'payments.stk.initiate')")
    public ResponseEntity<String> rails() {
        Optional<CloudSyncSession.Session> session = cloudSyncSession.load();
        if (session.isEmpty()) {
            return json(HttpStatus.OK, "[]");
        }
        try {
            ResponseEntity<String> res = call(session.get(), HttpMethod.GET, CLOUD_BASE + "/rails", null, null);
            // Not permitted / not configured online → "no rails", not an error toast per sale.
            return res.getStatusCode().is2xxSuccessful() ? res : json(HttpStatus.OK, "[]");
        } catch (Exception e) {
            log.debug("[DesktopStk] rails unavailable (offline?): {}", e.getMessage());
            return json(HttpStatus.OK, "[]");
        }
    }

    @PostMapping("/push")
    @PreAuthorize("hasPermission(null, 'payments.stk.initiate')")
    public ResponseEntity<String> push(
            @RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        CloudSyncSession.Session session = requireSession();
        try {
            return call(session, HttpMethod.POST, CLOUD_BASE + "/push", body, idempotencyKey);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[DesktopStk] push could not reach the online shop: {}", e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "No internet — the M-Pesa prompt was not sent. Take cash, or enter the M-Pesa code by hand.");
        }
    }

    @GetMapping("/push/status")
    @PreAuthorize("hasPermission(null, 'payments.stk.initiate')")
    public ResponseEntity<String> status(@RequestParam("checkoutRequestId") String checkoutRequestId) {
        CloudSyncSession.Session session = requireSession();
        String uri = CLOUD_BASE + "/push/status?checkoutRequestId="
            + UriUtils.encodeQueryParam(checkoutRequestId, StandardCharsets.UTF_8);
        try {
            return call(session, HttpMethod.GET, uri, null, null);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Lost internet while waiting for M-Pesa. Check the customer's message before retrying.");
        }
    }

    private CloudSyncSession.Session requireSession() {
        return cloudSyncSession.load().orElseThrow(() -> new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "This till is not connected to an online shop, so it cannot send M-Pesa prompts."));
    }

    /** One call to the online shop; on 401 refresh the stored session and retry once. */
    private ResponseEntity<String> call(
            CloudSyncSession.Session session,
            HttpMethod method,
            String uri,
            JsonNode body,
            String idempotencyKey) {
        RestClient client = client(session.origin());
        ResponseEntity<String> res = exchange(client, session, method, uri, body, idempotencyKey);
        if (res.getStatusCode().value() != 401) {
            return res;
        }
        CloudSyncSession.Session fresh = cloudSyncSession.refresh(client, session)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The online shop sign-in has expired. Reconnect under Settings, then try M-Pesa again."));
        return exchange(client, fresh, method, uri, body, idempotencyKey);
    }

    private static ResponseEntity<String> exchange(
            RestClient client,
            CloudSyncSession.Session session,
            HttpMethod method,
            String uri,
            JsonNode body,
            String idempotencyKey) {
        RestClient.RequestBodySpec spec = client
            .method(method)
            .uri(uri)
            .header("Authorization", "Bearer " + session.accessToken())
            .header("X-Tenant-Id", session.cloudBusinessId());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            spec = spec.header("Idempotency-Key", idempotencyKey.trim());
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON);
            spec.body(body);
        }
        return spec.exchange((req, resp) -> {
            String text = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
            return json(HttpStatus.valueOf(resp.getStatusCode().value()), text);
        });
    }

    private static RestClient client(String origin) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(8).toMillis());
        // The online shop may wait ~45-60s on the gateway before answering a push.
        factory.setReadTimeout((int) Duration.ofSeconds(75).toMillis());
        return RestClient.builder().baseUrl(origin).requestFactory(factory).build();
    }

    private static ResponseEntity<String> json(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
