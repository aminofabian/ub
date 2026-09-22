package zelisline.ub.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.repository.NotificationRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class InsightsDigestServiceAbandonedCartTest {

    @Mock BusinessRepository businessRepository;
    @Mock WebCartRepository webCartRepository;
    @Mock WebOrderRepository webOrderRepository;
    @Mock MvSalesDailyRepository mvSalesDailyRepository;
    @Mock ItemRepository itemRepository;
    @Mock ItemImageRepository itemImageRepository;
    @Mock ShopperRecipientResolver shopperRecipientResolver;
    @Mock NotificationOutboxService notificationOutboxService;
    @Mock NotificationRepository notificationRepository;
    @Mock ObjectMapper objectMapper;

    @InjectMocks InsightsDigestService service;

    private final Instant streakSince = Instant.now().minus(7, ChronoUnit.DAYS);

    @Test
    void allowsFirstAbandonedCartEmail() {
        when(notificationRepository.countByBusinessIdAndTypeAndCreatedAtGreaterThanEqual(
                        eq("biz"), eq(NotificationTypes.ABANDONED_CART), any()))
                .thenReturn(0L);
        when(notificationRepository.findFirstByBusinessIdAndTypeOrderByCreatedAtDesc(
                        "biz", NotificationTypes.ABANDONED_CART))
                .thenReturn(null);

        assertThat(service.shouldSendAbandonedCartDigest("biz", streakSince, 2, 3)).isTrue();
    }

    @Test
    void waitsTwoDaysBetweenEmails() {
        Notification last = new Notification();
        last.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(notificationRepository.countByBusinessIdAndTypeAndCreatedAtGreaterThanEqual(
                        eq("biz"), eq(NotificationTypes.ABANDONED_CART), any()))
                .thenReturn(1L);
        when(notificationRepository.findFirstByBusinessIdAndTypeOrderByCreatedAtDesc(
                        "biz", NotificationTypes.ABANDONED_CART))
                .thenReturn(last);

        assertThat(service.shouldSendAbandonedCartDigest("biz", streakSince, 2, 3)).isFalse();
    }

    @Test
    void stopsAfterThreeAttempts() {
        Notification last = new Notification();
        last.setCreatedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        when(notificationRepository.countByBusinessIdAndTypeAndCreatedAtGreaterThanEqual(
                        eq("biz"), eq(NotificationTypes.ABANDONED_CART), any()))
                .thenReturn(3L);
        when(notificationRepository.findFirstByBusinessIdAndTypeOrderByCreatedAtDesc(
                        "biz", NotificationTypes.ABANDONED_CART))
                .thenReturn(last);

        assertThat(service.shouldSendAbandonedCartDigest("biz", streakSince, 2, 3)).isFalse();
    }

    @Test
    void allowsThirdAttemptAfterInterval() {
        Notification last = new Notification();
        last.setCreatedAt(Instant.now().minus(2, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS));
        when(notificationRepository.countByBusinessIdAndTypeAndCreatedAtGreaterThanEqual(
                        eq("biz"), eq(NotificationTypes.ABANDONED_CART), any()))
                .thenReturn(2L);
        when(notificationRepository.findFirstByBusinessIdAndTypeOrderByCreatedAtDesc(
                        "biz", NotificationTypes.ABANDONED_CART))
                .thenReturn(last);

        assertThat(service.shouldSendAbandonedCartDigest("biz", streakSince, 2, 3)).isTrue();
    }
}
