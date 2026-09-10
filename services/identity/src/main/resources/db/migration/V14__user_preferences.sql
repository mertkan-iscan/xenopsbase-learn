-- V14 — how somebody wants this product to look and read (T-10.9).
--
-- WHY THESE TWO COLUMNS ARE HERE AND NOT IN THE BROWSER.
--
-- Both were already being read as though this table held them. `useMe.ts` has
-- typed `language` as a field of /api/v1/me since T-10.2 and its comment cites
-- "app_user.language" by name; no migration ever added it, so the field was
-- absent from every response and the frontend fell through to what the browser
-- guessed, every time, for everybody. This is that column, actually existing.
--
-- `theme` is the same fact about the same person. A preference kept only in
-- localStorage is a preference that belongs to a BROWSER: it is lost on a new
-- laptop, absent on the phone, and shared with whoever else uses the shared
-- terminal on a factory floor. Somebody who needs the light theme because of
-- how they see does not want to re-choose it on every device they are handed.
--
-- NULLABLE, AND THE NULL MEANS SOMETHING — the same argument V13 makes for
-- time_zone, and it is worth repeating because the shortcut is tempting twice
-- over here. Defaulting `language` to 'en' would erase the difference between
-- "chose English" and "has never been asked", which is the population a
-- customer rolling this out in Turkey needs to find. Defaulting `theme` to
-- 'system' would do the same to a smaller but real question: how many people
-- have deliberately chosen, and how many are simply getting the OS default?
--
-- 'SYSTEM' IS A STORABLE VALUE, and it is not the same as NULL. It renders
-- identically today — both follow the operating system — but one is a decision
-- and the other is the absence of one. A person who chose `SYSTEM` after trying
-- dark should not be re-prompted, re-defaulted or counted as unasked.
--
-- Theme is stored as the enum's own name, uppercase, the way `status` and every
-- other closed set in this schema is. Language is stored as the tag itself,
-- lowercase-normalised, because it is not an enum of ours to name.
ALTER TABLE app_user ADD COLUMN language varchar(16);
ALTER TABLE app_user ADD COLUMN theme    varchar(16);

-- CHECK ON THEME, NOT ON LANGUAGE, and the asymmetry is deliberate.
--
-- Theme is a closed set this platform defines and can only widen by deploying
-- code that knows the new name, so a constraint here is a guard that can never
-- go stale — a row holding 'sepia' would be a row no frontend can render, and
-- the honest place to refuse it is the moment it is written.
--
-- Language is the opposite: BCP-47 tags are an open, external and growing
-- namespace, and the set this product SHIPS in (en, tr today) is a product
-- decision that changes without a migration. A constraint listing today's two
-- would refuse a valid row on the day a third is added, in a place nobody
-- would think to look. It is validated where it is set, and an unknown tag
-- falls back where it is read — exactly what V13 says about IANA zones.
ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_theme CHECK (theme IS NULL OR theme IN ('LIGHT', 'DARK', 'SYSTEM'));
