package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.tenancy.api.dto.BranchReceiptSettingsPatch;
import zelisline.ub.tenancy.api.dto.BranchReceiptSettingsResponse;

class BranchReceiptSettingsServiceTest {

    private final BranchReceiptSettingsService service =
            new BranchReceiptSettingsService(new ObjectMapper());

    @Test
    void read_missingFlags_defaultFalse() {
        BranchReceiptSettingsResponse r = service.read("{\"phone\":\"0712\"}");
        assertThat(r.whatsappReceiptEnabled()).isFalse();
        assertThat(r.customerNameOnReceipt()).isFalse();
        assertThat(r.phone()).isEqualTo("0712");
    }

    @Test
    void merge_enablesWhatsAppReceipt() {
        String json = service.merge(
                null,
                new BranchReceiptSettingsPatch(null, null, null, null, null, null, true, null)
        );
        BranchReceiptSettingsResponse r = service.read(json);
        assertThat(r.whatsappReceiptEnabled()).isTrue();
        assertThat(r.customerNameOnReceipt()).isFalse();
    }

    @Test
    void merge_disablesWhatsAppReceipt() {
        String enabled = service.merge(
                null,
                new BranchReceiptSettingsPatch(null, null, null, null, null, null, true, null)
        );
        String disabled = service.merge(
                enabled,
                new BranchReceiptSettingsPatch(null, null, null, null, null, null, false, null)
        );
        assertThat(service.read(disabled).whatsappReceiptEnabled()).isFalse();
    }

    @Test
    void merge_enablesCustomerNameOnReceipt() {
        String json = service.merge(
                null,
                new BranchReceiptSettingsPatch(null, null, null, null, null, null, null, true)
        );
        BranchReceiptSettingsResponse r = service.read(json);
        assertThat(r.customerNameOnReceipt()).isTrue();
        assertThat(r.whatsappReceiptEnabled()).isFalse();
    }

    @Test
    void merge_disablesCustomerNameOnReceipt() {
        String enabled = service.merge(
                null,
                new BranchReceiptSettingsPatch(null, null, null, null, null, null, null, true)
        );
        String disabled = service.merge(
                enabled,
                new BranchReceiptSettingsPatch(null, null, null, null, null, null, null, false)
        );
        assertThat(service.read(disabled).customerNameOnReceipt()).isFalse();
    }
}
