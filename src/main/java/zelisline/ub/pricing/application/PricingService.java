package zelisline.ub.pricing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.PricingConstants;
import zelisline.ub.pricing.api.dto.BuyingPriceResponse;
import zelisline.ub.pricing.api.dto.PostBuyingPriceRequest;
import zelisline.ub.pricing.api.dto.PostPriceRuleRequest;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.api.dto.PostTaxRateRequest;
import zelisline.ub.pricing.api.dto.PriceRuleResponse;
import zelisline.ub.pricing.api.dto.PutPriceRuleRequest;
import zelisline.ub.pricing.api.dto.SellPriceSuggestionResponse;
import zelisline.ub.pricing.api.dto.SellingPriceResponse;
import zelisline.ub.pricing.api.dto.TaxRateResponse;
import zelisline.ub.pricing.domain.BuyingPrice;
import zelisline.ub.pricing.domain.PriceRule;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.BuyingPriceRepository;
import zelisline.ub.pricing.repository.PriceRuleRepository;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class PricingService {

    private static final int MONEY_SCALE = 2;

    private final SellingPriceRepository sellingPriceRepository;
    private final BuyingPriceRepository buyingPriceRepository;
    private final PriceRuleRepository priceRuleRepository;
    private final TaxRateRepository taxRateRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SellingPriceResponse setSellingPrice(String businessId, PostSellingPriceRequest req, String userId) {
        requireItem(businessId, req.itemId());
        String branchId = blankToNull(req.branchId());
        if (branchId != null) {
            requireBranch(businessId, branchId);
        }
        assertOpenEndedCompatibleForSelling(businessId, req.itemId(), branchId, req.effectiveFrom());
        sellingPriceRepository.closeOpenRowsEffectiveBefore(
                businessId,
                req.itemId(),
                branchId,
                req.effectiveFrom(),
                req.effectiveFrom().minusDays(1)
        );
        SellingPrice row = new SellingPrice();
        row.setBusinessId(businessId);
        row.setItemId(req.itemId());
        row.setBranchId(branchId);
        row.setPrice(req.price().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        row.setEffectiveFrom(req.effectiveFrom());
        row.setSetBy(userId);
        row.setNotes(req.notes());
        sellingPriceRepository.save(row);
        return toSellingDto(row);
    }

    @Transactional
    public BuyingPriceResponse setBuyingPrice(String businessId, PostBuyingPriceRequest req, String userId) {
        requireItem(businessId, req.itemId());
        requireSupplier(businessId, req.supplierId());
        assertOpenEndedCompatibleForBuying(businessId, req.itemId(), req.supplierId(), req.effectiveFrom());
        buyingPriceRepository.closeOpenRowsEffectiveBefore(
                businessId,
                req.itemId(),
                req.supplierId(),
                req.effectiveFrom(),
                req.effectiveFrom().minusDays(1)
        );
        BuyingPrice row = new BuyingPrice();
        row.setBusinessId(businessId);
        row.setItemId(req.itemId());
        row.setSupplierId(req.supplierId());
        row.setUnitCost(req.unitCost().setScale(4, RoundingMode.HALF_UP));
        row.setEffectiveFrom(req.effectiveFrom());
        row.setSourceType(req.sourceType() == null || req.sourceType().isBlank()
                ? PricingConstants.BUYING_SOURCE_MANUAL
                : req.sourceType());
        row.setSetBy(userId);
        row.setNotes(req.notes());
        buyingPriceRepository.save(row);
        return toBuyingDto(row);
    }

    @Transactional
    public PriceRuleResponse createPriceRule(String businessId, PostPriceRuleRequest req) {
        if (priceRuleRepository.existsByBusinessIdAndName(businessId, req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rule name already exists");
        }
        validateRulePayload(req.ruleType(), req.paramsJson());
        PriceRule rule = new PriceRule();
        rule.setBusinessId(businessId);
        rule.setName(req.name());
        rule.setRuleType(req.ruleType());
        rule.setParamsJson(req.paramsJson());
        rule.setActive(req.active());
        priceRuleRepository.save(rule);
        return toRuleDto(rule);
    }

    @Transactional
    public PriceRuleResponse updatePriceRule(String businessId, String ruleId, PutPriceRuleRequest req) {
        PriceRule rule = priceRuleRepository.findByIdAndBusinessId(ruleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (!rule.getName().equals(req.name())
                && priceRuleRepository.existsByBusinessIdAndNameAndIdNot(businessId, req.name(), ruleId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rule name already exists");
        }
        validateRulePayload(req.ruleType(), req.paramsJson());
        rule.setName(req.name());
        rule.setRuleType(req.ruleType());
        rule.setParamsJson(req.paramsJson());
        rule.setActive(req.active());
        priceRuleRepository.save(rule);
        return toRuleDto(rule);
    }

    @Transactional(readOnly = true)
    public List<PriceRuleResponse> listPriceRules(String businessId) {
        return priceRuleRepository.findByBusinessIdOrderByNameAsc(businessId).stream()
                .map(this::toRuleDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public SellPriceSuggestionResponse suggestSellPrice(String businessId, String itemId, String supplierId) {
        requireItem(businessId, itemId);
        String supId = blankToNull(supplierId);
        if (supId != null) {
            requireSupplier(businessId, supId);
        }
        List<BuyingPrice> latest = buyingPriceRepository.findLatestRows(
                businessId,
                itemId,
                supId,
                PageRequest.of(0, 1)
        );
        if (latest.isEmpty()) {
            return new SellPriceSuggestionResponse(null, null, null, null, "No buying price history for this item");
        }
        BigDecimal cost = latest.getFirst().getUnitCost();
        PriceRule marginRule = firstActiveMarginRule(businessId);
        if (marginRule == null) {
            return new SellPriceSuggestionResponse(cost, null, null, null, "No active margin price rule");
        }
        BigDecimal margin = readMarginPercent(marginRule.getParamsJson());
        BigDecimal suggested = cost.multiply(
                BigDecimal.ONE.add(margin.movePointLeft(2)))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new SellPriceSuggestionResponse(cost, margin, marginRule.getName(), suggested, null);
    }

    @Transactional
    public TaxRateResponse createTaxRate(String businessId, PostTaxRateRequest req) {
        TaxRate t = new TaxRate();
        t.setBusinessId(businessId);
        t.setName(req.name());
        t.setRatePercent(req.ratePercent().setScale(3, RoundingMode.HALF_UP));
        t.setInclusive(req.inclusive());
        t.setActive(req.active());
        taxRateRepository.save(t);
        return toTaxDto(t);
    }

    @Transactional(readOnly = true)
    public List<TaxRateResponse> listTaxRates(String businessId) {
        return taxRateRepository.findByBusinessIdAndActiveIsTrueOrderByNameAsc(businessId).stream()
                .map(this::toTaxDto)
                .toList();
    }

    private void assertOpenEndedCompatibleForSelling(
            String businessId,
            String itemId,
            String branchId,
            LocalDate newFrom
    ) {
        for (SellingPrice sp : sellingPriceRepository.findOpenEnded(businessId, itemId, branchId)) {
            if (!sp.getEffectiveFrom().isBefore(newFrom)) {
                if (sp.getEffectiveFrom().equals(newFrom)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Selling price already starts on this date");
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A later open-ended selling price exists");
            }
        }
    }

    private void assertOpenEndedCompatibleForBuying(
            String businessId,
            String itemId,
            String supplierId,
            LocalDate newFrom
    ) {
        for (BuyingPrice bp : buyingPriceRepository.findOpenEnded(businessId, itemId, supplierId)) {
            if (!bp.getEffectiveFrom().isBefore(newFrom)) {
                if (bp.getEffectiveFrom().equals(newFrom)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Buying price already starts on this date");
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A later open-ended buying price exists");
            }
        }
    }

    private PriceRule firstActiveMarginRule(String businessId) {
        for (PriceRule r : priceRuleRepository.findByBusinessIdAndActiveIsTrueOrderByNameAsc(businessId)) {
            if (PricingConstants.RULE_MARGIN_PERCENT.equals(r.getRuleType())) {
                return r;
            }
        }
        return null;
    }

    private void validateRulePayload(String ruleType, String paramsJson) {
        if (!PricingConstants.RULE_MARGIN_PERCENT.equals(ruleType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported rule type");
        }
        readMarginPercent(paramsJson);
    }

    private BigDecimal readMarginPercent(String paramsJson) {
        try {
            JsonNode n = objectMapper.readTree(paramsJson);
            if (!n.has(PricingConstants.PARAM_MARGIN_PERCENT)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paramsJson must include marginPercent");
            }
            BigDecimal m = new BigDecimal(n.get(PricingConstants.PARAM_MARGIN_PERCENT).asText());
            if (m.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "marginPercent must be non-negative");
            }
            return m;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid paramsJson");
        }
    }

    private void requireItem(String businessId, String itemId) {
        itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private void requireSupplier(String businessId, String supplierId) {
        supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier not found"));
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }

    private SellingPriceResponse toSellingDto(SellingPrice sp) {
        return new SellingPriceResponse(
                sp.getId(),
                sp.getItemId(),
                sp.getBranchId(),
                sp.getPrice(),
                sp.getEffectiveFrom(),
                sp.getEffectiveTo()
        );
    }

    private BuyingPriceResponse toBuyingDto(BuyingPrice bp) {
        return new BuyingPriceResponse(
                bp.getId(),
                bp.getItemId(),
                bp.getSupplierId(),
                bp.getUnitCost(),
                bp.getEffectiveFrom(),
                bp.getEffectiveTo()
        );
    }

    private PriceRuleResponse toRuleDto(PriceRule r) {
        return new PriceRuleResponse(r.getId(), r.getName(), r.getRuleType(), r.getParamsJson(), r.isActive());
    }

    private TaxRateResponse toTaxDto(TaxRate t) {
        return new TaxRateResponse(t.getId(), t.getName(), t.getRatePercent(), t.isInclusive());
    }
}
