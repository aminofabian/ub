package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.grocery.api.dto.CancelGroceryInvoiceRequest;
import zelisline.ub.grocery.application.GroceryInvoiceService;
import zelisline.ub.grocery.domain.GroceryInvoice;
import zelisline.ub.grocery.domain.GroceryInvoiceLine;
import zelisline.ub.grocery.repository.GroceryInvoiceLineRepository;
import zelisline.ub.grocery.repository.GroceryInvoiceRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.wholesale.api.dto.AnswerSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.CounterInvoiceAccountResponse;
import zelisline.ub.wholesale.api.dto.CounterTermsResponse;
import zelisline.ub.wholesale.api.dto.SalesOrderResponse;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderLine;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.domain.TradeAccount;
import zelisline.ub.wholesale.repository.SalesInvoiceRepository;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;
import zelisline.ub.wholesale.repository.TradeAccountRepository;

/**
 * Trade terms at a supplier's counter. The clerk reads a buyer's account terms to price the order; an owner or
 * admin may put the pending counter invoice on that account. On account, the invoice becomes a trade order on
 * credit that is accepted in full and dispatched at once, so stock, the sales invoice and the ledger entry follow
 * the same path as any trade order (ADR 0042). Off unless the supplier turned counter orders on.
 */
@Service
@RequiredArgsConstructor
public class CounterOrderService {

    private static final int MONEY_SCALE = 2;

    private final FeatureFlagService featureFlagService;
    private final TradeAccountService tradeAccountService;
    private final SalesOrderService salesOrderService;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final GroceryInvoiceRepository groceryInvoiceRepository;
    private final GroceryInvoiceLineRepository groceryInvoiceLineRepository;
    private final GroceryInvoiceService groceryInvoiceService;
    private final BusinessRepository businessRepository;
    private final TradeAccountRepository tradeAccountRepository;
    private final PricingService pricingService;

    @Transactional(readOnly = true)
    public CounterTermsResponse terms(String businessId, String phone) {
        requireOn(businessId);
        return tradeAccountService.approvedFor(businessId, phone)
                .map(account -> {
                    BigDecimal available = account.getCreditLimit() == null
                            ? null
                            : account.getCreditLimit().subtract(owed(businessId, account)).max(BigDecimal.ZERO);
                    return new CounterTermsResponse(
                            true, account.getId(), account.getBuyerName(), account.getBusinessName(),
                            account.getDiscountPercent(), account.getTermsDays(), account.getCreditLimit(),
                            available, tradeAccountService.priceList(businessId, account.getId()));
                })
                .orElseGet(CounterTermsResponse::none);
    }

    @Transactional
    public SalesOrderResponse putOnAccount(String businessId, String userId, String invoiceId) {
        requireOn(businessId);
        GroceryInvoice invoice = groceryInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (!invoice.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an unpaid invoice can go on account");
        }
        if (invoice.getTradeAccountId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The clerk did not pick an account for this invoice");
        }
        TradeAccount account = requireApproved(businessId, invoice.getTradeAccountId());
        List<GroceryInvoiceLine> invoiceLines =
                groceryInvoiceLineRepository.findByInvoiceIdOrderByLineIndex(invoice.getId());
        if (invoiceLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This invoice has no lines");
        }
        requireAccountPrices(businessId, invoice, account, invoiceLines);
        BigDecimal total = invoice.getGrandTotal();
        if (account.getCreditLimit() != null
                && owed(businessId, account).add(total).compareTo(account.getCreditLimit()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This would go over the account's credit limit");
        }

        SalesOrder order = new SalesOrder();
        order.setBusinessId(businessId);
        order.setBranchId(invoice.getBranchId());
        order.setSource(SalesOrderStatuses.SOURCE_COUNTER);
        order.setCurrency(businessRepository.findById(businessId)
                .map(b -> b.getCurrency())
                .orElse("KES"));
        order.setTotal(total);
        order.setBuyerName(account.getBuyerName());
        order.setBuyerPhone(account.getBuyerPhone());
        order.setBuyerBusinessName(account.getBusinessName());
        order.setBuyerPhoneKey(account.getPhoneKey());
        // A buyer shop connected on Kiosk gets the order on its side, as it does for an order placed online.
        order.setBuyerBusinessId(account.getBuyerBusinessId());
        order.setNotes("Counter order, invoice " + invoice.getBarcodeCode());
        order.setPaymentTerms(SalesOrderStatuses.TERMS_CREDIT);
        order.setTradeAccountId(account.getId());
        // Sold over the counter: it says nothing about how the supplier answers or dispatches trade orders.
        order.setStandingExcluded(true);
        order.setStandingExcludedAt(java.time.Instant.now());
        order.setStandingExclusionNote("Counter order");
        salesOrderRepository.save(order);

        List<AnswerSalesOrderRequest.Line> answers = new ArrayList<>();
        for (GroceryInvoiceLine line : invoiceLines) {
            SalesOrderLine row = new SalesOrderLine();
            row.setOrderId(order.getId());
            row.setBusinessId(businessId);
            row.setItemId(line.getItemId());
            row.setItemName(line.getItemName());
            row.setQuantity(line.getQuantity());
            row.setUnitPrice(line.getUnitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            row.setLineTotal(line.getLineTotal());
            row.setLineIndex(line.getLineIndex());
            salesOrderLineRepository.save(row);
            answers.add(new AnswerSalesOrderRequest.Line(row.getId(), line.getQuantity()));
        }

        // Accept in full (stock leaves the shelf), then dispatch (the sales invoice and its ledger entry).
        salesOrderService.answer(businessId, order.getId(), new AnswerSalesOrderRequest(answers));
        SalesOrderResponse dispatched = salesOrderService.dispatch(businessId, order.getId());
        // The counter invoice is settled by the account, so the cashier must not also take payment for it.
        groceryInvoiceService.cancelInvoice(businessId, invoice.getId(),
                new CancelGroceryInvoiceRequest("Put on account: trade order " + order.getId()), userId);
        return dispatched;
    }

    /**
     * The clerk issued this invoice for a buyer's account. The prices are checked here, on the server, against
     * that account: no line may be cheaper than the buyer's agreed price, or the shelf price less the discount.
     */
    @Transactional
    public CounterInvoiceAccountResponse attachAccount(String businessId, String invoiceId, String accountId) {
        requireOn(businessId);
        GroceryInvoice invoice = groceryInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (!invoice.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an unpaid invoice can take an account");
        }
        TradeAccount account = requireApproved(businessId, accountId);
        requireAccountPrices(businessId, invoice, account,
                groceryInvoiceLineRepository.findByInvoiceIdOrderByLineIndex(invoice.getId()));
        invoice.setTradeAccountId(account.getId());
        // The buyer's number goes on the invoice, so the cashier and the printed invoice show who it is for.
        if (invoice.getCustomerPhone() == null || invoice.getCustomerPhone().isBlank()) {
            invoice.setCustomerPhone(account.getBuyerPhone());
        }
        groceryInvoiceRepository.save(invoice);
        return new CounterInvoiceAccountResponse(
                true, account.getId(), account.getBuyerName(), account.getBusinessName(), invoice.getCustomerPhone());
    }

    @Transactional(readOnly = true)
    public CounterInvoiceAccountResponse invoiceAccount(String businessId, String invoiceId) {
        requireOn(businessId);
        GroceryInvoice invoice = groceryInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (invoice.getTradeAccountId() == null) {
            return CounterInvoiceAccountResponse.none();
        }
        return tradeAccountRepository.findById(invoice.getTradeAccountId())
                .filter(a -> businessId.equals(a.getBusinessId()))
                .map(a -> new CounterInvoiceAccountResponse(
                        true, a.getId(), a.getBuyerName(), a.getBusinessName(), invoice.getCustomerPhone()))
                .orElseGet(CounterInvoiceAccountResponse::none);
    }

    private TradeAccount requireApproved(String businessId, String accountId) {
        return tradeAccountRepository.findById(accountId)
                .filter(a -> businessId.equals(a.getBusinessId()) && TradeAccount.APPROVED.equals(a.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This account is not approved"));
    }

    private void requireAccountPrices(
            String businessId, GroceryInvoice invoice, TradeAccount account, List<GroceryInvoiceLine> invoiceLines) {
        var ownPrices = tradeAccountService.priceList(businessId, account.getId());
        BigDecimal discount = account.getDiscountPercent() != null && account.getDiscountPercent().signum() > 0
                ? account.getDiscountPercent()
                : BigDecimal.ZERO;
        for (GroceryInvoiceLine line : invoiceLines) {
            BigDecimal floor = ownPrices.get(line.getItemId());
            if (floor == null) {
                BigDecimal shelf = pricingService.getCurrentOpenSellingPrice(
                        businessId, line.getItemId(), invoice.getBranchId());
                if (shelf == null) {
                    continue;
                }
                floor = shelf.multiply(BigDecimal.valueOf(100).subtract(discount))
                        .divide(BigDecimal.valueOf(100), MONEY_SCALE, RoundingMode.HALF_UP);
            }
            if (line.getUnitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    .compareTo(floor.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        line.getItemName() + " is priced below this account's price");
            }
        }
    }

    private BigDecimal owed(String businessId, TradeAccount account) {
        BigDecimal onInvoices = salesInvoiceRepository.sumOwedForAccount(businessId, account.getId());
        BigDecimal notInvoicedYet = salesOrderRepository.sumOpenOrdersForAccount(businessId, account.getId());
        return (onInvoices == null ? BigDecimal.ZERO : onInvoices)
                .add(notInvoicedYet == null ? BigDecimal.ZERO : notInvoicedYet);
    }

    private void requireOn(String businessId) {
        if (!featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_COUNTER_ORDERS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
