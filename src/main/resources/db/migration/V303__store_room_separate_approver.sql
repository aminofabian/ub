-- Separation of duties for approvals (scope §10 D7).
--
-- Off by default, so nothing changes for a single-operator shop where the person who
-- raises a big take-out is also the only one able to approve it. Switched on, nobody
-- may approve a take-out they raised themselves — someone else has to look at it.
--
-- Rejecting your own is still allowed: withdrawing a request moves no stock, and
-- needing a second person to cancel your own mistake would be perverse.
--
-- A policy rather than a hard rule on purpose. Whether two people are standing in the
-- shop is a property of the business, not of this software, and the open question in
-- the scope ("should self-approval be allowed?") has different answers per merchant.

ALTER TABLE store_room_settings
  ADD COLUMN require_separate_approver BOOLEAN NOT NULL DEFAULT FALSE;
