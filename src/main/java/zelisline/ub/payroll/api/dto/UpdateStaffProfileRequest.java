package zelisline.ub.payroll.api.dto;

import java.time.LocalDate;
import java.util.Map;

import jakarta.validation.constraints.Size;

public record UpdateStaffProfileRequest(
        @Size(max = 255) String displayName,
        @Size(max = 128) String title,
        @Size(max = 500) String photoUrl,
        LocalDate startDate,
        @Size(max = 32) String employmentStatus,
        @Size(max = 50) String phone,
        @Size(max = 500) String address,
        @Size(max = 64) String nationalId,
        @Size(max = 64) String employeeCode,
        @Size(max = 255) String emergencyContactName,
        @Size(max = 50) String emergencyContactPhone,
        Map<String, Object> bankDetails,
        String notes
) {
}
