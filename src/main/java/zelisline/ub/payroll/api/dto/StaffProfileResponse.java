package zelisline.ub.payroll.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Staff profile response. {@code privateFields} is null when the caller lacks {@code staff.hr.read}.
 */
public record StaffProfileResponse(
        String id,
        String userId,
        String branchId,
        String branchName,
        String loginName,
        String roleName,
        PublicFields publicFields,
        PrivateFields privateFields,
        Instant createdAt,
        Instant updatedAt
) {

    public record PublicFields(
            String displayName,
            String title,
            String photoUrl,
            LocalDate startDate,
            String employmentStatus
    ) {
    }

    public record PrivateFields(
            String phone,
            String address,
            String nationalId,
            String employeeCode,
            String emergencyContactName,
            String emergencyContactPhone,
            Map<String, Object> bankDetails,
            String notes
    ) {
    }
}
