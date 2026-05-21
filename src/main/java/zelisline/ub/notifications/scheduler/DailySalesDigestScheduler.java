package zelisline.ub.notifications.scheduler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.notifications.daily-digest.enabled", havingValue = "true")
public class DailySalesDigestScheduler {

    private final BusinessRepository businessRepository;
    private final MvSalesDailyRepository mvSalesDailyRepository;
    private final NotificationOutboxService notificationOutboxService;

    @Value("${app.notifications.daily-digest.zone:Africa/Nairobi}")
    private String zoneId;

    @Scheduled(
            cron = "${app.notifications.daily-digest.cron:0 0 20 * * *}",
            zone = "${app.notifications.daily-digest.zone:Africa/Nairobi}"
    )
    public void enqueueDigests() {
        LocalDate businessDay = LocalDate.now(ZoneId.of(zoneId)).minusDays(1);
        var page = businessRepository.findByDeletedAtIsNull(PageRequest.of(0, 200));
        for (Business business : page.getContent()) {
            if (business.getTenantStatus() == TenantStatus.SUSPENDED
                    || business.getTenantStatus() == TenantStatus.INACTIVE) {
                continue;
            }
            try {
                BigDecimal revenue = mvSalesDailyRepository.sumRevenueForBusinessDay(
                        business.getId(), businessDay);
                if (revenue == null || revenue.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String currency = business.getCurrency() != null ? business.getCurrency().trim() : "KES";
                notificationOutboxService.enqueueDailySalesDigest(
                        business.getId(),
                        businessDay.toString(),
                        revenue.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        currency);
            } catch (RuntimeException ex) {
                log.warn("daily sales digest enqueue failed businessId={}", business.getId(), ex);
            }
        }
    }
}
