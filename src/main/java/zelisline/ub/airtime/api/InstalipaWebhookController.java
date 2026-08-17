package zelisline.ub.airtime.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.application.AirtimeSaleService;
import zelisline.ub.airtime.infrastructure.InstalipaAirtimeGateway;

/**
 * Instalipa delivery notifications.
 *
 * <p>Instalipa retries for several minutes unless it gets a 200, so this always
 * answers 200 with {@code {"status":"ok"}} once the body has been read — even for
 * a transaction we do not recognise. Retrying an unmatched callback would never
 * start matching, and a 500 would make Instalipa hammer us. Anything that fails
 * to apply is picked up by {@code AirtimeOrderReconciler} polling instead.
 */
@RestController
@RequestMapping("/webhooks/instalipa")
@RequiredArgsConstructor
public class InstalipaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(InstalipaWebhookController.class);
    private static final Map<String, String> ACK = Map.of("status", "ok");

    private final AirtimeSaleService saleService;
    private final InstalipaAirtimeGateway gateway;

    @PostMapping("/airtime")
    public ResponseEntity<Map<String, String>> airtime(@RequestBody(required = false) String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            log.warn("Instalipa callback had an empty body");
            return ResponseEntity.ok(ACK);
        }
        try {
            var parsed = gateway.parseCallback(rawBody);
            boolean matched = saleService.applyProviderUpdate(parsed);
            if (!matched) {
                log.warn("Instalipa callback did not match any airtime order: txn={} ref={}",
                        parsed.transactionId(), parsed.reference());
            }
        } catch (Exception e) {
            // Swallow deliberately: the reconciler is the safety net, and a non-200
            // here would only trigger provider retries of a callback we cannot apply.
            log.error("Instalipa callback processing failed", e);
        }
        return ResponseEntity.ok(ACK);
    }
}
