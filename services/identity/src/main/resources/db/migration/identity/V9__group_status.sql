-- V9 — the third level of the status chain (T-1.4).
--
-- Tenant status arrived with V7 and user status with V2/V8; a group had none,
-- and without it "the worst of the chain" has only two links. A department
-- being wound down is the case it exists for: its people keep their accounts
-- and their history, and stop being able to act, without the whole company
-- being suspended around them.

ALTER TABLE user_group
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE';
