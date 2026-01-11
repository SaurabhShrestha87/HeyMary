-- Create integration_configs table with all columns
CREATE TABLE integration_configs (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL UNIQUE,
    boomerangme_api_key VARCHAR(500),
    boomerangme_webhook_secret VARCHAR(500),
    boomerangme_program_id VARCHAR(255),
    integration_type VARCHAR(50) NOT NULL DEFAULT 'DUTCHIE',
    default_template_id INTEGER,
    dutchie_api_key VARCHAR(500),
    dutchie_auth_header VARCHAR(500),
    dutchie_webhook_secret VARCHAR(500),
    treez_api_key VARCHAR(500),
    treez_auth_header VARCHAR(500),
    treez_dispensary_id VARCHAR(100),
    treez_webhook_secret VARCHAR(500),
    customer_match_type VARCHAR(20) NOT NULL DEFAULT 'PHONE',
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_integration_configs_merchant_id ON integration_configs(merchant_id);

COMMENT ON COLUMN integration_configs.dutchie_auth_header IS 'Pre-computed Basic Auth header for Dutchie API (Basic <base64(apiKey:)>). Auto-computed when dutchie_api_key is saved.';
COMMENT ON COLUMN integration_configs.boomerangme_webhook_secret IS 'Webhook secret for validating Boomerangme webhook signatures';
COMMENT ON COLUMN integration_configs.treez_webhook_secret IS 'Bearer token for Treez webhook authentication';
COMMENT ON COLUMN integration_configs.integration_type IS 'POS integration type for this merchant: TREEZ or DUTCHIE';
COMMENT ON COLUMN integration_configs.default_template_id IS 'Default template ID to use when creating cards for this integration. If not set, the system will use the template ID from the request or a system default.';
COMMENT ON COLUMN integration_configs.customer_match_type IS 'Field used to match Treez customers with Boomerangme cards: PHONE, EMAIL, or BOTH (default: BOTH)';

-- Create cards table
CREATE TABLE cards (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(100) NOT NULL,
    serial_number VARCHAR(100) NOT NULL UNIQUE,
    cardholder_id VARCHAR(100) NOT NULL,
    card_type VARCHAR(50),
    device_type VARCHAR(50),
    template_id VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'not_installed',
    
    -- Cardholder Information
    cardholder_email VARCHAR(255),
    cardholder_phone VARCHAR(50),
    cardholder_first_name VARCHAR(100),
    cardholder_last_name VARCHAR(100),
    cardholder_birth_date DATE,
    
    -- Card Metrics
    bonus_balance INTEGER DEFAULT 0,
    count_visits INTEGER DEFAULT 0,
    balance INTEGER,
    number_stamps_total INTEGER,
    number_rewards_unused INTEGER,
    
    -- Links
    short_link VARCHAR(500),
    share_link VARCHAR(500),
    install_link_universal VARCHAR(500),
    install_link_apple VARCHAR(500),
    install_link_google VARCHAR(500),
    install_link_pwa VARCHAR(500),
    
    -- Custom fields (JSON)
    custom_fields TEXT,
    
    -- Tracking
    utm_source VARCHAR(100),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    
    -- Timestamps
    issued_at TIMESTAMP,
    installed_at TIMESTAMP,
    expires_at TIMESTAMP,
    last_reward_redeemed_at TIMESTAMP,
    last_reward_earned_at TIMESTAMP,
    last_stamp_earned_at TIMESTAMP,
    synced_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cards_serial_number ON cards(serial_number);
CREATE INDEX idx_cards_cardholder_id ON cards(cardholder_id);
CREATE INDEX idx_cards_merchant_id ON cards(merchant_id);
CREATE INDEX idx_cards_status ON cards(status);

COMMENT ON TABLE cards IS 'Boomerangme loyalty cards - one-to-one relationship with customers';
COMMENT ON COLUMN cards.serial_number IS 'Unique identifier for Boomerangme cards. Present in CardIssuedEvent but may not be in API responses.';

-- Create customers table
CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL,
    external_customer_id VARCHAR(100), -- External POS customer ID (from Dutchie, Treez, etc.)
    boomerangme_card_id VARCHAR(255), -- Legacy field for backward compatibility
    integration_type VARCHAR(50) NOT NULL, -- POS integration type: TREEZ or DUTCHIE
    card_id BIGINT UNIQUE, -- Foreign key to cards table
    
    treez_email VARCHAR(255),
    treez_phone VARCHAR(50),
    treez_first_name VARCHAR(100),
    treez_last_name VARCHAR(100),
    treez_birth_date DATE,
    
    email VARCHAR(255),
    phone VARCHAR(50),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    birth_date DATE,
    
    total_points INTEGER DEFAULT 0,
    synced_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_customers_merchant_id ON customers(merchant_id);
CREATE INDEX idx_customers_external_id ON customers(external_customer_id);
CREATE INDEX idx_customers_boomerangme_id ON customers(boomerangme_card_id);
CREATE INDEX idx_customers_card_id ON customers(card_id);
CREATE INDEX idx_customers_integration_type ON customers(integration_type);

CREATE INDEX idx_customers_treez_email ON customers(treez_email);
CREATE INDEX idx_customers_treez_phone ON customers(treez_phone);

-- Foreign key constraint for card_id
ALTER TABLE customers 
ADD CONSTRAINT fk_customers_card 
FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uk_customers_merchant_external 
ON customers(merchant_id, external_customer_id, integration_type) 
WHERE external_customer_id IS NOT NULL;

-- Unique constraint for card_id
CREATE UNIQUE INDEX uk_customers_card 
ON customers(card_id) 
WHERE card_id IS NOT NULL;

COMMENT ON COLUMN customers.integration_type IS 'POS integration type: TREEZ or DUTCHIE';
COMMENT ON COLUMN customers.external_customer_id IS 'External POS customer ID from Dutchie, Treez, or other POS systems';
COMMENT ON COLUMN customers.card_id IS 'Foreign key to cards table (one-to-one relationship)';
COMMENT ON COLUMN customers.treez_email IS 'Treez customer email - used for matching with Boomerangme cards when customer_match_type is EMAIL';
COMMENT ON COLUMN customers.treez_phone IS 'Treez customer phone - used for matching with Boomerangme cards when customer_match_type is PHONE';
COMMENT ON COLUMN customers.treez_first_name IS 'Treez customer first name';
COMMENT ON COLUMN customers.treez_last_name IS 'Treez customer last name';
COMMENT ON COLUMN customers.treez_birth_date IS 'Treez customer birth date';

-- Create orders table (V3 - supports both Dutchie and Treez)
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL,
    integration_type VARCHAR(50) NOT NULL, -- POS integration type: TREEZ or DUTCHIE
    external_order_id VARCHAR(100) NOT NULL, -- External POS order ID (dutchie_order_id or treez_ticket_id)
    customer_id BIGINT REFERENCES customers(id),
    order_total DECIMAL(10, 2) NOT NULL,
    points_earned INTEGER DEFAULT 0,
    points_synced BOOLEAN DEFAULT false,
    order_date TIMESTAMP NOT NULL,
    synced_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_orders_merchant_id ON orders(merchant_id);
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_sync_status ON orders(points_synced, synced_at);
CREATE INDEX idx_orders_external_order_id ON orders(external_order_id);
CREATE INDEX idx_orders_integration_type ON orders(integration_type);

-- Unique constraint for multi-POS support (V3)
-- An order is unique by merchant_id + external_order_id + integration_type
CREATE UNIQUE INDEX uk_orders_external_order 
ON orders(merchant_id, external_order_id, integration_type);

COMMENT ON COLUMN orders.integration_type IS 'POS integration type: TREEZ or DUTCHIE';
COMMENT ON COLUMN orders.external_order_id IS 'External POS order ID (dutchie_order_id or treez_ticket_id)';

-- Create sync_logs table
CREATE TABLE sync_logs (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL,
    sync_type VARCHAR(50) NOT NULL, -- 'ORDER', 'CUSTOMER', 'POINTS'
    entity_type VARCHAR(50) NOT NULL, -- 'ORDER', 'CUSTOMER'
    entity_id VARCHAR(255) NOT NULL,
    source_system VARCHAR(50) NOT NULL, -- 'DUTCHIE', 'BOOMERANGME'
    target_system VARCHAR(50) NOT NULL, -- 'DUTCHIE', 'BOOMERANGME'
    status VARCHAR(50) NOT NULL, -- 'SUCCESS', 'FAILED', 'RETRYING'
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    request_payload JSONB,
    response_payload JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_sync_logs_merchant_id ON sync_logs(merchant_id);
CREATE INDEX idx_sync_logs_entity ON sync_logs(entity_type, entity_id);
CREATE INDEX idx_sync_logs_status ON sync_logs(status);
CREATE INDEX idx_sync_logs_created_at ON sync_logs(created_at);
CREATE INDEX idx_sync_logs_failed ON sync_logs(status, created_at) WHERE status = 'FAILED';

-- Create dead_letter_queue table
CREATE TABLE dead_letter_queue (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL,
    sync_type VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    error_message TEXT NOT NULL,
    request_payload JSONB NOT NULL,
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    last_attempt_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolved BOOLEAN DEFAULT false,
    resolved_at TIMESTAMP,
    resolution_notes TEXT
);

CREATE INDEX idx_dlq_merchant_id ON dead_letter_queue(merchant_id);
CREATE INDEX idx_dlq_resolved ON dead_letter_queue(resolved, created_at);
CREATE INDEX idx_dlq_entity ON dead_letter_queue(entity_type, entity_id);

