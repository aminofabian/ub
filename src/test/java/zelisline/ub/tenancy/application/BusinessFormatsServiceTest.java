package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

class BusinessFormatsServiceTest {

    private static final String BIZ = "biz-1";

    private TenancyService tenancy;
    private OrderTicketService orders;
    private BranchRepository branches;
    private BusinessFormatsService service;

    @BeforeEach
    void setUp() {
        tenancy = mock(TenancyService.class);
        orders = mock(OrderTicketService.class);
        branches = mock(BranchRepository.class);
        service = new BusinessFormatsService(tenancy, orders, branches);

        Branch branch = new Branch();
        branch.setId("branch-1");
        when(branches.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(BIZ)).thenReturn(List.of(branch));
        when(orders.openOrders(eq(BIZ), anyString())).thenReturn(List.of());
        when(tenancy.changeStoreTypesForTenant(eq(BIZ), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    private static OrderTicketService.Order order(String channel) {
        return new OrderTicketService.Order(
                "o1", "branch-1", 1L, null, channel, Instant.now(), List.of(), "PENDING", null, null, null,
                null, null, null, null, null, null, null, null, null, 0L, BigDecimal.ZERO, null, null, null,
                null, BigDecimal.ZERO, null, null, null, null);
    }

    @Test
    void addingAFormatJustWidensTheSet() {
        when(tenancy.currentStoreTypesForTenant(BIZ)).thenReturn(List.of("restaurant"));

        List<String> result = service.change(BIZ, List.of("restaurant", "bar", "lodging"));

        assertThat(result).containsExactly("restaurant", "bar", "lodging");
        verify(tenancy).changeStoreTypesForTenant(BIZ, List.of("restaurant", "bar", "lodging"));
    }

    @Test
    void removingFoodWithOpenOrdersIsBlocked() {
        when(tenancy.currentStoreTypesForTenant(BIZ)).thenReturn(List.of("restaurant"));
        when(orders.openOrders(BIZ, "branch-1")).thenReturn(List.of(order(OrderTicketService.CHANNEL_DINE_IN)));

        // Trying to drop the only food format while a dine-in order is open.
        assertThatThrownBy(() -> service.change(BIZ, List.of("mini-mart")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("open orders on the floor");
        verify(tenancy, never()).changeStoreTypesForTenant(anyString(), any());
    }

    @Test
    void droppingBarButKeepingRestaurantIsAllowedEvenWithOpenOrders() {
        when(tenancy.currentStoreTypesForTenant(BIZ)).thenReturn(List.of("restaurant", "bar"));
        when(orders.openOrders(BIZ, "branch-1")).thenReturn(List.of(order(OrderTicketService.CHANNEL_DINE_IN)));

        // A food format stays (restaurant), so the floor is not pulled out — removing bar is fine.
        List<String> result = service.change(BIZ, List.of("restaurant"));

        assertThat(result).containsExactly("restaurant");
        verify(tenancy).changeStoreTypesForTenant(BIZ, List.of("restaurant"));
    }

    @Test
    void removingLodgingWithGuestsCheckedInIsBlocked() {
        when(tenancy.currentStoreTypesForTenant(BIZ)).thenReturn(List.of("bar", "lodging"));
        when(orders.openOrders(BIZ, "branch-1")).thenReturn(List.of(order(OrderTicketService.CHANNEL_LODGING)));

        assertThatThrownBy(() -> service.change(BIZ, List.of("bar")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("guests still checked in");
    }

    @Test
    void removingLodgingWithNoStaysIsAllowed() {
        when(tenancy.currentStoreTypesForTenant(BIZ)).thenReturn(List.of("bar", "lodging"));
        // Only a food order is open, no lodging stay.
        when(orders.openOrders(BIZ, "branch-1")).thenReturn(List.of(order(OrderTicketService.CHANNEL_DINE_IN)));

        List<String> result = service.change(BIZ, List.of("bar"));

        assertThat(result).containsExactly("bar");
    }

    @Test
    void anEmptySetIsRejected() {
        when(tenancy.currentStoreTypesForTenant(BIZ)).thenReturn(List.of("restaurant"));

        assertThatThrownBy(() -> service.change(BIZ, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void anUnknownCodeIsRejected() {
        when(tenancy.currentStoreTypesForTenant(BIZ)).thenReturn(List.of("restaurant"));

        assertThatThrownBy(() -> service.change(BIZ, List.of("restaurant", "casino")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown business format");
    }

    @Test
    void describeMarksAnInUseFoodFormatAsNotRemovable() {
        when(tenancy.currentStoreTypesForTenant(BIZ)).thenReturn(List.of("restaurant"));
        when(orders.openOrders(BIZ, "branch-1")).thenReturn(List.of(order(OrderTicketService.CHANNEL_DINE_IN)));

        BusinessFormatsService.FormatState restaurant = service.describe(BIZ).stream()
                .filter(f -> f.code().equals("restaurant")).findFirst().orElseThrow();
        assertThat(restaurant.selected()).isTrue();
        assertThat(restaurant.removable()).isFalse();
        assertThat(restaurant.lockedReason()).contains("open orders");

        BusinessFormatsService.FormatState bar = service.describe(BIZ).stream()
                .filter(f -> f.code().equals("bar")).findFirst().orElseThrow();
        assertThat(bar.selected()).isFalse();
    }
}
