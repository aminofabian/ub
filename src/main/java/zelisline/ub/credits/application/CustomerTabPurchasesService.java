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
import zelisline.ub.credits.api.dto.TabPurchaseLineResponse;
import zelisline.ub.credits.api.dto.TabPurchaseRowResponse;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditTransaction;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerRepository;
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
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final ItemRepository itemRepository;

    @Transactional(readOnly = true)
    public List<TabPurchaseRowResponse> list(String businessId, String customerId) {
        customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit profile not found"));

        List<CreditTransaction> debts = creditTransactionRepository
                .findByCreditAccountIdAndTxnTypeAndSaleIdIsNotNullOrderByCreatedAtDesc(
                        acc.getId(),
                        CreditTxnTypes.DEBT,
                        PageRequest.of(0, DEFAULT_LIMIT));
        if (debts.isEmpty()) {
            return List.of();
        }

        Set<String> saleIds = new HashSet<>();
        for (CreditTransaction t : debts) {
            if (t.getSaleId() != null && !t.getSaleId().isBlank()) {
                saleIds.add(t.getSaleId().trim());
            }
        }

        Map<String, Sale> salesById = new HashMap<>();
        for (Sale sale : saleRepository.findAllById(saleIds)) {
            if (businessId.equals(sale.getBusinessId())) {
                salesById.put(sale.getId(), sale);
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
        for (CreditTransaction debt : debts) {
            String saleId = debt.getSaleId();
            if (saleId == null || saleId.isBlank()) {
                continue;
            }
            Sale sale = salesById.get(saleId.trim());
            if (sale == null) {
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
                    scaleMoney(debt.getAmount()),
                    scaleMoney(sale.getGrandTotal()),
                    List.copyOf(lines)));
        }
        return List.copyOf(out);
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
