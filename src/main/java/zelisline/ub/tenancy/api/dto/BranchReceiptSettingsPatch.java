package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Size;

public record BranchReceiptSettingsPatch(
        @Size(max = 40) String phone,
        @Size(max = 255) String email,
        @Size(max = 500) String website,
        @Size(max = 40) String tillNumber,
        @Size(max = 500) String footerNote,
        @Size(max = 120) String printerCupsName
) {
}
