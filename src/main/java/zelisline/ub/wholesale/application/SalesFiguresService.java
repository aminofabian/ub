package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.wholesale.api.dto.SalesFiguresResponse;
import zelisline.ub.wholesale.domain.SalesInvoice;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderLine;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.repository.SalesInvoiceRepository;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

/**
 * A supplier's own trade figures (P4, "supplier analytics"): read from its sales orders and
 * invoices, for its own shop only. Nothing is stored and nothing here reaches another shop.
 */
@Service
@RequiredArgsConstructor
public class SalesFiguresService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final int TOP = 8;
    /** Answer times are shown from this many answered orders (SUPPLIER_ENGAGEMENT_DESIGN.md §4.2). */
    static final int MIN_ANSWERED_FOR_MEDIAN = 5;

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final zelisline.ub.tenancy.application.FeatureFlagService featureFlagService;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    @Transactional(readOnly = true)
    public SalesFiguresResponse figures(String businessId, int rawDays) {
        int days = Math.max(1, Math.min(rawDays, 366));
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<SalesOrder> orders = salesOrderRepository
                .findTop2000ByBusinessIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(businessId, since);

        Map<String, SalesOrder> byId = new HashMap<>();
        int waiting = 0;
        String currency = null;
        for (SalesOrder order : orders) {
            byId.put(order.getId(), order);
            currency = currency == null ? order.getCurrency() : currency;
            if (SalesOrderStatuses.PLACED.equals(order.getStatus())) {
                waiting++;
            }
        }

        BigDecimal asked = BigDecimal.ZERO;
        BigDecimal accepted = BigDecimal.ZERO;
        BigDecimal askedOfAnswered = BigDecimal.ZERO;
        Map<String, BigDecimal[]> products = new HashMap<>();
        Map<String, BigDecimal[]> buyers = new HashMap<>();
        List<SalesOrderLine> lines = byId.isEmpty()
                ? List.of()
                : salesOrderLineRepository.findByBusinessIdAndOrderIdIn(businessId, byId.keySet());
        for (SalesOrderLine line : lines) {
            SalesOrder order = byId.get(line.getOrderId());
            if (order == null || SalesOrderStatuses.CANCELLED.equals(order.getStatus())) {
                continue;
            }
            BigDecimal askedValue = line.getUnitPrice().multiply(line.getQuantity());
            asked = asked.add(askedValue);
            if (line.getAcceptedQty() == null) {
                continue;
            }
            askedOfAnswered = askedOfAnswered.add(askedValue);
            BigDecimal acceptedValue = line.getUnitPrice().multiply(line.getAcceptedQty());
            accepted = accepted.add(acceptedValue);
            add(products, line.getItemName(), acceptedValue, line.getAcceptedQty());
            String buyer = order.getBuyerBusinessName() != null ? order.getBuyerBusinessName() : order.getBuyerName();
            add(buyers, buyer, acceptedValue, BigDecimal.ZERO);
        }

        LocalDate sinceDay = LocalDate.now(NAIROBI).minusDays(days);
        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal owed = BigDecimal.ZERO;
        for (SalesInvoice invoice : salesInvoiceRepository
                .findTop2000ByBusinessIdAndIssuedOnGreaterThanEqual(businessId, sinceDay)) {
            invoiced = invoiced.add(invoice.getTotal());
            owed = owed.add(invoice.owed().max(BigDecimal.ZERO));
        }

        Integer answeredSample = null;
        BigDecimal medianHours = null;
        if (!killSwitch && featureFlagService.isEnabled(businessId, zelisline.ub.tenancy.application.FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)) {
            List<Double> hours = new ArrayList<>();
            for (SalesOrder answered : salesOrderRepository
                    .findByBusinessIdAndAnsweredAtGreaterThanEqual(businessId, since)) {
                hours.add(WorkingHours.between(answered.getCreatedAt(), answered.getAnsweredAt()));
            }
            answeredSample = hours.size();
            medianHours = hours.size() >= MIN_ANSWERED_FOR_MEDIAN ? WorkingHours.median(hours) : null;
        }

        BigDecimal fillRate = askedOfAnswered.signum() == 0
                ? null
                : accepted.multiply(BigDecimal.valueOf(100)).divide(askedOfAnswered, 1, RoundingMode.HALF_UP);
        return new SalesFiguresResponse(
                days,
                currency,
                orders.size(),
                waiting,
                money(asked),
                money(accepted),
                fillRate,
                money(invoiced),
                money(owed),
                top(buyers),
                top(products),
                answeredSample,
                medianHours);
    }

    private static void add(Map<String, BigDecimal[]> into, String name, BigDecimal value, BigDecimal quantity) {
        BigDecimal[] sums = into.computeIfAbsent(name == null ? "Unknown" : name,
                k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        sums[0] = sums[0].add(value);
        sums[1] = sums[1].add(quantity);
    }

    private static List<SalesFiguresResponse.Row> top(Map<String, BigDecimal[]> sums) {
        List<SalesFiguresResponse.Row> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : sums.entrySet()) {
            rows.add(new SalesFiguresResponse.Row(e.getKey(), money(e.getValue()[0]), e.getValue()[1]));
        }
        rows.sort((a, b) -> b.value().compareTo(a.value()));
        return rows.size() > TOP ? List.copyOf(rows.subList(0, TOP)) : rows;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
