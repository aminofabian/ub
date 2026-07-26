package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.GlobalSupplierHubResponse;
import zelisline.ub.marketplace.api.dto.GlobalSupplierHubShopCard;
import zelisline.ub.marketplace.api.dto.SupplierPortalDeliveryRow;
import zelisline.ub.marketplace.api.dto.SupplierPortalPaymentRow;

@Service
@RequiredArgsConstructor
public class SupplierPortalReportsService {

    public static final String TYPE_PAYMENTS = "payments";
    public static final String TYPE_OUTSTANDING = "outstanding";
    public static final String TYPE_DELIVERIES = "deliveries";

    private final SupplierPortalPaymentsService paymentsService;
    private final SupplierPortalDeliveriesService deliveriesService;
    private final GlobalSupplierHubService globalSupplierHubService;

    @Transactional(readOnly = true)
    public byte[] exportCsv(String marketplaceSupplierId, String type) {
        String kind = type == null ? "" : type.trim().toLowerCase();
        return switch (kind) {
            case TYPE_PAYMENTS -> paymentsCsv(marketplaceSupplierId);
            case TYPE_OUTSTANDING -> outstandingCsv(marketplaceSupplierId);
            case TYPE_DELIVERIES -> deliveriesCsv(marketplaceSupplierId);
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown report type (payments|outstanding|deliveries)");
        };
    }

    private byte[] paymentsCsv(String marketplaceSupplierId) {
        List<SupplierPortalPaymentRow> rows = paymentsService.listPayments(marketplaceSupplierId, null);
        StringBuilder sb = new StringBuilder();
        sb.append("paidAt,shop,reference,amount,method,shopOpenBalance\n");
        for (SupplierPortalPaymentRow row : rows) {
            sb.append(csv(row.paidAt())).append(',')
                    .append(csv(row.businessName())).append(',')
                    .append(csv(row.reference())).append(',')
                    .append(csv(row.amount())).append(',')
                    .append(csv(row.paymentMethod())).append(',')
                    .append(csv(row.shopOpenBalance())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] outstandingCsv(String marketplaceSupplierId) {
        GlobalSupplierHubResponse hub = globalSupplierHubService.forMarketplaceSupplierId(marketplaceSupplierId);
        StringBuilder sb = new StringBuilder();
        sb.append("shop,localSupplierId,owed,paid,currency\n");
        for (GlobalSupplierHubShopCard shop : hub.shops()) {
            sb.append(csv(shop.shopName())).append(',')
                    .append(csv(shop.localSupplierId())).append(',')
                    .append(csv(shop.owed())).append(',')
                    .append(csv(shop.paid())).append(',')
                    .append(csv(hub.currency())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] deliveriesCsv(String marketplaceSupplierId) {
        List<SupplierPortalDeliveryRow> rows = deliveriesService.listDeliveries(marketplaceSupplierId);
        StringBuilder sb = new StringBuilder();
        sb.append("updatedAt,shop,poNumber,deliveryStatus,qtyOrdered,qtyReceived,expectedDate\n");
        for (SupplierPortalDeliveryRow row : rows) {
            sb.append(csv(row.updatedAt())).append(',')
                    .append(csv(row.businessName())).append(',')
                    .append(csv(row.poNumber())).append(',')
                    .append(csv(row.deliveryStatus())).append(',')
                    .append(csv(row.qtyOrdered())).append(',')
                    .append(csv(row.qtyReceived())).append(',')
                    .append(csv(row.expectedDate())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String raw = value instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(value);
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
            return "\"" + raw.replace("\"", "\"\"") + "\"";
        }
        return raw;
    }
}
