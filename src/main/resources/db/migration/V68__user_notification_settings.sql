-- V68 — User notification preferences (Phase 9 realtime Slice 5)
-- Adds a JSON settings column to users for storing per-user preferences
-- including notification type toggles and quiet hours.

ALTER TABLE users
    ADD COLUMN settings JSON DEFAULT NULL
    AFTER locked_until;
