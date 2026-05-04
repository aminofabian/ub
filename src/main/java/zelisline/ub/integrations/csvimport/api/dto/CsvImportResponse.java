package zelisline.ub.integrations.csvimport.api.dto;

import java.util.List;

public record CsvImportResponse(
        boolean dryRun,
        int rowsParsed,
        List<CsvImportLineError> errors,
        Integer rowsCommitted
) {
}
