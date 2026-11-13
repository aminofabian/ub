package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.storefront.api.dto.WebOrderClearResponse;
import zelisline.ub.storefront.repository.WebOrderRepository;

class WebOrderClearServiceTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String OTHER_BUSINESS = "biz-2";

    private final WebOrderRepository webOrderRepository = mock(WebOrderRepository.class);
    private final WebOrderFulfillmentService webOrderFulfillmentService = mock(WebOrderFulfillmentService.class);

    private final WebOrderClearService service = new WebOrderClearService(
            webOrderRepository, webOrderFulfillmentService);

    @BeforeEach
    void emptyByDefault() {
        when(webOrderRepository.findVoidableIds(any(), any(Pageable.class))).thenReturn(List.of());
        when(webOrderRepository.findVoidableIdsByBranch(any(), any(), any(Pageable.class))).thenReturn(List.of());
    }

    @Test
    void clearOpenOrders_voidsEveryIdForThatBusiness() {
        when(webOrderRepository.findVoidableIds(eq(BUSINESS_ID), any(Pageable.class)))
                .thenReturn(List.of("a", "b"))
                .thenReturn(List.of());

        WebOrderClearResponse result = service.clearOpenOrders(BUSINESS_ID, null);

        assertThat(result.voided()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.moreRemaining()).isFalse();
        verify(webOrderFulfillmentService).voidOrder(BUSINESS_ID, "a");
        verify(webOrderFulfillmentService).voidOrder(BUSINESS_ID, "b");
        verify(webOrderFulfillmentService, never()).voidOrder(eq(OTHER_BUSINESS), any());
    }

    @Test
    void clearOpenOrders_otherBusinessIsNotTouched() {
        when(webOrderRepository.findVoidableIds(eq(OTHER_BUSINESS), any(Pageable.class)))
                .thenReturn(List.of("secret"));

        WebOrderClearResponse result = service.clearOpenOrders(BUSINESS_ID, "  ");

        assertThat(result.voided()).isZero();
        verify(webOrderFulfillmentService, never()).voidOrder(any(), any());
        verify(webOrderRepository, never()).findVoidableIds(eq(OTHER_BUSINESS), any());
    }

    @Test
    void clearOpenOrders_branchScopesTheQuery() {
        when(webOrderRepository.findVoidableIdsByBranch(eq(BUSINESS_ID), eq("branch-1"), any(Pageable.class)))
                .thenReturn(List.of("a"))
                .thenReturn(List.of());

        WebOrderClearResponse result = service.clearOpenOrders(BUSINESS_ID, " branch-1 ");

        assertThat(result.voided()).isEqualTo(1);
        verify(webOrderFulfillmentService).voidOrder(BUSINESS_ID, "a");
        verify(webOrderRepository, never()).findVoidableIds(any(), any());
    }

    @Test
    void clearOpenOrders_oneFailureDoesNotStopTheRest() {
        when(webOrderRepository.findVoidableIds(eq(BUSINESS_ID), any(Pageable.class)))
                .thenReturn(List.of("a", "b"))
                .thenReturn(List.of("a"));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "A completed pickup cannot be voided"))
                .when(webOrderFulfillmentService).voidOrder(BUSINESS_ID, "a");

        WebOrderClearResponse result = service.clearOpenOrders(BUSINESS_ID, null);

        assertThat(result.voided()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.moreRemaining()).isFalse();
        verify(webOrderFulfillmentService).voidOrder(BUSINESS_ID, "b");
    }
}
