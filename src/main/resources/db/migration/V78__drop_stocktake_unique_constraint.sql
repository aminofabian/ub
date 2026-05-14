-- V78: Drop the unique constraint that prevented multiple stock-take sessions
-- of the same type per branch per day. Multiple stock managers can now run
-- concurrent or sequential sessions for the same branch.

ALTER TABLE stock_take_sessions DROP INDEX uq_stocktake_branch_type_date;
