package zelisline.ub.wholesale.api.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/** The dates a supplier marks as closed, as ISO dates (yyyy-MM-dd). Replaces the whole list. */
public record SetClosedDaysRequest(@Size(max = 366) List<String> dates) {
}
