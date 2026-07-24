package zelisline.ub.notifications.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class InsightsDigestService {

    private static final int ABANDONED_ITEM_PREVIEW_LIMIT = 8;

    private final BusinessRepository businessRepository;
    private final WebCartRepository webCartRepository;
    private final WebOrderRepository webOrderRepository;
    private final MvSalesDailyRepository mvSalesDailyRepository;
    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final ShopperRecipientResolver shopperRecipientResolver;
    private final NotificationOutboxService notificationOutboxService;
    private final ObjectMapper objectMapper;

    @Value("${app.notifications.insights.zone:Africa/Nairobi}")
    private String zoneId;

    @Value("${app.notifications.abandoned-cart.stale-hours:24}")
    private int abandonedCartStaleHours;

    @Value("${app.notifications.win-back.inactive-days:30}")
    private int winBackInactiveDays;

    public void enqueueAbandonedCartDigests() {
        LocalDate reportDay = LocalDate.now(ZoneId.of(zoneId));
        Instant staleBefore = Instant.now().minus(abandonedCartStaleHours, ChronoUnit.HOURS);
        for (Business business : activeBusinesses()) {
            try {
                long count = webCartRepository.countStaleCartsWithItems(business.getId(), staleBefore);
                if (count <= 0) {
                    continue;
                }
                String itemsJson = buildAbandonedItemsJson(business.getId(), staleBefore);
                notificationOutboxService.enqueueAbandonedCartDigest(
                        business.getId(),
                        reportDay.toString(),
                        String.valueOf(count),
                        itemsJson);
            } catch (RuntimeException ex) {
                log.warn("abandoned cart digest enqueue failed businessId={}", business.getId(), ex);
            }
        }
    }

    public void enqueuePeakHoursDigests() {
        LocalDate businessDay = LocalDate.now(ZoneId.of(zoneId)).minusDays(1);
        for (Business business : activeBusinesses()) {
            try {
                List<MvSalesDailyRepository.PeakHourRow> rows =
                        mvSalesDailyRepository.findPeakSalesHourForDay(business.getId(), businessDay);
                if (rows.isEmpty()) {
                    continue;
                }
                MvSalesDailyRepository.PeakHourRow peak = rows.getFirst();
                if (peak.getRevenue() == null || peak.getRevenue().signum() <= 0) {
                    continue;
                }
                String currency = business.getCurrency() != null ? business.getCurrency().trim() : "KES";
                notificationOutboxService.enqueuePeakHoursDigest(
                        business.getId(),
                        businessDay.toString(),
                        peak.getPeakHour() != null ? peak.getPeakHour() : "—",
                        peak.getRevenue().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        currency);
            } catch (RuntimeException ex) {
                log.warn("peak hours digest enqueue failed businessId={}", business.getId(), ex);
            }
        }
    }

    public void enqueueTopProductsDigests() {
        LocalDate weekEnding = LocalDate.now(ZoneId.of(zoneId));
        LocalDate from = weekEnding.minusDays(6);
        for (Business business : activeBusinesses()) {
            try {
                List<MvSalesDailyRepository.ItemRevenue> top = mvSalesDailyRepository.topItemsByRevenue(
                        business.getId(), from, weekEnding, null, null, 5);
                if (top.isEmpty()) {
                    continue;
                }
                List<String> itemIds = top.stream().map(MvSalesDailyRepository.ItemRevenue::getItemId).toList();
                Map<String, String> names = itemRepository
                        .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, business.getId())
                        .stream()
                        .collect(Collectors.toMap(Item::getId, i -> i.getName() != null ? i.getName() : i.getId()));
                String topItems = top.stream()
                        .map(row -> names.getOrDefault(row.getItemId(), row.getItemId()))
                        .limit(3)
                        .collect(Collectors.joining(", "));
                if (topItems.isBlank()) {
                    continue;
                }
                notificationOutboxService.enqueueTopProductsDigest(
                        business.getId(),
                        weekEnding.toString(),
                        topItems);
            } catch (RuntimeException ex) {
                log.warn("top products digest enqueue failed businessId={}", business.getId(), ex);
            }
        }
    }

    public void enqueueWinBackCampaign() {
        Instant cutoff = Instant.now().minus(winBackInactiveDays, ChronoUnit.DAYS);
        LocalDate week = LocalDate.now(ZoneId.of(zoneId));
        String weekKey = week.getYear() + "-W" + week.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        for (Business business : activeBusinesses()) {
            try {
                for (WebOrderRepository.InactiveShopperEmail row :
                        webOrderRepository.findInactiveShopperEmails(business.getId(), cutoff)) {
                    shopperRecipientResolver
                            .resolveBuyerUserId(business.getId(), row.getEmail())
                            .ifPresent(userId -> notificationOutboxService.enqueueWinBack(
                                    business.getId(),
                                    userId,
                                    "win_back:" + business.getId() + ":" + userId + ":" + weekKey));
                }
            } catch (RuntimeException ex) {
                log.warn("win-back enqueue failed businessId={}", business.getId(), ex);
            }
        }
    }

    private String buildAbandonedItemsJson(String businessId, Instant staleBefore) {
        List<WebCartRepository.AbandonedItemRow> rows = webCartRepository.findTopAbandonedItems(
                businessId, staleBefore, ABANDONED_ITEM_PREVIEW_LIMIT);
        if (rows.isEmpty()) {
            return "[]";
        }
        List<String> itemIds = rows.stream().map(WebCartRepository.AbandonedItemRow::getItemId).toList();
        Map<String, Item> itemsById = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
                .stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        Map<String, String> thumbs = firstGalleryUrlByItemIds(itemIds);

        List<Map<String, Object>> previews = new ArrayList<>();
        for (WebCartRepository.AbandonedItemRow row : rows) {
            Item item = itemsById.get(row.getItemId());
            if (item == null) {
                continue;
            }
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("itemId", item.getId());
            preview.put("name", item.getName() != null ? item.getName() : item.getId());
            if (item.getVariantName() != null && !item.getVariantName().isBlank()) {
                preview.put("variantName", item.getVariantName().trim());
            }
            String imageUrl = thumbs.get(item.getId());
            if (imageUrl != null) {
                preview.put("imageUrl", imageUrl);
            }
            BigDecimal qty = row.getTotalQty() != null ? row.getTotalQty() : BigDecimal.ZERO;
            preview.put("quantity", qty.stripTrailingZeros().toPlainString());
            preview.put("cartCount", row.getCartCount() != null ? row.getCartCount().longValue() : 0L);
            previews.add(preview);
        }
        try {
            return objectMapper.writeValueAsString(previews);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize abandoned cart item previews", e);
            return "[]";
        }
    }

    private Map<String, String> firstGalleryUrlByItemIds(List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Sort galleryOrder = Sort.by(
                Sort.Order.asc("itemId"), Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
        List<ItemImage> rows = itemImageRepository.findByItemIdIn(itemIds, galleryOrder);
        Map<String, String> out = new LinkedHashMap<>();
        for (ItemImage img : rows) {
            String url = resolveImagePublicUrl(img);
            if (url == null) {
                continue;
            }
            out.putIfAbsent(img.getItemId(), url);
        }
        return out;
    }

    private static String resolveImagePublicUrl(ItemImage img) {
        String secure = img.getSecureUrl();
        if (secure != null && !secure.isBlank()) {
            return secure.trim();
        }
        String key = img.getS3Key();
        if (key != null) {
            String k = key.trim();
            if (k.startsWith("http://") || k.startsWith("https://")) {
                return k;
            }
        }
        return null;
    }

    private List<Business> activeBusinesses() {
        return businessRepository.findByDeletedAtIsNull(PageRequest.of(0, 200)).getContent().stream()
                .filter(b -> b.getTenantStatus() != TenantStatus.SUSPENDED
                        && b.getTenantStatus() != TenantStatus.INACTIVE)
                .toList();
    }
}
