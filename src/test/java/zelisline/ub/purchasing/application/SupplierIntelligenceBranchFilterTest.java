package zelisline.ub.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupplierIntelligenceBranchFilterTest {

    @Test
    void blankBranchLeavesSqlUnchanged() {
        String sql = "SELECT 1 FROM supplier_invoices si WHERE si.business_id = ? ORDER BY si.id";
        assertThat(SupplierIntelligenceService.withInvoiceBranchFilter(sql, "  ")).isEqualTo(sql);
        assertThat(SupplierIntelligenceService.withInvoiceBranchFilter(sql, null)).isEqualTo(sql);
    }

    @Test
    void injectsBeforeOrderByAndLimit() {
        String sql = """
                SELECT si.id
                  FROM supplier_invoices si
                 WHERE si.business_id = ?
                 ORDER BY si.id
                 LIMIT 10
                """;
        String out = SupplierIntelligenceService.withInvoiceBranchFilter(sql, "branch-1");
        String upper = out.toUpperCase();
        int coalesce = upper.indexOf("AND COALESCE");
        int orderBy = upper.indexOf("ORDER BY");
        // Filter's own LIMIT 1 appears before ORDER BY; assert main LIMIT stays last.
        int mainLimit = upper.lastIndexOf("LIMIT");
        assertThat(coalesce).isGreaterThan(0);
        assertThat(coalesce).isLessThan(orderBy);
        assertThat(orderBy).isLessThan(mainLimit);
        assertThat(out).doesNotContain("?AND");
        assertThat(out).containsPattern("(?s)\\?\\s+AND COALESCE");
    }

    @Test
    void injectsBeforeGroupBy() {
        String sql = """
                SELECT si.supplier_id, SUM(1)
                  FROM supplier_invoices si
                 WHERE si.business_id = ?
                 GROUP BY si.supplier_id
                """;
        String out = SupplierIntelligenceService.withInvoiceBranchFilter(sql, "branch-1");
        assertThat(out.toUpperCase().indexOf("AND COALESCE")).isLessThan(out.toUpperCase().indexOf("GROUP BY"));
    }
}
