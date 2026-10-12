-- Hot-path indexes for the append-only stock_movements ledger.
--   * Desktop replay (StockMovementRepository.findForDesktopSync) filters business_id and orders by
--     created_at, and had no supporting index — it filesorted the tenant's whole ledger.
--   * Reversal/reconciliation lookups (findByBusinessIdAndReferenceTypeAndReferenceId) had none either.
CREATE INDEX idx_sm_business_created ON stock_movements (business_id, created_at);
CREATE INDEX idx_sm_business_ref ON stock_movements (business_id, reference_type, reference_id);
