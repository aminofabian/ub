package zelisline.ub.platform.logs;

import org.springframework.stereotype.Component;

/**
 * Maps a request path to a {@link RequestLogCategory}.
 *
 * <p>Webhooks and the public storefront share the same path space (e.g.
 * {@code /webhooks/instalipa/airtime}), so classification is purely
 * path-based and stays stable as new controllers are added. The first match
 * wins; more specific families (airtime, KPLC) are checked before broader
 * payment / cashier buckets.
 */
@Component
public class RequestLogClassifier {

    public RequestLogCategory classify(String path) {
        if (path == null) {
            return RequestLogCategory.OTHER;
        }
        String p = path.toLowerCase();
        if (p.contains("/airtime")) {
            return RequestLogCategory.AIRTIME;
        }
        if (p.contains("/kplc")) {
            return RequestLogCategory.KPLC;
        }
        if (p.contains("/mpesa") || p.contains("/stk") || p.contains("/kopokopo")
                || p.contains("/payments") || p.contains("/kiosk-pay")) {
            return RequestLogCategory.MPESA;
        }
        if (p.contains("/sales") || p.contains("/pos-drafts") || p.contains("/pos/")
                || p.contains("/web-orders") || p.contains("/cashier")
                || p.contains("/carts") || p.contains("/orders") || p.contains("/grocery")) {
            return RequestLogCategory.CASHIER;
        }
        return RequestLogCategory.OTHER;
    }
}
