package zelisline.ub.exports.application;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportJobParams(LocalDate from, LocalDate to, String branchId) {
}
