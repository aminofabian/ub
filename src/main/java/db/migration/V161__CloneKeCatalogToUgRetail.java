package db.migration;

import java.sql.Connection;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import zelisline.ub.globalcatalog.application.CatalogRegionalCloneJdbc;

/**
 * Phase 4 — populate empty {@code ug-retail} from KE {@code default}.
 * Absolute recommended buy/sell prices are scrubbed; margin hints are kept/derived.
 */
public class V161__CloneKeCatalogToUgRetail extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        CatalogRegionalCloneJdbc.cloneScrubbingAbsolutePrices(
                connection,
                "default",
                "ug-retail"
        );
    }
}
