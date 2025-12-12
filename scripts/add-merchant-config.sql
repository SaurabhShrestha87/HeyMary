-- Script to add a merchant configuration
-- Usage: Run this in pgAdmin or psql after connecting to the database

-- Example: Add a merchant configuration
INSERT INTO integration_configs (
    merchant_id,
    boomerangme_api_key,
    dutchie_api_key,
    dutchie_webhook_secret,
    boomerangme_program_id,
    enabled
) VALUES (
    'merchant-001',                              -- Your unique merchant ID
    'your-boomerangme-api-key-here',            -- Boomerangme API key
    'your-dutchie-api-key-here',                 -- Dutchie POS API key
    'your-dutchie-webhook-secret-here',          -- Dutchie webhook secret for HMAC validation
    'your-boomerangme-program-id-here',          -- Boomerangme program/loyalty program ID
    true                                         -- Enable this integration
)
ON CONFLICT (merchant_id) DO UPDATE SET
    boomerangme_api_key = EXCLUDED.boomerangme_api_key,
    dutchie_api_key = EXCLUDED.dutchie_api_key,
    dutchie_webhook_secret = EXCLUDED.dutchie_webhook_secret,
    boomerangme_program_id = EXCLUDED.boomerangme_program_id,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;

-- To add multiple merchants, repeat the INSERT statement with different merchant_id values

