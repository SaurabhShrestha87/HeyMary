-- Example merchant configuration
-- Replace with your actual API keys and merchant ID
-- You can add multiple merchants by inserting more rows

-- Example merchant configuration
-- Replace with your actual API keys and merchant ID
-- You can add multiple merchants by inserting more rows

DO $$
DECLARE
    v_dutchie_api_key VARCHAR(500) := 'ed7b410b422d4542b9cf848c48b1fcef';
    v_dutchie_auth_header VARCHAR(500);
BEGIN
    -- Compute the Basic Auth header: Base64 encode (apiKey:)
    v_dutchie_auth_header := 'Basic ' || encode((v_dutchie_api_key || ':')::bytea, 'base64');
    
    INSERT INTO integration_configs (
        merchant_id,
        boomerangme_api_key,
        dutchie_api_key,
        dutchie_auth_header,
        dutchie_webhook_secret,
        boomerangme_program_id,
        enabled
    ) VALUES (
        'Drop Delivery',
        '9184de46e48cbcfd5305260afb6f1013',
        v_dutchie_api_key,
        v_dutchie_auth_header,
        NULL,  -- Webhook secret is optional - add it later when you get it from Dutchie
        9389,
        true
    );
END $$;

-- Note: Uncomment and fill in the values above, then run this migration
-- Or use pgAdmin/psql to insert directly

