-- Create integration_configs table with all columns
CREATE TABLE integration_configs (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL UNIQUE,
    boomerangme_api_key VARCHAR(500) NOT NULL,
    dutchie_api_key VARCHAR(500) NOT NULL,
    dutchie_auth_header VARCHAR(500),
    dutchie_webhook_secret VARCHAR(500),
    boomerangme_webhook_secret VARCHAR(500),
    boomerangme_program_id VARCHAR(255),
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_integration_configs_merchant_id ON integration_configs(merchant_id);

COMMENT ON COLUMN integration_configs.dutchie_auth_header IS 'Pre-computed Basic Auth header for Dutchie API (Basic <base64(apiKey:)>). Auto-computed when dutchie_api_key is saved.';
COMMENT ON COLUMN integration_configs.boomerangme_webhook_secret IS 'Webhook secret for validating Boomerangme webhook signatures';

-- Create customers table
CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL,
    dutchie_customer_id VARCHAR(255) NOT NULL,
    boomerangme_card_id VARCHAR(255),
    email VARCHAR(255),
    phone VARCHAR(50),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    total_points INTEGER DEFAULT 0,
    synced_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(merchant_id, dutchie_customer_id)
);

CREATE INDEX idx_customers_merchant_id ON customers(merchant_id);
CREATE INDEX idx_customers_dutchie_id ON customers(dutchie_customer_id);
CREATE INDEX idx_customers_boomerangme_id ON customers(boomerangme_card_id);
CREATE INDEX idx_customers_email ON customers(email);

-- Create orders table
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL,
    dutchie_order_id VARCHAR(255) NOT NULL UNIQUE,
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
CREATE INDEX idx_orders_dutchie_order_id ON orders(dutchie_order_id);
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_sync_status ON orders(points_synced, synced_at);

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

