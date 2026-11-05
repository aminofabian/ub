package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One existing catalog item to nest under a parent as an option variant.
 * The item keeps its id, SKU, stock, and sales history — only the family link changes.
 *
 * <p>The optional pack fields (PACKAGE_FLOW_GAPS G1) let regroup mark a line as a
 * shared selling unit of the base product ("Crate of 24") instead of a flavor option:
 * {@code unitsPerPack > 1} or {@code sellsAsPack=true} sets the package shape, mirroring
 * the UI's {@code variantSellShape} rules, and the row stops holding its own stock.
 */
public record AttachVariantLineRequest(
        @NotBlank @Size(max = 36) String itemId,
        /** Option label shown under the family (e.g. "#12", "500ml"). */
        @NotBlank @Size(max = 255) String variantName,
        /** This option sells as a pack of the base product (forces package shape at qty 1). */
        Boolean sellsAsPack,
        /** Base units per sale of this option (24 for a crate). */
        @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitsPerPack,
        /** Unit label ("Crate"); defaults to the variant name. */
        @Size(max = 255) String packUnitName
) {
}
