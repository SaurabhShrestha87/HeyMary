-- Add license expiration field from Boomerangme customer data
-- Used when creating Treez customers (drivers_license_expiration)
ALTER TABLE cards ADD COLUMN cardholder_license_expiration DATE;
COMMENT ON COLUMN cards.cardholder_license_expiration IS 'License expiration date from Boomerangme - sent to Treez as drivers_license_expiration';
