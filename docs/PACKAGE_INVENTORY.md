# Package / selling-unit inventory

## Model

- **Base product** holds canonical physical stock (`items.current_stock` updates on the parent).
- **Selling units** (single, half-tray, tray, crate, …) are variant SKUs with:
  - `variant_of_item_id` → parent
  - `packaging_unit_qty` → base units per unit sold (conversion)
  - `is_package_variant` / `is_stocked = false` for package rows
- **Available packages** = `floor(pool_on_hand / packaging_unit_qty)` (see `PackageVariantStockResolver.displayStockQty`).
- **Stock pool** (`branchStockPoolItemIds`): sums active batches on the parent plus any variant SKUs that share the pool (including legacy rows that still have batches on a child SKU, e.g. singles marked stocked).

## Sales & checkout

`PackageVariantStockResolver.resolvePick` converts sold catalog quantity to base units and picks batches on the **parent** item. POS, voids, refunds, and web checkout use this path.

## Inbound stock

Opening balance, stock gains, GRN (Path A/B), and transfers use `resolveInbound` so receiving “10 trays” adds `10 × 30` eggs to the parent.

## Editing

Changing `packaging_unit_qty` after sales exist for that SKU is rejected (`409 Conflict`) so historical base-unit deductions stay interpretable.

## API list fields (with `branchId`)

| Field | Meaning |
|-------|---------|
| `stockQty` | Display units (packages for variants, base for parent) |
| `baseStockQty` | Parent on-hand in base units |
| `packageUnitsPerSale` | Conversion factor |
