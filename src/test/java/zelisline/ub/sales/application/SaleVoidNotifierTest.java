package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.notifications.application.NotificationTemplateRenderer;
import zelisline.ub.notifications.application.NotificationTemplateRenderer.RenderedNotification;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

class SaleVoidNotifierTest {

    private static final String BUSINESS = "biz-1";
    private static final String SALE_ID = "sale-1";
    private static final String CASHIER = "cashier-1";
    private static final String OWNER = "owner-1";
    private static final String ADMIN = "admin-1";

    private final NotificationService notifications = mock(NotificationService.class);
    private final NotificationTemplateRenderer templates = mock(NotificationTemplateRenderer.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final SaleActorNameService names = mock(SaleActorNameService.class);

    private final SaleVoidNotifier notifier = new SaleVoidNotifier(
            notifications,
            templates,
            users,
            businesses,
            names,
            new ObjectMapper());

    @BeforeEach
    void templateEchoesTheVariables() {
        when(templates.render(eq(BUSINESS), eq(NotificationTypes.SALE_VOIDED), any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            Map<String, String> vars = call.getArgument(2);
            return new RenderedNotification(
                    "Sale voided",
                    vars.get("voidedByName") + " voided sale " + vars.get("receiptLabel")
                            + " · " + vars.get("currency") + " " + vars.get("amount")
                            + vars.get("reasonSuffix"),
                    SaleVoidNotifier.ACTIVITY_PATH,
                    "sales",
                    "HIGH");
        });
        Business shop = new Business();
        shop.setCurrency("KES");
        when(businesses.findById(BUSINESS)).thenReturn(Optional.of(shop));
        when(names.resolveSoldByName(BUSINESS, CASHIER)).thenReturn("Faith");
    }

    @Test
    void tellsOwnerAndAdminAndLeavesOutThePersonWhoVoided() throws Exception {
        when(users.findActiveByRoleKeyOrderByCreatedAtAsc(BUSINESS, IdentityService.OWNER_ROLE_KEY))
                .thenReturn(List.of(user(OWNER), user(CASHIER)));
        when(users.findActiveByRoleKeyOrderByCreatedAtAsc(BUSINESS, IdentityService.ADMIN_ROLE_KEY))
                .thenReturn(List.of(user(ADMIN)));

        notifier.notifyVoided(BUSINESS, sale(), CASHIER, "customer left");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notifications).tryInsertDedupeForUser(
                eq(BUSINESS),
                eq(OWNER),
                eq(NotificationTypes.SALE_VOIDED),
                eq(NotificationTypes.SALE_VOIDED + ":" + SALE_ID + ":" + OWNER),
                eq("sales"),
                eq("HIGH"),
                payload.capture());
        verify(notifications).tryInsertDedupeForUser(
                eq(BUSINESS),
                eq(ADMIN),
                eq(NotificationTypes.SALE_VOIDED),
                eq(NotificationTypes.SALE_VOIDED + ":" + SALE_ID + ":" + ADMIN),
                eq("sales"),
                eq("HIGH"),
                any());
        verify(notifications, never()).tryInsertDedupeForUser(
                eq(BUSINESS),
                eq(CASHIER),
                any(),
                any(),
                any(),
                any(),
                any());

        assertThat(payload.getValue()).contains("Faith voided sale #1563");
        assertThat(payload.getValue()).contains("KES 1450.00");
        assertThat(payload.getValue()).contains("Reason: customer left");
        assertThat(payload.getValue()).contains(SaleVoidNotifier.ACTIVITY_PATH);
    }

    @Test
    void staysQuietWhenTheOnlyOwnerVoidedIt() {
        when(users.findActiveByRoleKeyOrderByCreatedAtAsc(BUSINESS, IdentityService.OWNER_ROLE_KEY))
                .thenReturn(List.of(user(OWNER)));
        when(users.findActiveByRoleKeyOrderByCreatedAtAsc(BUSINESS, IdentityService.ADMIN_ROLE_KEY))
                .thenReturn(List.of());

        notifier.notifyVoided(BUSINESS, sale(), OWNER, null);

        verify(notifications, never()).tryInsertDedupeForUser(
                any(), any(), any(), any(), any(), any(), any());
    }

    private static Sale sale() {
        Sale sale = new Sale();
        sale.setId(SALE_ID);
        sale.setReceiptNo(1563L);
        sale.setGrandTotal(new BigDecimal("1450.00"));
        return sale;
    }

    private static User user(String id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
