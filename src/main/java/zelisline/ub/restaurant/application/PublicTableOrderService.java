package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.restaurant.api.dto.OrderDtos.NewOrderLine;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicDish;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicGroup;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicMenu;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicMenuBranch;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicMenuDish;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicOption;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicOrderConfirmation;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicOrderLine;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicOrderRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicOrderStatus;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicStatusLine;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicTableMenu;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuItemResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ModifierGroupResponse;
import zelisline.ub.restaurant.domain.RestaurantSettings;
import zelisline.ub.serviceops.application.ServiceResourceService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Ordering from the QR code on a table (ADR 0020, amendment 3). Nobody is signed in, so this
 * guards itself and trusts nothing it is sent:
 *
 * <ul>
 * <li>the business comes from the slug in the path and must own the table the code names;</li>
 * <li>the code is a long random secret an owner can replace, and works only while the restaurant
 *     has QR ordering switched on;</li>
 * <li>a customer's lines are added <em>unsent</em>. Nothing reaches the kitchen until a member of
 *     staff looks at the order and sends it, so a stranger with a photo of the code can waste a
 *     waiter's glance but not the kitchen's food;</li>
 * <li>requests are limited per address, per request and per order; every price is the server's.</li>
 * </ul>
 *
 * No customer details are taken, and nothing about why a request was refused is told to the caller
 * beyond what they need to fix their own order.
 */
@Service
@RequiredArgsConstructor
public class PublicTableOrderService {

    private static final int READS_PER_MINUTE = 40;
    private static final int WRITES_PER_MINUTE = 6;
    private static final int WEB_MENU_READS_PER_MINUTE = 300;
    /** However many phones are used, one order holds at most this many lines nobody has sent yet. */
    private static final int MAX_UNSENT_LINES = 40;
    private static final int MAX_TRACKED_ADDRESSES = 20_000;

    private final BusinessRepository businessRepository;
    private final ServiceResourceService resourceService;
    private final RestaurantSettingsService settingsService;
    private final MenuService menuService;
    private final DineInService dineInService;
    private final OrderTicketService orderTicketService;
    private final RestaurantGate gate;
    private final zelisline.ub.tenancy.repository.BranchRepository branchRepository;

    /** Requests per address in the last minute. In memory: it slows a casual flood on one node, no more. */
    private final ConcurrentHashMap<String, List<Long>> hits = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public PublicTableMenu menu(String slug, String token, String clientAddress) {
        if (!allow("r:" + clientAddress, READS_PER_MINUTE)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; wait a moment");
        }
        Business business = requireBusiness(slug);
        ServiceResourceService.TableRecord table = requireTable(business, token);
        MenuResponse menu = menuService.menu(business.getId(), table.branchId());
        Map<String, ModifierGroupResponse> groups = new HashMap<>();
        for (ModifierGroupResponse group : menu.modifierGroups()) {
            groups.put(group.id(), group);
        }
        Map<String, String> headings = new HashMap<>();
        menu.categories().forEach(c -> headings.put(c.id(), c.name()));
        List<PublicDish> dishes = new ArrayList<>();
        for (MenuItemResponse item : menu.items()) {
            // Only what can actually be ordered now, and only with a price.
            if (!item.active() || !item.available() || !item.servedNow() || !item.sellable()
                    || item.price() == null || item.price().signum() <= 0) {
                continue;
            }
            List<PublicGroup> itemGroups = new ArrayList<>();
            for (String groupId : item.modifierGroupIds()) {
                ModifierGroupResponse group = groups.get(groupId);
                if (group == null) {
                    continue;
                }
                itemGroups.add(new PublicGroup(group.id(), group.name(), group.minSelect(), group.maxSelect(),
                        group.options().stream().filter(o -> o.available())
                                .map(o -> new PublicOption(o.id(), o.name(), o.priceDelta())).toList()));
            }
            dishes.add(new PublicDish(item.id(), item.categoryId() == null ? null : headings.get(item.categoryId()),
                    item.name(), item.price(), itemGroups, item.description(), item.imageUrl()));
        }
        return new PublicTableMenu(business.getName(), table.name(),
                business.getCurrency() == null || business.getCurrency().isBlank() ? "KES" : business.getCurrency(),
                dishes);
    }

    /**
     * The menu for the restaurant's public web page. Only when the owner has switched it on;
     * otherwise not found, like a shop that does not exist. It is read-only: a price and a
     * picture, nothing that can be ordered and nothing about stock or cost.
     */
    @Transactional(readOnly = true)
    public PublicMenu webMenu(String slug, String clientAddress) {
        // The web page is rendered on our own server, so many visitors arrive from one address.
        if (!allow("m:" + slug + ":" + clientAddress, WEB_MENU_READS_PER_MINUTE)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; wait a moment");
        }
        Business business = requireBusiness(slug);
        if (!settingsService.get(business.getId()).isMenuOnline()) {
            throw notFound();
        }
        List<PublicMenuBranch> branches = new ArrayList<>();
        String menuBranchId = null;
        for (Branch branch : branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(business.getId())) {
            if (!branch.isActive()) {
                continue;
            }
            if (menuBranchId == null) {
                menuBranchId = branch.getId();
            }
            branches.add(new PublicMenuBranch(branch.getName(), branch.getAddress()));
        }
        if (menuBranchId == null) {
            throw notFound();
        }
        MenuResponse menu = menuService.menu(business.getId(), menuBranchId);
        Map<String, String> headingNames = new HashMap<>();
        List<String> headings = new ArrayList<>();
        menu.categories().stream().filter(c -> c.active()).forEach(c -> headingNames.put(c.id(), c.name()));
        List<PublicMenuDish> dishes = new ArrayList<>();
        for (MenuItemResponse item : menu.items()) {
            if (!item.active() || item.price() == null || item.price().signum() <= 0) {
                continue;
            }
            String heading = item.categoryId() == null ? null : headingNames.get(item.categoryId());
            dishes.add(new PublicMenuDish(item.id(), heading, item.name(), item.description(), item.price(),
                    item.imageUrl(), item.available() && item.servedNow() && item.sellable()));
        }
        if (dishes.isEmpty()) {
            // Nothing priced to show. This is also what keeps the page off a business that is
            // not a restaurant at all: a barbershop has no menu, so it has no menu page.
            throw notFound();
        }
        // Headings in the menu's own order, and only those with something under them.
        menu.categories().forEach(c -> {
            String name = headingNames.get(c.id());
            if (name != null && !headings.contains(name) && dishes.stream().anyMatch(d -> name.equals(d.heading()))) {
                headings.add(name);
            }
        });
        return new PublicMenu(business.getName(),
                business.getCurrency() == null || business.getCurrency().isBlank() ? "KES" : business.getCurrency(),
                headings, dishes, branches);
    }

    /**
     * Adds the customer's lines, unsent, to the open order at the table, opening one if the table
     * has none. Sending the same request again adds nothing more.
     */
    @Transactional
    public PublicOrderConfirmation order(String slug, String token, PublicOrderRequest request, String clientAddress) {
        if (!allow("w:" + clientAddress, WRITES_PER_MINUTE)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; wait a moment");
        }
        Business business = requireBusiness(slug);
        ServiceResourceService.TableRecord table = requireTable(business, token);
        RestaurantSettings settings = settingsService.get(business.getId());
        String openedBy = settings.getQrEnabledBy();
        if (openedBy == null) {
            throw notFound();
        }
        // The newest open order at the table that is not a booking; else a new one for this table.
        OrderTicketService.Order target = openOrderAt(business.getId(), table.branchId(), table.id());
        if (target == null) {
            // Opening is idempotent on the request id, so a phone that retries opens one order, not two.
            // Two phones at an empty table at the same instant can still open one each; staff see
            // both on the table and can join them.
            target = orderTicketService.open(business.getId(), openedBy, new OrderTicketService.OpenOrder(
                    table.branchId(), OrderTicketService.CHANNEL_DINE_IN, table.id(), null, null, null, null, null,
                    null, "qr-" + table.id().substring(0, 8) + "-" + request.requestId(), null, null, null, "QR"));
        }
        long unsent = target.lines().stream().filter(OrderTicketService.OrderLine::isDraft).count();
        if (unsent + request.lines().size() > MAX_UNSENT_LINES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This table already has an order waiting; please ask a member of staff");
        }
        List<NewOrderLine> lines = new ArrayList<>();
        int index = 0;
        for (PublicOrderLine line : request.lines()) {
            lines.add(new NewOrderLine(OrderTicketService.CUSTOMER_LINE_PREFIX + request.requestId() + "-" + index++, line.menuItemId(),
                    line.modifierOptionIds(), BigDecimal.valueOf(line.quantity()), line.note(), null));
        }
        int before = target.lines().size();
        var updated = dineInService.addLines(business.getId(), target.id(), lines);
        return new PublicOrderConfirmation(updated.orderNumber(), table.name(), updated.lines().size() - before,
                updated.total());
    }

    /**
     * What the diner's table has ordered and still owes. Read-only, and deliberately narrow: line
     * names, quantities, amounts and kitchen stages, plus whether the bill has been asked for. No
     * customer name or phone, no staff name, no bill internals.
     */
    @Transactional(readOnly = true)
    public PublicOrderStatus status(String slug, String token, String clientAddress) {
        if (!allow("r:" + clientAddress, READS_PER_MINUTE)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; wait a moment");
        }
        Business business = requireBusiness(slug);
        ServiceResourceService.TableRecord table = requireTable(business, token);
        return statusOf(business, table, openOrderAt(business.getId(), table.branchId(), table.id()));
    }

    /**
     * The diner asks for the bill. The floor already treats "bill requested" as a table that needs
     * someone, so this uses the same flag staff see. Repeating it changes nothing.
     */
    @Transactional
    public PublicOrderStatus requestBill(String slug, String token, String clientAddress) {
        if (!allow("w:" + clientAddress, WRITES_PER_MINUTE)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; wait a moment");
        }
        Business business = requireBusiness(slug);
        ServiceResourceService.TableRecord table = requireTable(business, token);
        OrderTicketService.Order order = openOrderAt(business.getId(), table.branchId(), table.id());
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing has been ordered at this table yet");
        }
        if (order.billRequestedAt() == null) {
            order = orderTicketService.requestBill(business.getId(), order.id(), true, Instant.now());
        }
        return statusOf(business, table, order);
    }

    /**
     * Takes off a line this table's phone added, while staff have not sent it yet. A line staff added,
     * or one already in the kitchen, cannot be removed here: the first is not the diner's to remove,
     * and the second needs a person to stop the kitchen and record why. Both answer "not found" /
     * "ask a member of staff" rather than saying whose line it is.
     */
    @Transactional
    public PublicOrderStatus removeLine(String slug, String token, String orderLineId, String clientAddress) {
        if (!allow("w:" + clientAddress, WRITES_PER_MINUTE)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; wait a moment");
        }
        Business business = requireBusiness(slug);
        ServiceResourceService.TableRecord table = requireTable(business, token);
        OrderTicketService.Order order = openOrderAt(business.getId(), table.branchId(), table.id());
        if (order == null) {
            throw notFound();
        }
        OrderTicketService.OrderLine line = order.lines().stream()
                .filter(l -> l.id().equals(orderLineId))
                .findFirst()
                .orElseThrow(PublicTableOrderService::notFound);
        if (!line.fromCustomer()) {
            throw notFound();
        }
        if (!line.isDraft()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That has gone to the kitchen; ask a member of staff to take it off");
        }
        dineInService.voidLine(business.getId(), order.id(), line.id(),
                "Removed from the table's phone", null, false);
        return statusOf(business, table, openOrderAt(business.getId(), table.branchId(), table.id()));
    }

    /** The table's open, non-booking order. Where two exist (two phones, one table), the last wins. */
    private OrderTicketService.Order openOrderAt(String businessId, String branchId, String tableId) {
        OrderTicketService.Order target = null;
        for (OrderTicketService.Order open : orderTicketService.openOrders(businessId, branchId)) {
            if (tableId.equals(open.tableResourceId()) && !open.isBooked()) {
                target = open;
            }
        }
        return target;
    }

    private PublicOrderStatus statusOf(
            Business business, ServiceResourceService.TableRecord table, OrderTicketService.Order order) {
        String currency = business.getCurrency() == null || business.getCurrency().isBlank()
                ? "KES" : business.getCurrency();
        if (order == null) {
            return new PublicOrderStatus(business.getName(), table.name(), currency, null, "NONE", false,
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), List.of());
        }
        Map<String, BigDecimal> settled = dineInService.settledQuantity(business.getId(), order.id());
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal due = BigDecimal.ZERO;
        List<PublicStatusLine> lines = new ArrayList<>();
        for (OrderTicketService.OrderLine line : order.lines()) {
            BigDecimal amount = line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal();
            lines.add(new PublicStatusLine(line.id(), line.name(), line.quantity(), amount, line.stage(),
                    line.isDraft() && line.fromCustomer()));
            if (line.isVoided()) {
                continue;
            }
            total = total.add(amount);
            BigDecimal settledQty = settled.getOrDefault(line.id(), BigDecimal.ZERO);
            BigDecimal onABill = line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice().multiply(settledQty);
            due = due.add(amount.subtract(onABill).max(BigDecimal.ZERO));
        }
        return new PublicOrderStatus(business.getName(), table.name(), currency, order.orderNumber(), order.status(),
                order.billRequestedAt() != null,
                total.setScale(2, RoundingMode.HALF_UP), due.setScale(2, RoundingMode.HALF_UP), lines);
    }

    private Business requireBusiness(String slug) {
        if (slug == null || slug.isBlank() || slug.length() > 120) {
            throw notFound();
        }
        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug.trim()).orElseThrow(PublicTableOrderService::notFound);
        if (!gate.isEnabled(business.getId())) {
            throw notFound();
        }
        return business;
    }

    /** The table the code names, only if it belongs to this business and QR ordering is on. Otherwise: not found. */
    private ServiceResourceService.TableRecord requireTable(Business business, String token) {
        ServiceResourceService.TableRecord table = resourceService.tableByQrToken(token).orElseThrow(PublicTableOrderService::notFound);
        if (!business.getId().equals(table.businessId()) || !settingsService.get(business.getId()).isQrOrdering()) {
            throw notFound();
        }
        return table;
    }

    /** One answer for every reason a code does not work, so nothing can be learnt by probing. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }

    private boolean allow(String key, int perMinute) {
        if (key == null || key.length() < 3) {
            return true;
        }
        long now = System.currentTimeMillis();
        boolean[] allowed = {true};
        hits.compute(key, (k, list) -> {
            List<Long> recent = new ArrayList<>();
            if (list != null) {
                for (Long at : list) {
                    if (now - at < 60_000) {
                        recent.add(at);
                    }
                }
            }
            if (recent.size() >= perMinute) {
                allowed[0] = false;
            } else {
                recent.add(now);
            }
            return recent;
        });
        if (hits.size() > MAX_TRACKED_ADDRESSES) {
            // Someone is cycling addresses. Forget them all rather than grow without end.
            hits.clear();
        }
        return allowed[0];
    }
}
