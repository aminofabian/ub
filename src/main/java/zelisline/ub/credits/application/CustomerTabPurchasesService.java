package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.credits.CreditTxnTypes;
import zelisline.ub.credits.WalletTxnTypes;
import zelisline.ub.credits.api.dto.TabPurchaseLineResponse;
import zelisline.ub.credits.api.dto.TabPurchaseRowResponse;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditTransaction;
import zelisline.ub.credits.domain.WalletTransaction;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.WalletTransactionRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SaleRepository;

@Service
@RequiredArgsConstructor
public class CustomerTabPurchasesService {

    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 4;
    private static final int DEFAULT_LIMIT = 40;

    private final CustomerRepository customerRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final ItemRepository itemRepository;

    @Transactional(readOnly = true)
    public List<TabPurchaseRowResponse> list(String businessId, String customerId) {
        customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit profile not found"));

        // Include cash / wallet visits linked to the customer — not only tab (debt) sales.
        List<Sale> sales = new ArrayList<>(saleRepository.findByBusinessIdAndCustomerIdOrderBySoldAtDesc(
                businessId, customerId, PageRequest.of(0, DEFAULT_LIMIT)));

        // Also pull sales that credited this wallet (covers edge cases where customerId
        // was not persisted on the sale row but the wallet ledger was updated).
        Set<String> knownIds = new HashSet<>();
        for (Sale s : sales) {
            knownIds.add(s.getId());
        }
        List<WalletTransaction> walletRows =
                walletTransactionRepository.findByCreditAccountIdOrderByCreatedAtDesc(acc.getId());
        Set<String> walletSaleIds = new HashSet<>();
        for (WalletTransaction w : walletRows) {
            if (w.getSaleId() == null || w.getSaleId().isBlank()) {
                continue;
            }
            if (!WalletTxnTypes.CREDIT_OVERPAY_CHANGE.equals(w.getTxnType())
                    && !WalletTxnTypes.CREDIT_REFUND.equals(w.getTxnType())) {
                continue;
            }
            String sid = w.getSaleId().trim();
            if (!knownIds.contains(sid)) {
                walletSaleIds.add(sid);
            }
        }
        if (!walletSaleIds.isEmpty()) {
            for (Sale sale : saleRepository.findAllById(walletSaleIds)) {
                if (businessId.equals(sale.getBusinessId()) && !knownIds.contains(sale.getId())) {
                    sales.add(sale);
                    knownIds.add(sale.getId());
                }
            }
            sales.sort((a, b) -> {
                if (a.getSoldAt() == null && b.getSoldAt() == null) return 0;
                if (a.getSoldAt() == null) return 1;
                if (b.getSoldAt() == null) return -1;
                return b.getSoldAt().compareTo(a.getSoldAt());
            });
            if (sales.size() > DEFAULT_LIMIT) {
                sales = new ArrayList<>(sales.subList(0, DEFAULT_LIMIT));
            }
        }

        if (sales.isEmpty()) {
            return List.of();
        }

        Set<String> saleIds = new HashSet<>();
        for (Sale sale : sales) {
            if (isVisibleSale(sale)) {
                saleIds.add(sale.getId());
            }
        }
        if (saleIds.isEmpty()) {
            return List.of();
        }

        Map<String, BigDecimal> debtBySale = new HashMap<>();
        List<CreditTransaction> debts = creditTransactionRepository
                .findByCreditAccountIdAndTxnTypeAndSaleIdIsNotNullOrderByCreatedAtDesc(
                        acc.getId(),
                        CreditTxnTypes.DEBT,
                        PageRequest.of(0, DEFAULT_LIMIT * 2));
        for (CreditTransaction t : debts) {
            if (t.getSaleId() != null && saleIds.contains(t.getSaleId().trim())) {
                debtBySale.merge(t.getSaleId().trim(), scaleMoney(t.getAmount()), BigDecimal::add);
            }
        }

        Map<String, BigDecimal> walletCreditBySale = new HashMap<>();
        for (WalletTransaction w : walletRows) {
            if (w.getSaleId() == null || w.getSaleId().isBlank()) {
                continue;
            }
            String sid = w.getSaleId().trim();
            if (!saleIds.contains(sid)) {
                continue;
            }
            if (WalletTxnTypes.CREDIT_OVERPAY_CHANGE.equals(w.getTxnType())
                    || WalletTxnTypes.CREDIT_REFUND.equals(w.getTxnType())) {
                walletCreditBySale.merge(sid, scaleMoney(w.getAmount()), BigDecimal::add);
            }
        }

        Map<String, List<SaleItem>> itemsBySale = new HashMap<>();
        Set<String> itemIds = new HashSet<>();
        for (String saleId : saleIds) {
            List<SaleItem> lines = saleItemRepository.findBySaleIdOrderByLineIndexAsc(saleId);
            itemsBySale.put(saleId, lines);
            for (SaleItem line : lines) {
                itemIds.add(line.getItemId());
            }
        }

        Map<String, String> itemNames = new HashMap<>();
        if (!itemIds.isEmpty()) {
            for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)) {
                itemNames.put(item.getId(), item.getName() != null && !item.getName().isBlank()
                        ? item.getName().trim()
                        : "Item");
            }
        }

        List<TabPurchaseRowResponse> out = new ArrayList<>();
        for (Sale sale : sales) {
            if (!saleIds.contains(sale.getId())) {
                continue;
            }
            List<SaleItem> saleItems = itemsBySale.getOrDefault(sale.getId(), List.of());
            List<TabPurchaseLineResponse> lines = new ArrayList<>(saleItems.size());
            for (SaleItem si : saleItems) {
                lines.add(new TabPurchaseLineResponse(
                        itemNames.getOrDefault(si.getItemId(), "Item"),
                        scaleQty(si.getQuantity()),
                        scaleUnitPrice(si.getUnitPrice()),
                        scaleMoney(si.getLineTotal())));
            }
            out.add(new TabPurchaseRowResponse(
                    sale.getId(),
                    sale.getReceiptNo(),
                    sale.getSoldAt(),
                    sale.getStatus(),
                    debtBySale.getOrDefault(sale.getId(), BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)),
                    scaleMoney(sale.getGrandTotal()),
                    walletCreditBySale.getOrDefault(
                            sale.getId(), BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)),
                    List.copyOf(lines)));
        }
        return List.copyOf(out);
    }

    private static boolean isVisibleSale(Sale sale) {
        if (sale == null || sale.getStatus() == null) {
            return false;
        }
        String status = sale.getStatus().trim().toLowerCase();
        return SalesConstants.SALE_STATUS_COMPLETED.equals(status)
                || "completed".equals(status);
    }

    private static BigDecimal scaleMoney(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleQty(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        return v.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleUnitPrice(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        return v.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }
}
