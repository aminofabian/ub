package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ItemTaxResolver;
import zelisline.ub.sales.domain.SaleItem;

/**
 * Stamps a tax breakdown onto built sale lines and returns the sale's total tax.
 *
 * <p>Sale prices are treated as tax-inclusive (the Kenyan retail norm), so tax
 * is <em>extracted</em> from each line's gross total: {@code net + tax = gross}.
 * This never changes the amount the customer pays — it only records how much of
 * a completed sale is tax, which is what a KRA eTIMS invoice must report.
 *
 * <p>The ledger treatment (splitting revenue into net revenue + VAT payable) is a
 * deliberate follow-up: it changes revenue reporting, so it is not done here.
 */
@Service
@RequiredArgsConstructor
public class SaleTaxService {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ItemTaxResolver itemTaxResolver;

    /**
     * Resolve and stamp tax on each line that maps to a taxed item, returning the
     * sum of the line tax amounts. Lines without an item (airtime) are skipped.
     */
    public BigDecimal applyTax(String businessId, List<SaleItem> items) {
        Map<String, ItemTaxResolver.ResolvedTax> cache = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SaleItem row : items) {
            String itemId = row.getItemId();
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            ItemTaxResolver.ResolvedTax tax = cache.get(itemId);
            if (tax == null && !cache.containsKey(itemId)) {
                tax = itemTaxResolver.resolve(businessId, itemId);
                cache.put(itemId, tax);
            }
            if (tax == null) {
                continue;
            }

            BigDecimal gross = row.getLineTotal();
            BigDecimal taxAmount = extractTax(gross, tax.ratePercent());
            row.setTaxRateId(tax.taxRateId());
            row.setTaxRatePercent(tax.ratePercent());
            row.setTaxCode(tax.taxCode());
            row.setTaxAmount(taxAmount);
            total = total.add(taxAmount);
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Tax contained in a tax-inclusive gross amount. A zero, negative, or absent
     * rate yields no tax (net = gross).
     */
    static BigDecimal extractTax(BigDecimal gross, BigDecimal ratePercent) {
        if (gross == null || ratePercent == null || ratePercent.signum() <= 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal divisor = BigDecimal.ONE.add(ratePercent.divide(HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal net = gross.divide(divisor, MONEY_SCALE, RoundingMode.HALF_UP);
        return gross.subtract(net).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
