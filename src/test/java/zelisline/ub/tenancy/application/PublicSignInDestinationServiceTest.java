package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.application.SupplierSignInDoorService;
import zelisline.ub.tenancy.api.dto.PublicSignInDestinationResponse;
import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;

@ExtendWith(MockitoExtension.class)
class PublicSignInDestinationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private SupplierSignInDoorService supplierSignInDoorService;
    @Mock
    private CustomerPhoneRepository customerPhoneRepository;
    @Mock
    private PublicShopsSearchService publicShopsSearchService;

    private PublicSignInDestinationService service;

    @BeforeEach
    void setUp() {
        service = new PublicSignInDestinationService(
                userRepository,
                roleRepository,
                supplierSignInDoorService,
                customerPhoneRepository,
                publicShopsSearchService);
    }

    @Test
    void byEmailSurfacesInvitedOwnersAsStaffUnverified() {
        User invited = new User();
        invited.setId("u1");
        invited.setBusinessId("b1");
        invited.setEmail("owner@example.com");
        invited.setRoleId("role-owner");
        invited.setStatus(UserStatus.INVITED);

        Role owner = new Role();
        owner.setRoleKey("owner");

        when(userRepository.findAllSignInEligibleByEmail("owner@example.com"))
                .thenReturn(List.of(invited));
        when(roleRepository.findByIdAndDeletedAtIsNull("role-owner"))
                .thenReturn(Optional.of(owner));
        when(publicShopsSearchService.byBusinessIds(anyList()))
                .thenReturn(List.of(new PublicShopsSearchResponse(
                        "ghost-shop",
                        "Ghost Shop",
                        null,
                        "ghost-shop.kiosk.ke")));
        when(supplierSignInDoorService.byEmail("owner@example.com"))
                .thenReturn(Optional.empty());

        List<PublicSignInDestinationResponse> rows = service.byEmail("owner@example.com");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).door())
                .isEqualTo(PublicSignInDestinationResponse.DOOR_STAFF_UNVERIFIED);
        assertThat(rows.get(0).hint()).containsIgnoringCase("verify");
        assertThat(rows.get(0).slug()).isEqualTo("ghost-shop");
    }
}
