-- V8 — the person lifecycle: invited, active, deactivated (T-1.9).
--
-- NOTHING HERE DELETES. A person who leaves a company still has to appear in
-- last year's compliance report, and a report has to be able to say "no longer
-- with the company" rather than "never did the training" — which are the same
-- row unless the model keeps them apart. So deactivation is a status and a
-- timestamp, and every attempt, score and completion keeps pointing at the
-- same app_user.id.

ALTER TABLE app_user
    -- When the invitation was offered, and when it stops being acceptable.
    -- Separate columns rather than "invited_at + a constant": the TTL is
    -- configuration, and a row must still say what IT was promised if that
    -- configuration changes afterwards.
    ADD COLUMN invited_at            timestamptz,
    ADD COLUMN invitation_expires_at timestamptz,

    -- The SHA-256 of the invitation token, hex. NEVER the token itself: this
    -- column is a bearer credential's verifier, and a database backup, a
    -- support query or a leaked dump must not be a way in. The token exists
    -- exactly once, in the response to the call that minted it, and single-use
    -- means this column is cleared the moment it is accepted.
    ADD COLUMN invitation_token_hash varchar(64),

    -- Set on deactivation, cleared on reactivation. The status column already
    -- says WHICH state; this says since when, which is what a report needs to
    -- distinguish somebody who left in March from somebody who left last week.
    ADD COLUMN deactivated_at        timestamptz;

-- Globally unique rather than per tenant, because the token is random and a
-- collision would be a defect, not a coincidence. Postgres treats NULLs as
-- distinct, so any number of people may have no open invitation.
CREATE UNIQUE INDEX uq_app_user_invitation_token ON app_user (invitation_token_hash);
