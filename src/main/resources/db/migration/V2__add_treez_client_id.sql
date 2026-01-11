-- Add treez_client_id column for Treez token-based authentication
ALTER TABLE integration_configs 
ADD COLUMN IF NOT EXISTS treez_client_id VARCHAR(500);

COMMENT ON COLUMN integration_configs.treez_client_id IS 'Client ID for Treez API token generation and requests';
COMMENT ON COLUMN integration_configs.treez_api_key IS 'API key for Treez token generation (used with client_id to get access tokens)';
