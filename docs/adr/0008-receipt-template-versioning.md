# ADR 0008 — Receipt template versioning (PDF + ESC/POS)

## Status

Accepted (Phase 4, Slice 5)

## Context

Sale receipts are rendered server-side as **A6 PDF** (OpenPDF) and **ESC/POS** byte streams for thermal printers (`58` mm and `80` mm widths). Ten eventually need **business header** details (name, branch, optional logo, footer, tax registration text) without breaking printer integrations or saved PDFs when we change layout.

There is no separate **`platform-pdf`** module in this codebase; renderers live under `zelisline.ub.sales.receipt` and consume a **`ReceiptSnapshot`** built from the sale, lines, payments, and tenant-scoped business/branch data.

## Decision

1. **Implicit template version in code** — Layout, column widths, and command sequences are owned by `ReceiptPdfRenderer` and `ReceiptEscPosRenderer`. Any material change to structure or encoding is a **normal application release**; consumers must tolerate new bytes (same endpoints, same content types).

2. **No template-id query parameter in Phase 4** — We do not expose multiple concurrently selectable templates. Re-print always uses the **current** renderer for the deployed version.

3. **Logo and rich branding** — Not required for Slice 5. When added, prefer **URL or storage key on `businesses` (or related)** resolved at render time; keep fallbacks so missing assets do not fail the sale flow. Document field names when implemented.

4. **Footer / tax registration** — Free-text or structured fields on the business aggregate (or dedicated columns later) feed the snapshot footer; until present, stubs or empty sections are acceptable.

5. **Golden tests** — Integration tests assert **PDF magic** and **ESC/POS init/cut** sequences for fixture sales; optional snapshot files can be added if diffs become noisy.

## Consequences

- **Breaking visual changes** are not versioned at the HTTP layer; clients that parse raw ESC/POS beyond print-and-forget may need coordination on deploys.
- **Caching/CDN** of PDFs is optional; default response is generated per request with `Content-Disposition` appropriate for download or inline view.
- A future ADR may introduce **`templateVersion`** on responses or a **dedicated print job** API if mobile clients need strict compatibility windows.

## References

- `docs/PHASE_4_PLAN.md` — Slice 5 (Receipts)
- `zelisline.ub.sales.receipt.SaleReceiptService`
- `zelisline.ub.sales.api.SalesController` — `GET .../receipt.pdf`, `GET .../receipt/thermal`
