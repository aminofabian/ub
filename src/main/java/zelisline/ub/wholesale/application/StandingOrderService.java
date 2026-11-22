package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.storefront.api.dto.PublicUpsertCartLineRequest;
import zelisline.ub.storefront.application.PublicWebCartService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.wholesale.api.dto.PlaceSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.SalesOrderPlacedResponse;
import zelisline.ub.wholesale.api.dto.StandingOrderResponse;
import zelisline.ub.wholesale.domain.StandingOrder;
import zelisline.ub.wholesale.domain.StandingOrderLine;
import zelisline.ub.wholesale.repository.StandingOrderLineRepository;
import zelisline.ub.wholesale.repository.StandingOrderRepository;

/**
 * Standing orders (P4): a buying shop asks a supplier's shop for the same goods every so many
 * days. Each run places an ordinary trade order through the shop's own cart, so the shop's stock,
 * prices and minimum order apply on the day, and the supplier still accepts or declines it.
 *
 * <p>Only a signed-in marketplace buyer can start one, for a shop they are staff of; the caller
 * checks that. A run that cannot be placed says why on the standing order and waits for the next.
 */
@Service
public class StandingOrderService {

    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 90;
    static final int MAX_PER_BUYER_PER_SUPPLIER = 5;
    private static final Logger log = LoggerFactory.getLogger(StandingOrderService.class);

    /** One product and how much of it, by the shop's own product id. */
    public record Wanted(String itemId, BigDecimal quantity) {
    }

    private final StandingOrderRepository standingOrderRepository;
    private final StandingOrderLineRepository lineRepository;
    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final PublicWebCartService cartService;
    private final SalesOrderService salesOrderService;
    private final TransactionTemplate ownTransaction;

    public StandingOrderService(
            StandingOrderRepository standingOrderRepository,
            StandingOrderLineRepository lineRepository,
            BusinessRepository businessRepository,
            ItemRepository itemRepository,
            PublicWebCartService cartService,
            SalesOrderService salesOrderService,
            PlatformTransactionManager transactionManager
    ) {
        this.standingOrderRepository = standingOrderRepository;
        this.lineRepository = lineRepository;
        this.businessRepository = businessRepository;
        this.itemRepository = itemRepository;
        this.cartService = cartService;
        this.salesOrderService = salesOrderService;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Starts a standing order. The first run is {@code everyDays} from today. */
    public StandingOrderResponse start(
            String supplierBusinessId,
            String buyerBusinessId,
            String buyerBusinessName,
            String buyerName,
            String buyerPhone,
            int everyDays,
            List<Wanted> wanted,
            LocalDate today
    ) {
        if (everyDays < MIN_DAYS || everyDays > MAX_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Repeat every " + MIN_DAYS + " to " + MAX_DAYS + " days");
        }
        if (wanted == null || wanted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A standing order needs at least one product");
        }
        if (standingOrderRepository.countBySupplierBusinessIdAndBuyerBusinessIdAndActiveTrue(
                supplierBusinessId, buyerBusinessId) >= MAX_PER_BUYER_PER_SUPPLIER) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "You already have " + MAX_PER_BUYER_PER_SUPPLIER + " standing orders with this supplier");
        }
        return ownTransaction.execute(status -> {
            StandingOrder order = new StandingOrder();
            order.setSupplierBusinessId(supplierBusinessId);
            order.setBuyerBusinessId(buyerBusinessId);
            order.setBuyerBusinessName(buyerBusinessName);
            order.setBuyerName(buyerName);
            order.setBuyerPhone(buyerPhone);
            order.setEveryDays(everyDays);
            order.setNextRunOn(today.plusDays(everyDays));
            standingOrderRepository.save(order);
            for (Wanted w : wanted) {
                // Only the supplier's own product.
                Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(w.itemId(), supplierBusinessId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "A product on this order is no longer sold by the shop"));
                StandingOrderLine line = new StandingOrderLine();
                line.setStandingOrderId(order.getId());
                line.setItemId(item.getId());
                line.setItemName(item.getName() == null ? "Item" : item.getName());
                line.setQuantity(w.quantity());
                lineRepository.save(line);
            }
            return toResponse(order);
        });
    }

    /** The standing orders buyers have with this supplier's shop. */
    public List<StandingOrderResponse> forSupplier(String supplierBusinessId) {
        return standingOrderRepository.findBySupplierBusinessIdAndActiveTrueOrderByNextRunOnAsc(supplierBusinessId)
                .stream().map(this::toResponse).toList();
    }

    /** The standing orders this buying shop has with suppliers. */
    public List<StandingOrderResponse> forBuyer(String buyerBusinessId) {
        return standingOrderRepository.findByBuyerBusinessIdAndActiveTrueOrderByNextRunOnAsc(buyerBusinessId)
                .stream().map(this::toResponse).toList();
    }

    /** The buying shop stops its own standing order. */
    public void stopAsBuyer(String buyerBusinessId, String standingOrderId) {
        stop(standingOrderRepository.findByIdAndBuyerBusinessId(standingOrderId, buyerBusinessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Standing order not found")));
    }

    /** The supplier stops a standing order a buyer has with it. */
    public void stopAsSupplier(String supplierBusinessId, String standingOrderId) {
        stop(standingOrderRepository.findByIdAndSupplierBusinessId(standingOrderId, supplierBusinessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Standing order not found")));
    }

    private void stop(StandingOrder order) {
        order.setActive(false);
        standingOrderRepository.save(order);
    }

    /**
     * Places every standing order that is due. Each one is its own transaction: a run that fails
     * (out of stock, below the minimum, too many unanswered orders) records why and moves on to
     * its next date; it is not retried the same day.
     *
     * @return how many orders were placed
     */
    public int runDue(LocalDate today) {
        int placed = 0;
        for (StandingOrder due : standingOrderRepository
                .findTop200ByActiveTrueAndNextRunOnLessThanEqualOrderByNextRunOnAsc(today)) {
            String id = due.getId();
            String orderId = null;
            String result;
            try {
                orderId = ownTransaction.execute(status -> placeOne(id));
                result = "Order placed on " + today;
                placed++;
            } catch (ResponseStatusException refused) {
                result = "Not placed on " + today + ": " + refused.getReason();
            } catch (RuntimeException failed) {
                log.warn("standing order {} could not be placed: {}", id, failed.getMessage());
                result = "Not placed on " + today + ". It will be tried again on the next date.";
            }
            String placedOrderId = orderId;
            String outcome = result.length() > 500 ? result.substring(0, 500) : result;
            ownTransaction.executeWithoutResult(status -> standingOrderRepository.findById(id).ifPresent(order -> {
                order.setNextRunOn(today.plusDays(order.getEveryDays()));
                order.setLastResult(outcome);
                if (placedOrderId != null) {
                    order.setLastOrderId(placedOrderId);
                }
                standingOrderRepository.save(order);
            }));
        }
        return placed;
    }

    private String placeOne(String standingOrderId) {
        StandingOrder order = standingOrderRepository.findById(standingOrderId).orElseThrow();
        Business shop = businessRepository.findById(order.getSupplierBusinessId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The supplier's shop is gone"));
        String slug = shop.getSlug();
        String cartId = cartService.createCart(slug).id();
        for (StandingOrderLine line : lineRepository.findByStandingOrderId(order.getId())) {
            cartService.upsertLine(slug, cartId, new PublicUpsertCartLineRequest(line.getItemId(), line.getQuantity()));
        }
        SalesOrderPlacedResponse placed = salesOrderService.placeFromCart(
                slug,
                cartId,
                new PlaceSalesOrderRequest(
                        order.getBuyerName(), order.getBuyerPhone(), null, order.getBuyerBusinessName(),
                        "Standing order, every " + order.getEveryDays() + " days"),
                order.getBuyerBusinessId());
        return placed.orderId();
    }

    private StandingOrderResponse toResponse(StandingOrder order) {
        List<StandingOrderResponse.Line> lines = new ArrayList<>();
        for (StandingOrderLine line : lineRepository.findByStandingOrderId(order.getId())) {
            lines.add(new StandingOrderResponse.Line(line.getItemName(), line.getQuantity()));
        }
        String supplierName = businessRepository.findById(order.getSupplierBusinessId())
                .map(Business::getName).orElse(null);
        return new StandingOrderResponse(
                order.getId(), supplierName, order.getBuyerBusinessName(), order.getEveryDays(),
                order.getNextRunOn(), order.getLastResult(), lines);
    }
}
