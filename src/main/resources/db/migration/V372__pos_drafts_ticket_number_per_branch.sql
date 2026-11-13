-- Held-cart and service-ticket numbers are per branch, so their unique key must be too.
--
-- V114 made ticket_number unique per business, but BranchPosSequenceAllocator has always handed
-- numbers out per branch, starting at 1. A business with two branches therefore hits a
-- duplicate-key error on the second branch's first ticket (and on every one after, until that
-- branch's sequence passes the first's). The fault was hidden while only one branch of a
-- business used held carts.
--
-- The new key is strictly weaker than the old one for existing data: rows that were unique per
-- (business_id, ticket_number) are necessarily unique per (business_id, branch_id,
-- ticket_number), so adding it cannot fail on rows already there. business_id stays the leading
-- column, and the other indexes on business_id keep fk_pd_business satisfied once the old key
-- is dropped. One statement, so the table is never without a uniqueness rule.
--
-- Dropping an index: approved by the owner on 2026-11-13.

ALTER TABLE pos_drafts
  ADD UNIQUE KEY uq_pos_drafts_branch_ticket (business_id, branch_id, ticket_number),
  DROP INDEX uq_pos_drafts_business_ticket;
