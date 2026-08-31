package zelisline.ub.integrations.csvimport.api.dto;

import java.util.List;

public record CsvImportResponse(
        boolean dryRun,
        int rowsParsed,
        List<CsvImportLineError> errors,
        Integer rowsCommitted,
        List<CsvImportLineError> warnings
) {
    public CsvImportResponse(
            boolean dryRun,
            int rowsParsed,
            List<CsvImportLineError> errors,
            Integer rowsCommitted
    ) {
        this(dryRun, rowsParsed, errors, rowsCommitted, List.of());
    }
}
