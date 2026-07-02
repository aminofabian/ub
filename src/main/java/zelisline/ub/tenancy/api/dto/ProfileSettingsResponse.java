package zelisline.ub.tenancy.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileSettingsResponse(
        String storeType,
        List<String> storeTypes
) {
    public static ProfileSettingsResponse empty() {
        return new ProfileSettingsResponse(null, null);
    }
}
