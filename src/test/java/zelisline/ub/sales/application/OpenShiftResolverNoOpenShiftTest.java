package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import zelisline.ub.platform.web.SetupRequiredException;
import zelisline.ub.platform.web.SetupRequirement;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.till.repository.TillDeviceRepository;

/**
 * A sale with no open shift has to tell the cashier the right next step. When the device is not
 * one the branch trusts, opening a shift would fail too, so registering the till comes first.
 */
class OpenShiftResolverNoOpenShiftTest {

    private static final String BUSINESS = "biz-1";
    private static final String BRANCH = "branch-1";
    private static final String DEVICE = "device-key-0001";

    private final TillDeviceRepository tills = mock(TillDeviceRepository.class);
    private final OpenShiftResolver resolver = new OpenShiftResolver(mock(ShiftRepository.class), tills);

    @Test
    void branchWithoutRegisteredTillsAsksForAShift() {
        when(tills.existsByBusinessIdAndBranchIdAndRevokedAtIsNull(BUSINESS, BRANCH)).thenReturn(false);

        SetupRequiredException ex = resolver.noOpenShift(BUSINESS, BRANCH, DEVICE, "No open shift");

        assertThat(ex.getRequirement()).isEqualTo(SetupRequirement.SHIFT);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).isEqualTo("No open shift");
    }

    @Test
    void trustedDeviceAsksForAShift() {
        when(tills.existsByBusinessIdAndBranchIdAndRevokedAtIsNull(BUSINESS, BRANCH)).thenReturn(true);
        when(tills.existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(BUSINESS, BRANCH, DEVICE))
                .thenReturn(true);

        SetupRequiredException ex = resolver.noOpenShift(BUSINESS, BRANCH, DEVICE, "No open shift");

        assertThat(ex.getRequirement()).isEqualTo(SetupRequirement.SHIFT);
    }

    @Test
    void untrustedDeviceIsToldToRegisterTheTillFirst() {
        when(tills.existsByBusinessIdAndBranchIdAndRevokedAtIsNull(BUSINESS, BRANCH)).thenReturn(true);
        when(tills.existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(BUSINESS, BRANCH, DEVICE))
                .thenReturn(false);

        SetupRequiredException ex = resolver.noOpenShift(BUSINESS, BRANCH, DEVICE, "No open shift");

        assertThat(ex.getRequirement()).isEqualTo(SetupRequirement.TILL);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deviceThatSendsNoKeyIsToldToRegisterWhenTheBranchTrustsOnlyRegisteredTills() {
        when(tills.existsByBusinessIdAndBranchIdAndRevokedAtIsNull(BUSINESS, BRANCH)).thenReturn(true);

        SetupRequiredException ex = resolver.noOpenShift(BUSINESS, BRANCH, null, "No open shift");

        assertThat(ex.getRequirement()).isEqualTo(SetupRequirement.TILL);
    }
}
