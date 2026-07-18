package zelisline.ub.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.tenancy.api.dto.InventorySettingsResponse;
import zelisline.ub.tenancy.api.dto.StocktakeSettingsResponse;
import zelisline.ub.tenancy.application.BusinessInventorySettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@ExtendWith(MockitoExtension.class)
class InventoryRoleAccessServiceTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String ROLE_ID = "role-1";

    @Mock private BusinessRepository businessRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private BusinessInventorySettingsService businessInventorySettingsService;

    private InventoryRoleAccessService service;

    @BeforeEach
    void setUp() {
        service = new InventoryRoleAccessService(
                businessRepository,
                roleRepository,
                businessInventorySettingsService
        );
    }

    @Test
    void stockManagerWithApprove_respectsHiddenToggle() {
        stubRole("stock_manager");
        stubShowSystemStock(false);

        assertThat(service.canSeeSystemStockDuringCount(BUSINESS_ID, ROLE_ID, true))
                .isFalse();
    }

    @Test
    void stockManagerWithApprove_respectsShownToggle() {
        stubRole("stock_manager");
        stubShowSystemStock(true);

        assertThat(service.canSeeSystemStockDuringCount(BUSINESS_ID, ROLE_ID, true))
                .isTrue();
    }

    @Test
    void otherRoleWithApprove_alwaysSeesSystemStock() {
        stubRole("manager");

        assertThat(service.canSeeSystemStockDuringCount(BUSINESS_ID, ROLE_ID, true))
                .isTrue();
    }

    @Test
    void adminAlwaysSeesSystemStock() {
        stubRole("admin");

        assertThat(service.canSeeSystemStockDuringCount(BUSINESS_ID, ROLE_ID, false))
                .isTrue();
    }

    private void stubRole(String roleKey) {
        Role role = new Role();
        role.setId(ROLE_ID);
        role.setRoleKey(roleKey);
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
    }

    private void stubShowSystemStock(boolean show) {
        Business business = new Business();
        business.setId(BUSINESS_ID);
        business.setSettings("{}");
        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(business));
        when(businessInventorySettingsService.readFromSettingsJson(any()))
                .thenReturn(new InventorySettingsResponse(
                        new StocktakeSettingsResponse(
                                show,
                                25,
                                StocktakeSettingsResponse.DEFAULT_MORNING_STARTS_AT,
                                StocktakeSettingsResponse.DEFAULT_MORNING_ENDS_AT,
                                StocktakeSettingsResponse.DEFAULT_EVENING_STARTS_AT,
                                StocktakeSettingsResponse.DEFAULT_EVENING_ENDS_AT
                        ),
                        null,
                        null,
                        null,
                        null
                ));
    }
}
