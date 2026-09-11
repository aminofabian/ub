package zelisline.ub.till.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.notifications.application.NotificationTemplateRenderer;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.till.api.dto.RegisterTillDeviceRequest;
import zelisline.ub.till.api.dto.TillDeviceResponse;
import zelisline.ub.till.domain.TillAccessRequest;
import zelisline.ub.till.repository.TillAccessRequestRepository;
import zelisline.ub.till.repository.TillDeviceRepository;

@ExtendWith(MockitoExtension.class)
class TillAccessRequestServiceTest {

    private static final String DEVICE_KEY = "device-key-01";

    @Mock private TillAccessRequestRepository tillAccessRequestRepository;
    @Mock private TillDeviceRepository tillDeviceRepository;
    @Mock private TillDeviceService tillDeviceService;
    @Mock private NotificationService notificationService;
    @Mock private NotificationTemplateRenderer templateRenderer;
    @Mock private UserRepository userRepository;
    @Mock private BusinessRepository businessRepository;
    @Mock private BranchRepository branchRepository;

    private TillAccessApprovalToken token;
    private TillAccessRequestService service;

    @BeforeEach
    void setUp() {
        token = new TillAccessApprovalToken("test-secret");
        service = new TillAccessRequestService(
                tillAccessRequestRepository,
                tillDeviceRepository,
                tillDeviceService,
                token,
                notificationService,
                templateRenderer,
                userRepository,
                businessRepository,
                branchRepository,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://shop.kiosk.ke");
    }

    @Test
    void suggestedLabel_usesCashierFirstName() {
        assertThat(TillAccessRequestService.suggestedLabel("MURIKI Wanjiru", DEVICE_KEY))
                .isEqualTo("MURIKI's till");
        assertThat(TillAccessRequestService.suggestedLabel("  ", DEVICE_KEY))
                .startsWith("Till ");
    }

    @Test
    void firstDeny_createsPendingAndNotifiesOwner() {
        User cashier = cashier();
        when(tillAccessRequestRepository.findByBusinessIdAndBranchIdAndDeviceKey("biz", "br", DEVICE_KEY))
                .thenReturn(Optional.empty());
        when(tillAccessRequestRepository.save(any())).thenAnswer(inv -> {
            TillAccessRequest row = inv.getArgument(0);
            if (row.getId() == null || row.getId().isBlank()) {
                row.setId("req-1");
            }
            return row;
        });
        stubNotifyLookups();

        service.recordPinDenied(cashier, "br", DEVICE_KEY, "Safari/18");

        ArgumentCaptor<TillAccessRequest> saved = ArgumentCaptor.forClass(TillAccessRequest.class);
        verify(tillAccessRequestRepository, times(2)).save(saved.capture());
        TillAccessRequest row = saved.getAllValues().get(0);
        assertThat(row.getStatus()).isEqualTo(TillAccessRequest.STATUS_PENDING);
        assertThat(row.getRequestedByUserId()).isEqualTo("cashier-1");
        assertThat(row.getSuggestedLabel()).isEqualTo("MURIKI's till");

        verify(notificationService).tryInsertDedupeForUser(
                eq("biz"),
                eq("owner-1"),
                eq(NotificationTypes.TILL_ACCESS_REQUESTED),
                eq(NotificationTypes.TILL_ACCESS_REQUESTED + ":req-1:owner-1:1"),
                eq("cash_drawer"),
                eq("HIGH"),
                anyString());
    }

    @Test
    void secondDeny_doesNotSpamWhileQuietPeriodHolds() {
        User cashier = cashier();
        TillAccessRequest existing = pendingRow();
        existing.setNotifiedAt(Instant.now());
        existing.setNotifyCount(1);
        when(tillAccessRequestRepository.findByBusinessIdAndBranchIdAndDeviceKey("biz", "br", DEVICE_KEY))
                .thenReturn(Optional.of(existing));
        when(tillAccessRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordPinDenied(cashier, "br", DEVICE_KEY, "Safari/18");

        verify(notificationService, never()).tryInsertDedupeForUser(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void blankDeviceKey_isIgnored() {
        service.recordPinDenied(cashier(), "br", "  ", null);
        verify(tillAccessRequestRepository, never()).save(any());
    }

    @Test
    void approve_registersDeviceAsOwner() {
        TillAccessRequest existing = pendingRow();
        when(tillAccessRequestRepository.findById("req-1")).thenReturn(Optional.of(existing));
        when(tillAccessRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findIdsWithPermission("biz", TillAccessRequestService.APPROVE_PERMISSION))
                .thenReturn(List.of("owner-1"));
        when(tillDeviceService.register(eq("biz"), eq("owner-1"), eq("br"), any(), eq(DEVICE_KEY)))
                .thenReturn(new TillDeviceResponse(
                        "till-1", "br", DEVICE_KEY, "Front counter", "shelf",
                        "owner-1", Instant.now(), null));
        stubShopNames();

        String issued = token.issue("req-1", Instant.now().plusSeconds(3600));
        var review = service.approveByToken(issued, "Front counter");

        assertThat(review.status()).isEqualTo(TillAccessRequest.STATUS_APPROVED);
        assertThat(review.canApprove()).isFalse();
        assertThat(existing.getStatus()).isEqualTo(TillAccessRequest.STATUS_APPROVED);
        assertThat(existing.getResolvedBy()).isEqualTo("owner-1");

        ArgumentCaptor<RegisterTillDeviceRequest> body = ArgumentCaptor.forClass(RegisterTillDeviceRequest.class);
        verify(tillDeviceService).register(eq("biz"), eq("owner-1"), eq("br"), body.capture(), eq(DEVICE_KEY));
        assertThat(body.getValue().label()).isEqualTo("Front counter");
    }

    private void stubNotifyLookups() {
        stubShopNames();
        when(userRepository.findIdsWithPermission("biz", TillAccessRequestService.APPROVE_PERMISSION))
                .thenReturn(List.of("owner-1"));
        when(templateRenderer.render(eq("biz"), eq(NotificationTypes.TILL_ACCESS_REQUESTED), any()))
                .thenReturn(new NotificationTemplateRenderer.RenderedNotification(
                        "MURIKI is waiting at Westlands",
                        "Tap to trust this till",
                        "https://shop.kiosk.ke/tills/review?token=x",
                        "cash_drawer",
                        "HIGH"));
        when(notificationService.tryInsertDedupeForUser(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private void stubShopNames() {
        Business business = new Business();
        business.setId("biz");
        business.setName("Demo Shop");
        when(businessRepository.findById("biz")).thenReturn(Optional.of(business));
        Branch branch = new Branch();
        branch.setId("br");
        branch.setName("Westlands");
        when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull("br", "biz"))
                .thenReturn(Optional.of(branch));
    }

    private static User cashier() {
        User user = new User();
        user.setId("cashier-1");
        user.setBusinessId("biz");
        user.setBranchId("br");
        user.setName("MURIKI Wanjiru");
        user.setEmail("muriki@shop.test");
        return user;
    }

    private static TillAccessRequest pendingRow() {
        TillAccessRequest row = new TillAccessRequest();
        row.setId("req-1");
        row.setBusinessId("biz");
        row.setBranchId("br");
        row.setDeviceKey(DEVICE_KEY);
        row.setRequestedByUserId("cashier-1");
        row.setRequestedByName("MURIKI Wanjiru");
        row.setRequestedByEmail("muriki@shop.test");
        row.setSuggestedLabel("MURIKI's till");
        row.setStatus(TillAccessRequest.STATUS_PENDING);
        row.setLastSeenAt(Instant.now());
        row.setCreatedAt(Instant.now());
        return row;
    }
}
