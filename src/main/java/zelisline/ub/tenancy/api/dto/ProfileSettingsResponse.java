package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileSettingsResponse(String storeType) {
    public static ProfileSettingsResponse empty() {
        return new ProfileSettingsResponse(null);
    }
}
