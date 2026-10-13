-- V13: Drop decorative, product-irrelevant profile fields.
--
-- "company" and "job_title" were scaffolded from a generic professional-profile
-- template, but no feature of this content-analytics platform reads them. They
-- made the data model look templated rather than deliberate. Every remaining
-- column in user_profiles now maps to a real capability:
--   bio, location, phone, website  -> editable profile fields (validated)
--   timezone                        -> timestamp display for the analytics dashboard
--   email/two-factor/public-email   -> privacy + notification preferences
--   total_*                         -> usage statistics
--
-- DROP COLUMN IF EXISTS keeps this safe to re-run against any environment.
ALTER TABLE user_profiles DROP COLUMN IF EXISTS company;
ALTER TABLE user_profiles DROP COLUMN IF EXISTS job_title;
