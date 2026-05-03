package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.EffectivePricingContextResponse;
import zelisline.ub.catalog.api.dto.LinkedPriceRuleRef;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.CategoryPriceRule;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryPriceRuleRepository;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.domain.PriceRule;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.PriceRuleRepository;
import zelisline.ub.pricing.repository.TaxRateRepository;

@Service
@RequiredArgsConstructor
public class CategoryPricingResolutionService {

    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final TaxRateRepository taxRateRepository;
    private final PriceRuleRepository priceRuleRepository;
    private final CategoryPriceRuleRepository categoryPriceRuleRepository;

    @Transactional(readOnly = true)
    public EffectivePricingContextResponse resolve(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        String leafCatId = item.getCategoryId();
        if (leafCatId == null || leafCatId.isBlank()) {
            return new EffectivePricingContextResponse(
                    item.getId(),
                    null,
                    null,
                    null,
                    "none",
                    null,
                    null,
                    null,
                    null,
                    "none",
                    List.of());
        }

        Map<String, Category> categoryById = new LinkedHashMap<>();
        for (Category c : categoryRepository.findByBusinessIdOrderByPositionAsc(businessId)) {
            categoryById.put(c.getId(), c);
        }

        List<Category> leafFirstChain = ancestorsLeafFirst(leafCatId, categoryById);
        Category leaf = leafFirstChain.isEmpty() ? null : leafFirstChain.get(0);

        BigDecimal markup = null;
        String markupSource = "none";
        String taxRateId = null;
        String taxSource = "none";
        for (Category c : leafFirstChain) {
            if (markup == null && c.getDefaultMarkupPct() != null) {
                markup = c.getDefaultMarkupPct();
                markupSource = "category:" + c.getId();
            }
            if (taxRateId == null && c.getDefaultTaxRateId() != null && !c.getDefaultTaxRateId().isBlank()) {
                taxRateId = c.getDefaultTaxRateId().trim();
                taxSource = "category:" + c.getId();
            }
            if (markup != null && taxRateId != null) {
                break;
            }
        }

        TaxRate tax = null;
        if (taxRateId != null) {
            tax = taxRateRepository.findByIdAndBusinessId(taxRateId, businessId).orElse(null);
        }

        List<CategoryPriceRule> ruleRows = categoryPriceRuleRepository.findByCategoryOrdered(leafCatId);
        List<LinkedPriceRuleRef> refs = new ArrayList<>();
        for (CategoryPriceRule row : ruleRows) {
            String rid = row.getId().getPriceRuleId();
            PriceRule pr = priceRuleRepository.findByIdAndBusinessId(rid, businessId).orElse(null);
            String name = pr != null ? pr.getName() : rid;
            refs.add(new LinkedPriceRuleRef(rid, name, row.getPrecedence()));
        }

        return new EffectivePricingContextResponse(
                item.getId(),
                leaf != null ? leaf.getId() : leafCatId,
                leaf != null ? leaf.getName() : null,
                markup,
                markupSource,
                tax != null ? tax.getId() : null,
                tax != null ? tax.getName() : null,
                tax != null ? tax.getRatePercent() : null,
                tax != null ? tax.isInclusive() : null,
                taxSource,
                refs);
    }

    private static List<Category> ancestorsLeafFirst(String leafId, Map<String, Category> byId) {
        List<Category> chain = new ArrayList<>();
        String cur = leafId;
        int guard = 0;
        while (cur != null && guard++ < 128) {
            Category c = byId.get(cur);
            if (c == null) {
                break;
            }
            chain.add(c);
            cur = c.getParentId();
        }
        return chain;
    }
}
