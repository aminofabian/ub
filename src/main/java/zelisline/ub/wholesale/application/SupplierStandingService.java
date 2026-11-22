package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.application.BusinessOnboardingSettingsService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.trade.domain.TradeOrder;
import zelisline.ub.trade.domain.TradeOrderLine;
import zelisline.ub.trade.repository.TradeOrderLineRepository;
import zelisline.ub.trade.repository.TradeOrderRepository;
import zelisline.ub.wholesale.api.dto.BuyerLinesResponse;
import zelisline.ub.wholesale.api.dto.SupplierStandingResponse;
import zelisline.ub.wholesale.api.dto.SupplierStandingResponse.Measure;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderLine;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

/**
 * A supplier's standing over a window of days, read from its own answered orders (SUPPLIER_ENGAGEMENT_DESIGN.md
 * §4.2). Nothing is stored and nothing is added up into one score. Orders from the shop itself, and from the
 * owner's own phone, are left out. Below ten answered orders from three buyers the state is {@code new} and no
 * number is returned. Public holidays and the supplier's own closed days do not count on the working clock.
 */
@Service
@RequiredArgsConstructor
public class SupplierStandingService {

    static final int DEFAULT_WINDOW_DAYS = 90;
    static final int MAX_WINDOW_DAYS = 366;
    static final int MIN_ANSWERED = 10;
    static final int MIN_BUYERS = 3;
    static final int DEFAULT_LEAD_WORKING_DAYS = 2;
    static final int MIN_LEAD_WORKING_DAYS = 1;
    static final int MAX_LEAD_WORKING_DAYS = 3;
    /** Monday to Saturday, 08:00 to 18:00: ten working hours in a working day. */
    static final double HOURS_PER_WORKING_DAY = 10.0;
    /** Buyer trust lines (D6, D8): shown only when true, from verified buyers' orders, with the D3 sample. */
    static final double BUYER_ANSWER_MAX_HOURS = 4.0;
    static final int BUYER_SUPPLY_MIN_PERCENT = 90;
    static final int BUYER_ON_TIME_MIN_PERCENT = 80;

    private final FeatureFlagService featureFlagService;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeOrderLineRepository tradeOrderLineRepository;
    private final BusinessRepository businessRepository;
    private final BusinessOnboardingSettingsService onboardingSettingsService;
    private final ObjectMapper objectMapper;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    @Transactional(readOnly = true)
    public SupplierStandingResponse standing(String businessId, int rawDays) {
        if (!on(businessId)) {
            return SupplierStandingResponse.off();
        }
        int days = rawDays <= 0 ? DEFAULT_WINDOW_DAYS : Math.min(rawDays, MAX_WINDOW_DAYS);
        Instant now = Instant.now();
        Instant since = now.minus(days, ChronoUnit.DAYS);
        Optional<MarketplaceSupplier> passport = marketplaceSupplierRepository.findFirstByBusinessId(businessId);
        int leadDays = passport.map(MarketplaceSupplier::getDispatchLeadWorkingDays).orElse(DEFAULT_LEAD_WORKING_DAYS);
        Set<LocalDate> closed = passport.map(p -> parseClosedDays(p.getClosedDatesJson())).orElse(Set.of());
        List<String> closedList = closed.stream().map(LocalDate::toString).sorted().toList();
        String ownerPhoneKey = ownerPhoneKey(businessId);

        List<SalesOrder> answered = salesOrderRepository
                .findByBusinessIdAndAnsweredAtGreaterThanEqual(businessId, since).stream()
                .filter(o -> !businessId.equals(o.getBuyerBusinessId()))
                .filter(o -> !o.isStandingExcluded())
                .filter(o -> ownerPhoneKey == null || !ownerPhoneKey.equals(o.getBuyerPhoneKey()))
                .toList();
        Set<String> buyers = answered.stream()
                .map(SalesOrder::getBuyerPhoneKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (answered.size() < MIN_ANSWERED || buyers.size() < MIN_BUYERS) {
            return new SupplierStandingResponse(
                    true, "new", answered.size(), buyers.size(), List.of(), leadDays, closedList);
        }

        List<Measure> measures = new ArrayList<>();
        measures.add(answerTimes(answered, closed));
        measures.add(suppliesWhatItAccepts(businessId, answered));
        Measure onTime = dispatchesOnTime(answered, leadDays, now, closed);
        if (onTime != null) {
            measures.add(onTime);
        }
        Measure cancels = cancelsAfterAccepting(answered);
        if (cancels != null) {
            measures.add(cancels);
        }
        Measure arrives = whatArrives(businessId, answered);
        if (arrives != null) {
            measures.add(arrives);
        }
        return new SupplierStandingResponse(
                true, "measured", answered.size(), buyers.size(), measures, leadDays, closedList);
    }

    /** The supplier sets how many working days it takes to dispatch an accepted order. */
    @Transactional
    public void setLeadWorkingDays(String businessId, int workingDays) {
        requireOn(businessId);
        if (workingDays < MIN_LEAD_WORKING_DAYS || workingDays > MAX_LEAD_WORKING_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose one to three working days");
        }
        MarketplaceSupplier passport = requirePassport(businessId);
        passport.setDispatchLeadWorkingDays(workingDays);
        marketplaceSupplierRepository.save(passport);
    }

    /** The supplier replaces its list of closed days. Each is an ISO date; a closed day stops the clock. */
    @Transactional
    public void setClosedDays(String businessId, List<String> dates) {
        requireOn(businessId);
        Set<LocalDate> parsed = new java.util.TreeSet<>();
        for (String raw : dates == null ? List.<String>of() : dates) {
            try {
                parsed.add(LocalDate.parse(raw.trim()));
            } catch (DateTimeParseException | NullPointerException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Closed days must be dates like 2026-12-31");
            }
        }
        MarketplaceSupplier passport = requirePassport(businessId);
        passport.setClosedDatesJson(toJson(parsed.stream().map(LocalDate::toString).toList()));
        marketplaceSupplierRepository.save(passport);
    }

    /**
     * What buyers may see about this supplier. Only lines that are true, computed from verified buyers' orders
     * (a buyer business on Kiosk, not a typed phone number: D10), not excluded by platform support, with the
     * same sample as the standing (D3). Off unless the buyer-lines flag is on.
     */
    @Transactional(readOnly = true)
    public BuyerLinesResponse buyerLines(String businessId) {
        if (killSwitch || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_BUYER_LINES)) {
            return BuyerLinesResponse.off();
        }
        Instant now = Instant.now();
        Instant since = now.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS);
        Optional<MarketplaceSupplier> passport = marketplaceSupplierRepository.findFirstByBusinessId(businessId);
        int leadDays = passport.map(MarketplaceSupplier::getDispatchLeadWorkingDays).orElse(DEFAULT_LEAD_WORKING_DAYS);
        Set<LocalDate> closed = passport.map(p -> parseClosedDays(p.getClosedDatesJson())).orElse(Set.of());

        List<SalesOrder> verified = salesOrderRepository
                .findByBusinessIdAndAnsweredAtGreaterThanEqual(businessId, since).stream()
                .filter(o -> o.getBuyerBusinessId() != null && !o.getBuyerBusinessId().isBlank())
                .filter(o -> !businessId.equals(o.getBuyerBusinessId()))
                .filter(o -> !o.isStandingExcluded())
                .toList();
        int buyers = distinctBuyers(verified);
        if (verified.size() < MIN_ANSWERED || buyers < MIN_BUYERS) {
            return new BuyerLinesResponse(true, verified.size(), buyers, List.of());
        }

        List<BuyerLinesResponse.Line> lines = new ArrayList<>();
        Measure answer = answerTimes(verified, closed);
        if (answer.value() != null && answer.value().compareTo(BigDecimal.valueOf(BUYER_ANSWER_MAX_HOURS)) <= 0) {
            lines.add(new BuyerLinesResponse.Line("answers_within_four_hours",
                    "Usually answers within 4 working hours", verified.size(), buyers));
        }
        Measure supply = suppliesWhatItAccepts(businessId, verified);
        if (supply.value() != null
                && supply.value().compareTo(BigDecimal.valueOf(BUYER_SUPPLY_MIN_PERCENT)) >= 0) {
            lines.add(new BuyerLinesResponse.Line("supplies_most_of_what_buyers_ask",
                    "Supplies at least 90% of what buyers ask for", verified.size(), buyers));
        }
        // On time counts only orders the buying shop has confirmed receipt for (design §8, D6).
        Set<String> received = receivedOrderIds(businessId, verified);
        List<SalesOrder> confirmed = verified.stream().filter(o -> received.contains(o.getId())).toList();
        int confirmedBuyers = distinctBuyers(confirmed);
        Measure onTime = dispatchesOnTime(confirmed, leadDays, now, closed);
        if (confirmed.size() >= MIN_ANSWERED && onTime != null && onTime.value() != null
                && onTime.value().compareTo(BigDecimal.valueOf(BUYER_ON_TIME_MIN_PERCENT)) >= 0) {
            lines.add(new BuyerLinesResponse.Line("dispatches_on_time",
                    "Dispatches on time, as buyers confirmed", confirmed.size(), confirmedBuyers));
        }
        return new BuyerLinesResponse(true, verified.size(), buyers, List.copyOf(lines));
    }

    private Set<String> receivedOrderIds(String businessId, List<SalesOrder> orders) {
        Set<String> salesOrderIds = orders.stream().map(SalesOrder::getId).collect(Collectors.toSet());
        if (salesOrderIds.isEmpty()) {
            return Set.of();
        }
        List<TradeOrder> trades = tradeOrderRepository.findBySupplierBusinessIdAndSalesOrderIdIn(businessId, salesOrderIds);
        if (trades.isEmpty()) {
            return Set.of();
        }
        Set<String> tradesWithReceipt = tradeOrderLineRepository
                .findByTradeOrderIdIn(trades.stream().map(TradeOrder::getId).toList()).stream()
                .filter(l -> l.getReceivedQty() != null)
                .map(TradeOrderLine::getTradeOrderId)
                .collect(Collectors.toSet());
        return trades.stream()
                .filter(t -> tradesWithReceipt.contains(t.getId()))
                .map(TradeOrder::getSalesOrderId)
                .collect(Collectors.toSet());
    }

    private static int distinctBuyers(List<SalesOrder> orders) {
        return (int) orders.stream().map(SalesOrder::getBuyerBusinessId).distinct().count();
    }

    private void requireOn(String businessId) {
        if (!on(businessId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not available");
        }
    }

    private MarketplaceSupplier requirePassport(String businessId) {
        return marketplaceSupplierRepository.findFirstByBusinessId(businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Claim your page before you change your working days"));
    }

    private boolean on(String businessId) {
        return !killSwitch
                && featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)
                && featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_STANDING);
    }

    /** The stored closed days. Unreadable or missing data means none, never an error. */
    private Set<LocalDate> parseClosedDays(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> raw = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            Set<LocalDate> out = new java.util.HashSet<>();
            for (String value : raw) {
                try {
                    out.add(LocalDate.parse(value));
                } catch (DateTimeParseException e) {
                    // Skip a value that is not a date; the rest still count.
                }
            }
            return out;
        } catch (JsonProcessingException e) {
            return Set.of();
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store closed days", e);
        }
    }

    /** The owner's phone, in the one spelling orders are matched on. Null when the shop has none. */
    private String ownerPhoneKey(String businessId) {
        var business = businessRepository.findByIdAndDeletedAtIsNull(businessId);
        if (business.isEmpty()) {
            return null;
        }
        var onboarding = onboardingSettingsService.readFromSettingsJson(business.get().getSettings());
        if (onboarding == null || onboarding.answers() == null) {
            return null;
        }
        String phone = onboarding.answers().ownerPhone();
        return phone == null || phone.isBlank() ? null : StkPhoneNormalizer.normalize(phone);
    }

    /** Median working hours from placed to answered. */
    private static Measure answerTimes(List<SalesOrder> answered, Set<LocalDate> closed) {
        List<Double> hours = new ArrayList<>();
        for (SalesOrder order : answered) {
            hours.add(WorkingHours.between(order.getCreatedAt(), order.getAnsweredAt(), closed));
        }
        return new Measure("answers_in", "Usually answers in", WorkingHours.median(hours), "hours", answered.size());
    }

    /** Accepted value as a share of the value asked for, over the orders answered in the window. */
    private Measure suppliesWhatItAccepts(String businessId, List<SalesOrder> answered) {
        Map<String, SalesOrder> byId = new HashMap<>();
        answered.forEach(o -> byId.put(o.getId(), o));
        BigDecimal asked = BigDecimal.ZERO;
        BigDecimal accepted = BigDecimal.ZERO;
        if (!byId.isEmpty()) {
            for (SalesOrderLine line : salesOrderLineRepository
                    .findByBusinessIdAndOrderIdIn(businessId, byId.keySet())) {
                asked = asked.add(line.getUnitPrice().multiply(line.getQuantity()));
                if (line.getAcceptedQty() != null) {
                    accepted = accepted.add(line.getUnitPrice().multiply(line.getAcceptedQty()));
                }
            }
        }
        BigDecimal value = asked.signum() == 0
                ? null
                : accepted.multiply(BigDecimal.valueOf(100)).divide(asked, 1, RoundingMode.HALF_UP);
        return new Measure("supplies_what_it_accepts", "Supplied what it accepted", value, "percent", answered.size());
    }

    /**
     * Of accepted orders that are dispatched or overdue, the share dispatched within the lead time. An accepted
     * order still inside its lead time is left out until it is due, so it does not count against the supplier.
     */
    private static Measure dispatchesOnTime(List<SalesOrder> answered, int leadDays, Instant now, Set<LocalDate> closed) {
        double limit = leadDays * HOURS_PER_WORKING_DAY;
        int due = 0;
        int onTime = 0;
        for (SalesOrder order : answered) {
            if (SalesOrderStatuses.REJECTED.equals(order.getStatus())) {
                continue;
            }
            if (order.getDispatchedAt() != null) {
                due++;
                if (WorkingHours.between(order.getAnsweredAt(), order.getDispatchedAt(), closed) <= limit) {
                    onTime++;
                }
            } else if (isStillAccepted(order)
                    && WorkingHours.between(order.getAnsweredAt(), now, closed) > limit) {
                due++;
            }
        }
        if (due == 0) {
            return null;
        }
        BigDecimal value = BigDecimal.valueOf(onTime).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(due), 1, RoundingMode.HALF_UP);
        return new Measure("dispatches_on_time", "Dispatched on time", value, "percent", due);
    }

    /** Orders cancelled after they were accepted, as a share of the orders accepted. */
    private static Measure cancelsAfterAccepting(List<SalesOrder> answered) {
        int accepted = 0;
        int cancelled = 0;
        for (SalesOrder order : answered) {
            if (SalesOrderStatuses.REJECTED.equals(order.getStatus())) {
                continue;
            }
            accepted++;
            if (SalesOrderStatuses.CANCELLED.equals(order.getStatus())
                    && (SalesOrderStatuses.ACCEPTED.equals(order.getCancelledFrom())
                    || SalesOrderStatuses.PART_ACCEPTED.equals(order.getCancelledFrom()))) {
                cancelled++;
            }
        }
        if (accepted == 0) {
            return null;
        }
        BigDecimal value = BigDecimal.valueOf(cancelled).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(accepted), 1, RoundingMode.HALF_UP);
        return new Measure("cancels_after_accepting", "Cancelled after accepting", value, "percent", accepted);
    }

    /**
     * Of the orders the buyer has said what arrived for, the share that arrived as sent: no line short. Read
     * from the trade order linked to each sales order, so it needs the buyer's receipt.
     */
    private Measure whatArrives(String businessId, List<SalesOrder> answered) {
        Set<String> salesOrderIds = answered.stream().map(SalesOrder::getId).collect(Collectors.toSet());
        if (salesOrderIds.isEmpty()) {
            return null;
        }
        List<TradeOrder> trades = tradeOrderRepository.findBySupplierBusinessIdAndSalesOrderIdIn(businessId, salesOrderIds);
        if (trades.isEmpty()) {
            return null;
        }
        Map<String, List<TradeOrderLine>> linesByTrade = tradeOrderLineRepository
                .findByTradeOrderIdIn(trades.stream().map(TradeOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(TradeOrderLine::getTradeOrderId));
        int receipts = 0;
        int short_ = 0;
        for (TradeOrder trade : trades) {
            List<TradeOrderLine> lines = linesByTrade.getOrDefault(trade.getId(), List.of());
            if (lines.stream().noneMatch(l -> l.getReceivedQty() != null)) {
                continue;
            }
            receipts++;
            if (lines.stream().anyMatch(l -> l.getReceivedQty() != null
                    && l.getReceivedQty().compareTo(l.getQuantity()) < 0)) {
                short_++;
            }
        }
        if (receipts == 0) {
            return null;
        }
        BigDecimal value = BigDecimal.valueOf(receipts - short_).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(receipts), 1, RoundingMode.HALF_UP);
        return new Measure("what_arrives", "What arrived was what was sent", value, "percent", receipts);
    }

    private static boolean isStillAccepted(SalesOrder order) {
        return SalesOrderStatuses.ACCEPTED.equals(order.getStatus())
                || SalesOrderStatuses.PART_ACCEPTED.equals(order.getStatus());
    }
}
