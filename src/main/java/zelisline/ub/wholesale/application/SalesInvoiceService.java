package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.trade.application.TradeOrderService;
import zelisline.ub.trade.domain.TradeOrder;
import zelisline.ub.trade.domain.TradeOrderEvent;
import zelisline.ub.wholesale.api.dto.ReceivablesAgeingResponse;
import zelisline.ub.wholesale.api.dto.RecordSalesReceiptRequest;
import zelisline.ub.wholesale.api.dto.SalesInvoiceResponse;
import zelisline.ub.wholesale.domain.SalesInvoice;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.domain.SalesReceipt;
import zelisline.ub.wholesale.domain.TradeAccount;
import zelisline.ub.wholesale.repository.SalesInvoiceRepository;
import zelisline.ub.wholesale.repository.SalesReceiptRepository;
import zelisline.ub.wholesale.repository.TradeAccountRepository;

/**
 * A supplier's receivables (ADR 0042): the invoice a dispatched trade order becomes, the money
 * received against it, and what buyers still owe.
 *
 * <p>The books move twice. When the goods leave: trade receivables up, sales up, and the cost of
 * the goods out of stock and into cost of sales. When money comes in: cash, M-Pesa or bank up,
 * trade receivables down. Tax is not split out (owner's decision O2).
 */
@Service
@RequiredArgsConstructor
public class SalesInvoiceService {

    static final String JOURNAL_SOURCE_INVOICE = "sales_invoice";
    static final String JOURNAL_SOURCE_RECEIPT = "sales_receipt";
    static final String JOURNAL_SOURCE_CREDIT_NOTE = "sales_credit_note";
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final int MONEY_SCALE = 2;

    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesReceiptRepository salesReceiptRepository;
    private final TradeAccountRepository tradeAccountRepository;
    private final StockMovementRepository stockMovementRepository;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final TradeOrderService tradeOrderService;
    private final zelisline.ub.wholesale.repository.SalesCreditNoteRepository salesCreditNoteRepository;

    /**
     * Called as the order is dispatched, in the same transaction: the goods have left, so the buyer
     * owes for them. One invoice per order; a second call returns the first.
     */
    @Transactional
    public SalesInvoice issueOnDispatch(SalesOrder order) {
        String businessId = order.getBusinessId();
        var existing = salesInvoiceRepository.findBySalesOrderIdAndBusinessId(order.getId(), businessId);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDate today = LocalDate.now(NAIROBI);
        BigDecimal total = order.getTotal().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal cost = costOfGoods(businessId, order.getId());

        SalesInvoice invoice = new SalesInvoice();
        invoice.setBusinessId(businessId);
        invoice.setSalesOrderId(order.getId());
        invoice.setInvoiceNo("INV-" + WebOrderCodes.code(order.getId()));
        invoice.setCurrency(order.getCurrency());
        invoice.setTotal(total);
        invoice.setCostOfGoods(cost);
        invoice.setIssuedOn(today);
        invoice.setDueOn(today.plusDays(daysToPay(order)));
        invoice.setBuyerName(order.getBuyerName());
        invoice.setBuyerBusinessName(order.getBuyerBusinessName());
        invoice.setBuyerPhone(order.getBuyerPhone());
        salesInvoiceRepository.save(invoice);

        if (total.signum() > 0) {
            JournalEntry entry = new JournalEntry();
            entry.setBusinessId(businessId);
            entry.setEntryDate(today);
            entry.setSourceType(JOURNAL_SOURCE_INVOICE);
            entry.setSourceId(invoice.getId());
            entry.setMemo("Sales invoice " + invoice.getInvoiceNo());
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.TRADE_RECEIVABLES), total);
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.SALES_REVENUE), total);
            if (cost.signum() > 0) {
                entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.COST_OF_GOODS_SOLD), cost);
                entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY), cost);
            }
            ledgerPostingPort.post(entry);
        }
        tradeOrderService.onMoneyState(
                businessId, order.getId(), TradeOrder.MONEY_INVOICED, TradeOrderEvent.INVOICED);
        return invoice;
    }

    @Transactional(readOnly = true)
    public List<SalesInvoiceResponse> list(String businessId, boolean unpaidOnly) {
        List<SalesInvoice> rows = unpaidOnly
                ? salesInvoiceRepository.findTop500ByBusinessIdAndStatusInOrderByDueOnAsc(
                        businessId, List.of(SalesInvoice.OPEN, SalesInvoice.PART_PAID))
                : salesInvoiceRepository.findTop500ByBusinessIdOrderByIssuedOnDescCreatedAtDesc(businessId);
        return rows.stream().map(i -> toResponse(i, null)).toList();
    }

    @Transactional(readOnly = true)
    public SalesInvoiceResponse get(String businessId, String invoiceId) {
        return withReceipts(requireInvoice(businessId, invoiceId));
    }

    /** Money received. It cannot be more than is still owed on the invoice. */
    @Transactional
    public SalesInvoiceResponse recordReceipt(String businessId, String invoiceId, RecordSalesReceiptRequest req) {
        SalesInvoice invoice = requireInvoice(businessId, invoiceId);
        BigDecimal amount = req.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal owed = invoice.owed();
        if (owed.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This invoice is already paid");
        }
        if (amount.compareTo(owed) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Only " + owed.toPlainString() + " is still owed on this invoice");
        }
        LocalDate today = LocalDate.now(NAIROBI);
        LocalDate receivedOn = req.receivedOn() == null ? today : req.receivedOn();
        if (receivedOn.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The date received cannot be in the future");
        }

        SalesReceipt receipt = new SalesReceipt();
        receipt.setBusinessId(businessId);
        receipt.setInvoiceId(invoice.getId());
        receipt.setAmount(amount);
        receipt.setMethod(req.method());
        receipt.setReference(req.reference() == null || req.reference().isBlank() ? null : req.reference().trim());
        receipt.setReceivedOn(receivedOn);
        salesReceiptRepository.save(receipt);

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(receivedOn);
        entry.setSourceType(JOURNAL_SOURCE_RECEIPT);
        entry.setSourceId(receipt.getId());
        entry.setMemo("Received on sales invoice " + invoice.getInvoiceNo());
        entry.debit(ledgerAccountResolver.resolveId(businessId, moneyAccount(req.method())), amount);
        entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.TRADE_RECEIVABLES), amount);
        ledgerPostingPort.post(entry);

        invoice.setAmountPaid(invoice.getAmountPaid().add(amount));
        boolean settled = invoice.owed().signum() <= 0;
        invoice.setStatus(settled ? SalesInvoice.PAID : SalesInvoice.PART_PAID);
        salesInvoiceRepository.save(invoice);
        tradeOrderService.onMoneyState(
                businessId,
                invoice.getSalesOrderId(),
                settled ? TradeOrder.MONEY_SETTLED : TradeOrder.MONEY_PART_PAID,
                TradeOrderEvent.PAYMENT);
        return withReceipts(invoice);
    }

    /**
     * Takes money off what is owed on an invoice: a shortage, goods sent back, a price put right
     * (P2-6). It reverses that much of the sale in the books. It does not put stock back; goods
     * that come back are received into stock the way any stock is.
     */
    @Transactional
    public SalesInvoiceResponse issueCreditNote(String businessId, String invoiceId, BigDecimal rawAmount, String reason) {
        SalesInvoice invoice = requireInvoice(businessId, invoiceId);
        BigDecimal amount = rawAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal owed = invoice.owed();
        if (owed.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing is owed on this invoice");
        }
        if (amount.compareTo(owed) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Only " + owed.toPlainString() + " is still owed on this invoice");
        }
        zelisline.ub.wholesale.domain.SalesCreditNote note = new zelisline.ub.wholesale.domain.SalesCreditNote();
        note.setBusinessId(businessId);
        note.setInvoiceId(invoice.getId());
        note.setAmount(amount);
        note.setReason(reason.trim());
        salesCreditNoteRepository.save(note);

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(LocalDate.now(NAIROBI));
        entry.setSourceType(JOURNAL_SOURCE_CREDIT_NOTE);
        entry.setSourceId(note.getId());
        entry.setMemo("Credit note on sales invoice " + invoice.getInvoiceNo());
        entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.SALES_REVENUE), amount);
        entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.TRADE_RECEIVABLES), amount);
        ledgerPostingPort.post(entry);

        invoice.setAmountCredited(invoice.getAmountCredited().add(amount));
        boolean settled = invoice.owed().signum() <= 0;
        if (settled) {
            invoice.setStatus(SalesInvoice.PAID);
        }
        salesInvoiceRepository.save(invoice);
        tradeOrderService.onCreditNote(businessId, invoice.getSalesOrderId(), settled);
        return withReceipts(invoice);
    }

    /** What each buyer still owes, by how many days past due it is, largest debt first. */
    @Transactional(readOnly = true)
    public ReceivablesAgeingResponse ageing(String businessId) {
        LocalDate today = LocalDate.now(NAIROBI);
        Map<String, BigDecimal[]> byBuyer = new LinkedHashMap<>();
        Map<String, String[]> labels = new LinkedHashMap<>();
        String currency = null;
        for (SalesInvoice invoice : salesInvoiceRepository.findTop500ByBusinessIdAndStatusInOrderByDueOnAsc(
                businessId, List.of(SalesInvoice.OPEN, SalesInvoice.PART_PAID))) {
            BigDecimal owed = invoice.owed();
            if (owed.signum() <= 0) {
                continue;
            }
            currency = currency == null ? invoice.getCurrency() : currency;
            String key = TradeAccountService.phoneKey(invoice.getBuyerPhone());
            key = key == null ? invoice.getBuyerPhone() : key;
            BigDecimal[] buckets = byBuyer.computeIfAbsent(key, k -> new BigDecimal[] {
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            labels.putIfAbsent(key, new String[] {
                    invoice.getBuyerBusinessName() != null ? invoice.getBuyerBusinessName() : invoice.getBuyerName(),
                    invoice.getBuyerPhone()});
            long late = ChronoUnit.DAYS.between(invoice.getDueOn(), today);
            int bucket = late <= 0 ? 0 : late <= 30 ? 1 : late <= 60 ? 2 : late <= 90 ? 3 : 4;
            buckets[bucket] = buckets[bucket].add(owed);
        }
        List<ReceivablesAgeingResponse.Row> rows = new ArrayList<>();
        BigDecimal grand = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : byBuyer.entrySet()) {
            BigDecimal[] b = e.getValue();
            BigDecimal sum = b[0].add(b[1]).add(b[2]).add(b[3]).add(b[4]);
            grand = grand.add(sum);
            String[] label = labels.get(e.getKey());
            rows.add(new ReceivablesAgeingResponse.Row(label[0], label[1], b[0], b[1], b[2], b[3], b[4], sum));
        }
        rows.sort((x, y) -> y.total().compareTo(x.total()));
        return new ReceivablesAgeingResponse(currency, grand, rows);
    }

    private SalesInvoice requireInvoice(String businessId, String invoiceId) {
        return salesInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    /** The days the buyer's approved account allows; an order paid up front is due the day it leaves. */
    private long daysToPay(SalesOrder order) {
        if (!SalesOrderStatuses.TERMS_CREDIT.equals(order.getPaymentTerms()) || order.getTradeAccountId() == null) {
            return 0;
        }
        return tradeAccountRepository.findByIdAndBusinessId(order.getTradeAccountId(), order.getBusinessId())
                .map(TradeAccount::getTermsDays)
                .filter(days -> days != null && days > 0)
                .map(Integer::longValue)
                .orElse(0L);
    }

    /** What the goods cost: the batches this order took, less anything it put back. */
    private BigDecimal costOfGoods(String businessId, String salesOrderId) {
        BigDecimal cost = BigDecimal.ZERO;
        for (StockMovement m : stockMovementRepository.findByBusinessIdAndReferenceTypeAndReferenceId(
                businessId, SalesOrderStatuses.STOCK_REFERENCE_TYPE, salesOrderId)) {
            if (m.getQuantityDelta() == null || m.getUnitCost() == null) {
                continue;
            }
            BigDecimal value = m.getQuantityDelta().abs().multiply(m.getUnitCost());
            if (InventoryConstants.MOVEMENT_SALE.equals(m.getMovementType())) {
                cost = cost.add(value);
            } else if (InventoryConstants.MOVEMENT_SALE_VOID.equals(m.getMovementType())) {
                cost = cost.subtract(value);
            }
        }
        return cost.max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String moneyAccount(String method) {
        return switch (method) {
            case SalesReceipt.MPESA -> LedgerAccountCodes.MPESA_CLEARING;
            case SalesReceipt.BANK -> LedgerAccountCodes.BANK_ACCOUNT;
            default -> LedgerAccountCodes.OPERATING_CASH;
        };
    }

    private SalesInvoiceResponse withReceipts(SalesInvoice invoice) {
        List<SalesInvoiceResponse.Receipt> receipts = salesReceiptRepository
                .findByInvoiceIdAndBusinessIdOrderByCreatedAtAsc(invoice.getId(), invoice.getBusinessId())
                .stream()
                .map(r -> new SalesInvoiceResponse.Receipt(
                        r.getId(), r.getAmount(), r.getMethod(), r.getReference(), r.getReceivedOn()))
                .toList();
        return toResponse(invoice, receipts);
    }

    private static SalesInvoiceResponse toResponse(SalesInvoice i, List<SalesInvoiceResponse.Receipt> receipts) {
        return new SalesInvoiceResponse(
                i.getId(),
                i.getInvoiceNo(),
                i.getSalesOrderId(),
                i.getStatus(),
                i.getCurrency(),
                i.getTotal(),
                i.getAmountPaid(),
                i.owed(),
                i.getIssuedOn(),
                i.getDueOn(),
                i.getBuyerName(),
                i.getBuyerBusinessName(),
                i.getBuyerPhone(),
                receipts,
                i.getAmountCredited());
    }
}
