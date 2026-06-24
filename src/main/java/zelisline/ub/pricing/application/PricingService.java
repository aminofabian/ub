package zelisline.ub.pricing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.catalog.domain.Item;
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
import zelisline.ub.notifications.application.CatalogNotificationListener;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

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
    private final ApplicationEventPublisher eventPublisher;
    private final BusinessRepository businessRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /**
     * Keep the pricing module's active selling price aligned with catalog shelf ({@code bundlePrice}).
     * Storefront, POS, and cart resolve the open selling price before falling back to {@code bundlePrice}.
     */
    @Transactional
    public void syncSellingPriceFromBundle(
            String businessId,
            String itemId,
            BigDecimal bundlePrice,
            String actorUserId
    ) {
        requireItem(businessId, itemId);
        LocalDate today = LocalDate.now();
        BigDecimal priorPrice = getCurrentOpenSellingPrice(businessId, itemId, null);
        sellingPriceRepository.closeAllOpenRowsForItem(businessId, itemId, today);
        if (bundlePrice == null || bundlePrice.signum() <= 0) {
            return;
        }
        BigDecimal price = bundlePrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (price.compareTo(new BigDecimal("0.01")) < 0) {
            return;
        }
        String setBy = actorUserId != null && !actorUserId.isBlank() ? actorUserId : "system";
        setSellingPrice(
                businessId,
                new PostSellingPriceRequest(
                        itemId,
                        null,
                        price,
                        today,
                        "catalog shelf price"),
                setBy,
                priorPrice);
    }

    @Transactional
    public SellingPriceResponse setSellingPrice(String businessId, PostSellingPriceRequest req, String userId) {
        return setSellingPrice(businessId, req, userId, null);
    }

    @Transactional
    public SellingPriceResponse setSellingPrice(
            String businessId,
            PostSellingPriceRequest req,
            String userId,
            BigDecimal priorPriceHint
    ) {
        requireItem(businessId, req.itemId());
        String branchId = blankToNull(req.branchId());
        if (branchId != null) {
            requireBranch(businessId, branchId);
        }
        BigDecimal priorForEvent = priorPriceHint != null
                ? priorPriceHint
                : resolvePriorSellPriceForEvent(businessId, req.itemId(), branchId);
        // If an open-ended row already exists for this date, update its price instead of throwing
        for (SellingPrice existing : sellingPriceRepository.findOpenEnded(businessId, req.itemId(), branchId)) {
            if (existing.getEffectiveFrom().equals(req.effectiveFrom())) {
                BigDecimal oldPrice = existing.getPrice();
                existing.setPrice(req.price().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                if (req.notes() != null && !req.notes().isBlank()) {
                    existing.setNotes(req.notes());
                }
                existing.setSetBy(userId);
                sellingPriceRepository.save(existing);
                eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.PriceChangedEvent(
                        businessId, branchId, req.itemId(), itemName(businessId, req.itemId()),
                        oldPrice, req.price()));
                publishPriceEvent(businessId, req.itemId(), branchId, userId,
                        AuditEventTypes.SELLING_PRICE_CHANGED, oldPrice, req.price());
                maybePublishSubscriberPriceDrop(businessId, req.itemId(), oldPrice, req.price());
                return toSellingDto(existing);
            }
            if (!existing.getEffectiveFrom().isBefore(req.effectiveFrom())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A later open-ended selling price exists");
            }
        }
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
        BigDecimal prior = priorForEvent != null ? priorForEvent : BigDecimal.ZERO;
        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.PriceChangedEvent(
                businessId, branchId, req.itemId(), itemName(businessId, req.itemId()),
                prior, req.price()));
        publishPriceEvent(businessId, req.itemId(), branchId, userId,
                AuditEventTypes.SELLING_PRICE_CHANGED, prior, req.price());
        maybePublishSubscriberPriceDrop(businessId, req.itemId(), prior, req.price());
        return toSellingDto(row);
    }

    private void maybePublishSubscriberPriceDrop(
            String businessId,
            String itemId,
            BigDecimal oldPrice,
            BigDecimal newPrice
    ) {
        if (newPrice.compareTo(oldPrice) >= 0) {
            return;
        }
        String currency = businessRepository.findById(businessId)
                .map(Business::getCurrency)
                .map(String::trim)
                .filter(c -> !c.isBlank())
                .orElse("KES");
        eventPublisher.publishEvent(new CatalogNotificationListener.PriceDropForSubscribersEvent(
                businessId,
                itemId,
                itemName(businessId, itemId),
                oldPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString(),
                newPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString(),
                currency));
    }

    @Transactional
    public BuyingPriceResponse setBuyingPrice(String businessId, PostBuyingPriceRequest req, String userId) {
        requireItem(businessId, req.itemId());
        requireSupplier(businessId, req.supplierId());
        BigDecimal priorCost = resolvePriorBuyingPriceForEvent(businessId, req.itemId(), req.supplierId());
        // If an open-ended row already exists for this date, update it instead of throwing
        for (BuyingPrice existing : buyingPriceRepository.findOpenEnded(businessId, req.itemId(), req.supplierId())) {
            if (existing.getEffectiveFrom().equals(req.effectiveFrom())) {
                BigDecimal oldCost = existing.getUnitCost();
                existing.setUnitCost(req.unitCost().setScale(4, RoundingMode.HALF_UP));
                if (req.notes() != null && !req.notes().isBlank()) {
                    existing.setNotes(req.notes());
                }
                existing.setSetBy(userId);
                buyingPriceRepository.save(existing);
                publishPriceEvent(businessId, req.itemId(), null, userId,
                        AuditEventTypes.BUYING_PRICE_CHANGED, oldCost, req.unitCost());
                return toBuyingDto(existing);
            }
            if (!existing.getEffectiveFrom().isBefore(req.effectiveFrom())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A later open-ended buying price exists");
            }
        }
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
        publishPriceEvent(businessId, req.itemId(), null, userId,
                AuditEventTypes.BUYING_PRICE_CHANGED, priorCost, req.unitCost());
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
    public SellPriceSuggestionResponse suggestSellPrice(
            String businessId,
            String itemId,
            String supplierId,
            String branchId,
            BigDecimal draftUnitCost
    ) {
        requireItem(businessId, itemId);
        String supId = blankToNull(supplierId);
        if (supId != null) {
            requireSupplier(businessId, supId);
        }
        String brId = blankToNull(branchId);
        if (brId != null) {
            requireBranch(businessId, brId);
        }
        BigDecimal currentSell = resolveCurrentOpenSellPrice(businessId, itemId, brId);
        BigDecimal costFromDraft = draftUnitCost == null ? null : draftUnitCost.setScale(4, RoundingMode.HALF_UP);
        if (costFromDraft != null && costFromDraft.signum() <= 0) {
            costFromDraft = null;
        }
        List<BuyingPrice> latest = buyingPriceRepository.findLatestRows(
                businessId,
                itemId,
                supId,
                PageRequest.of(0, 1)
        );
        BigDecimal cost = costFromDraft;
        if (cost == null && !latest.isEmpty()) {
            cost = latest.getFirst().getUnitCost();
        }
        if (cost == null) {
            return new SellPriceSuggestionResponse(
                    null, null, null, null, "No buying price history for this item", currentSell);
        }
        PriceRule marginRule = firstActiveMarginRule(businessId);
        if (marginRule == null) {
            return new SellPriceSuggestionResponse(
                    cost, null, null, null, "No active margin price rule", currentSell);
        }
        BigDecimal margin = readMarginPercent(marginRule.getParamsJson());
        BigDecimal rawSuggested = cost.multiply(
                BigDecimal.ONE.add(margin.movePointLeft(2)))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal suggested = SuggestedSellPriceRounding.round(rawSuggested);
        return new SellPriceSuggestionResponse(cost, margin, marginRule.getName(), suggested, null, currentSell);
    }

    /**
     * Active open-ended selling price (shelf price) for the item, optionally scoped to a branch.
     * When {@code branchId} is set, resolves the branch-specific open row first, then falls back to a
     * business-wide row ({@code branch_id} null) so POS still sees a price after "Pick branch".
     * If no open {@link SellingPrice} row exists, falls back to the item's {@code bundlePrice} when
     * that field is set (products UI uses it as quick "shelf" price).
     * Returns {@code null} when no price can be resolved.
     */
    @Transactional(readOnly = true)
    public BigDecimal getCurrentOpenSellingPrice(String businessId, String itemId, String branchId) {
        requireItem(businessId, itemId);
        String brId = blankToNull(branchId);
        if (brId != null) {
            requireBranch(businessId, brId);
            BigDecimal atBranch = resolveCurrentOpenSellPrice(businessId, itemId, brId);
            if (atBranch != null) {
                return atBranch;
            }
        }
        BigDecimal businessWide = resolveCurrentOpenSellPrice(businessId, itemId, null);
        if (businessWide != null) {
            return businessWide;
        }
        return itemBundlePriceFallback(businessId, itemId);
    }

    /**
     * Batch equivalent of {@link #getCurrentOpenSellingPrice}: for each item id, prefers an open-ended
     * selling price on {@code branchId}, then a business-wide row ({@code branch_id} null), then the
     * item's {@code bundlePrice}.
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> getCurrentOpenSellingPricesForItems(
            String businessId,
            String branchId,
            Collection<String> itemIds
    ) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = itemIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> out = new HashMap<>();
        String brId = blankToNull(branchId);
        if (brId != null) {
            requireBranch(businessId, brId);
            List<SellingPrice> atBranch = sellingPriceRepository.findOpenEndedForBranchAndItemIds(
                    businessId, brId, ids);
            for (SellingPrice sp : atBranch) {
                out.putIfAbsent(sp.getItemId(), sp.getPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            }
        }
        List<String> missingBranch = ids.stream().filter(id -> !out.containsKey(id)).toList();
        if (!missingBranch.isEmpty()) {
            List<SellingPrice> wide =
                    sellingPriceRepository.findOpenEndedBusinessWideForItemIds(businessId, missingBranch);
            for (SellingPrice sp : wide) {
                out.putIfAbsent(sp.getItemId(), sp.getPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            }
        }
        List<String> missingSellingRows = ids.stream().filter(id -> !out.containsKey(id)).toList();
        if (!missingSellingRows.isEmpty()) {
            List<Item> rows =
                    itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(missingSellingRows, businessId);
            for (Item it : rows) {
                BigDecimal bp = it.getBundlePrice();
                if (bp != null && bp.signum() > 0) {
                    out.putIfAbsent(it.getId(), bp.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                }
            }
        }
        return out;
    }

    /**
     * For each item id, returns the most recent buying price (latest effectiveFrom,
     * tie-broken by id desc) across all suppliers. Returns empty map when none exist.
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> getLatestBuyingPricesForItems(
            String businessId,
            Collection<String> itemIds
    ) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = itemIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<BuyingPrice> rows = buyingPriceRepository.findForItems(businessId, ids);
        Map<String, BigDecimal> out = new HashMap<>();
        for (BuyingPrice bp : rows) {
            out.putIfAbsent(bp.getItemId(), bp.getUnitCost());
        }
        return out;
    }

    private BigDecimal itemBundlePriceFallback(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .map(row -> {
                    BigDecimal bp = row.getBundlePrice();
                    if (bp == null || bp.signum() <= 0) {
                        return null;
                    }
                    return bp.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                })
                .orElse(null);
    }

    private BigDecimal resolveCurrentOpenSellPrice(String businessId, String itemId, String branchId) {
        List<SellingPrice> open = sellingPriceRepository.findOpenEnded(businessId, itemId, branchId);
        if (open.isEmpty()) {
            return null;
        }
        return open.getFirst().getPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal resolvePriorSellPriceForEvent(String businessId, String itemId, String branchId) {
        return getCurrentOpenSellingPrice(businessId, itemId, branchId);
    }

    private BigDecimal resolvePriorBuyingPriceForEvent(String businessId, String itemId, String supplierId) {
        List<BuyingPrice> open = buyingPriceRepository.findOpenEnded(businessId, itemId, supplierId);
        if (open.isEmpty()) {
            return null;
        }
        return open.getFirst().getUnitCost();
    }

    private void publishPriceEvent(String businessId, String itemId, String branchId, String userId,
                                   String eventType, BigDecimal oldPrice, BigDecimal newPrice) {
        AuditEventActorType actorType = userId != null && !userId.isBlank()
                ? AuditEventActorType.USER
                : AuditEventActorType.SYSTEM;
        String label = eventType + " " + itemName(businessId, itemId);
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.PRODUCTS, eventType, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(branchId)
                .actor(userId, actorType)
                .target("item", itemId)
                .targetLabel(label)
                .source("web_admin")
                .oldState(map("price", oldPrice != null ? oldPrice.toPlainString() : null))
                .newState(map("price", newPrice != null ? newPrice.toPlainString() : null))
                .diff(map("price", map("old", oldPrice != null ? oldPrice.toPlainString() : null,
                        "new", newPrice != null ? newPrice.toPlainString() : null)))
                .build());
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
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

    private String itemName(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .map(Item::getName).orElse(itemId);
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
