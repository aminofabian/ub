package zelisline.ub.wholesale.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.support.application.SupportService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

class SupplierAppealServiceTest {

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final SupportService support = mock(SupportService.class);

    private final SupplierAppealService service = new SupplierAppealService(
            flags, orders, support, new ObjectMapper());

    @Test
    void appealsAreNotFoundWhileTheStandingFlagIsOff() {
        assertThatThrownBy(() -> service.appeal("biz-1", "user-1", "order-1", "wrong quantity"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(orders, support);
    }

    @Test
    void leavingAnOrderOutNeedsAReason() {
        when(orders.findById("order-1")).thenReturn(Optional.of(mock(SalesOrder.class)));
        assertThatThrownBy(() -> service.setStandingExcluded("order-1", true, "  ", "admin-1", "Admin"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
