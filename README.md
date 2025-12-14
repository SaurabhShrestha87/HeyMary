# HeyMary Integrations Service

Integration service for syncing Boomerangme loyalty program with Dutchie POS systems.

## Features

- **Real-time webhook-based synchronization** - Bidirectional sync between POS systems (Treez/Dutchie) and Boomerangme
- **Treez Integration** - Full support for Treez POS system with customer and order webhooks
- **Dutchie Integration** - Support for Dutchie POS system (legacy)
- **Automatic points calculation** - 1:1 ratio (1 point per $1 spent) with configurable matching
- **Customer matching** - Configurable matching by phone or email between POS and Boomerangme
- **Points sync via webhooks** - Points updated from Boomerangme webhooks (CardBalanceUpdatedEvent) for single source of truth
- **Dead letter queue** - Failed syncs queued for manual review and retry
- **Comprehensive logging** - Structured logging with integration-specific log files
- **Web UI dashboards** - Visual interfaces for viewing integrations, customers, cards, and logs
- **HMAC signature validation** - Optional webhook signature validation for security
- **Admin API authentication** - API key-based authentication for admin endpoints

## Technology Stack

- Spring Boot 3.4.12
- Java 17
- PostgreSQL
- Flyway for database migrations
- Docker & Docker Compose

## Prerequisites

- Java 17+
- Maven 3.6+
- Docker & Docker Compose (for local development)
- PostgreSQL (if not using Docker)

## Local Development

### Using Docker Compose

1. Clone the repository

3. Start services:
   ```bash
   docker-compose down
   docker-compose up -d
   // OR
   docker-compose up -d --build app // Rebuilding the app so Flyway picks up the new migration
   ```

3.1 Rebuild spring app without cache 
```
docker-compose up -d --no-cache --build app
```

4. Application will be available at `http://localhost:8080`

### Merchant Configuration

Merchants need to be configured in the `integration_configs` table with:

**Required Fields:**
- `merchant_id` - Unique merchant identifier
- `integration_type` - TREEZ or DUTCHIE
- `boomerangme_api_key` - Merchant's Boomerangme API key

**Treez-Specific:**
- `treez_api_key` - Treez API key
- `treez_dispensary_id` - Treez dispensary ID
- `treez_webhook_secret` - Bearer token for Treez webhook authentication
- `customer_match_type` - PHONE or EMAIL (default: PHONE)

**Dutchie-Specific:**
- `dutchie_api_key` - Merchant's Dutchie API key
- `dutchie_webhook_secret` - Webhook secret for HMAC validation

**Optional:**
- `boomerangme_webhook_secret` - Webhook secret for HMAC validation (optional)
- `boomerangme_program_id` - Boomerangme program ID
- `default_template_id` - Default card template ID

**Admin API Authentication:**
- Admin endpoints require `X-API-Key` header
- Configure admin API key in `application.yml` or environment variable

## API Documentation

### Interactive API Documentation (Swagger)

Explore and test all API endpoints using our interactive Swagger UI:

**Swagger UI**: http://localhost:8080/swagger-ui.html

**OpenAPI Spec**: http://localhost:8080/v3/api-docs

See [Swagger Documentation Guide](docs/SWAGGER_API_DOCUMENTATION.md) for detailed usage.

### API Endpoints

#### Authentication & Credentials

- `POST /api/check-credentials` - Validate merchant credentials (array format)
- `POST /api/check-credentials-simple` - Validate merchant credentials (simple format)
- `POST /api/integration-configs/{merchantId}/access-token` - Set/update merchant access token

See [Credentials Validation API Documentation](docs/CREDENTIALS_VALIDATION_API.md) for detailed usage.

### Webhooks

- `POST /webhooks/treez?config_id={id}` - Receive webhooks from Treez (CUSTOMER, TICKET, PRODUCT events)
- `POST /webhooks/dutchie/order` - Receive order webhooks from Dutchie
- `POST /webhooks/dutchie/customer` - Receive customer webhooks from Dutchie
- `POST /webhooks/boomerangme/card?config_id={id}` - Receive card webhooks from Boomerangme (CardIssuedEvent, CardInstalledEvent, CardBalanceUpdatedEvent)

**Webhook Authentication:**
- Treez: Bearer token authentication (configured in `treez_webhook_secret`)
- Boomerangme: HMAC signature validation (optional, configured in `boomerangme_webhook_secret`)

### Integration Management APIs

- `GET /api/treez/integrations` - List Treez customer-card integrations (with pagination, search, filtering)
- `GET /api/treez/integrations/stats` - Get integration statistics (total customers, linkage rate, etc.)
- `GET /api/integration-configs` - List all merchant configurations (requires admin API key)
- `GET /api/integration-configs/{merchantId}` - Get specific merchant configuration
- `POST /api/integration-configs` - Create new merchant configuration
- `PUT /api/integration-configs/{merchantId}` - Update merchant configuration

### Sync Logs API

- `GET /api/sync-logs` - Query sync logs with filtering (merchant, status, type, date range, pagination)
- `GET /api/sync-logs/stats` - Get sync statistics (success rate, counts by status)
- `GET /api/sync-logs/{id}` - Get specific sync log details

### Health & Monitoring

- `GET /actuator/health` - Application health check
- `GET /actuator/metrics` - Application metrics
- `GET /actuator/prometheus` - Prometheus metrics endpoint

## Web UI Dashboards

The application includes web-based dashboards for monitoring and managing integrations:

### Treez Integration Dashboard
**URL:** http://localhost:8080/treez

Features:
- View all Treez customer-card integrations
- Search and filter by merchant, customer ID, email, phone
- See integration status (linked/unlinked cards)
- View customer details and card information
- Real-time statistics (total customers, linkage rate)
- Pagination support

### Logs Viewer
**URL:** http://localhost:8080/logs or http://localhost:8080/

Features:
- View application logs in real-time
- Filter by log level
- Search functionality
- Integration-specific log filtering

## Logging

The application uses structured logging with multiple log files for different purposes:

### Log Files Location
All logs are stored in the `logs/` directory (created automatically).

### Unified Logs (All Events)
- **`integrations.log`** - Complete application log (all components)
- **`webhooks.log`** - All webhook events from all integrations
- **`errors.log`** - All errors across the entire system

### Integration-Specific Logs (Filtered)
- **`boomerangme-webhooks.log`** - Boomerangme webhooks only
- **`treez-webhooks.log`** - Treez webhooks and service logs only
- **`dutchie-webhooks.log`** - Dutchie webhooks only

### Log Rotation
- Logs rotate when they reach **10MB**
- Daily rotation with date in filename
- **Retention:** 30 days (60 days for errors)
- Pattern: `{logname}-yyyy-MM-dd.{index}.log`

### Viewing Logs

**Linux/Mac:**
```bash
# All webhook activity
tail -f logs/webhooks.log

# Treez only
tail -f logs/treez-webhooks.log

# All errors
tail -f logs/errors.log

# Search for specific events
grep "CardInstalledEvent" logs/webhooks.log
```

**Windows PowerShell:**
```powershell
# View live logs
Get-Content logs\webhooks.log -Wait -Tail 50

# Treez logs
Get-Content logs\treez-webhooks.log -Wait -Tail 50

# Search logs
Select-String -Path logs\treez-webhooks.log -Pattern "CUSTOMER"
```

For detailed logging documentation, see [logs/README.md](logs/README.md)

## Database Schema

The application uses Flyway for database migrations. Tables include:

- `integration_configs` - Merchant integration configurations
  - `customer_match_type` - PHONE or EMAIL (for matching Treez customers with Boomerangme cards)
- `customers` - Customer mappings between systems
  - `treez_*` fields - Treez-specific customer data (email, phone, name, birth date)
  - `card_id` - Foreign key to cards table (one-to-one relationship)
- `cards` - Boomerangme loyalty cards
  - `serial_number` - Optional (present in webhooks, may not be in API responses)
  - `cardholder_id` - Primary identifier (required, unique)
  - `bonus_balance` - Current points balance
- `orders` - Order sync history
  - `points_earned` - Points calculated for the order
  - `points_synced` - Whether points were sent to Boomerangme
- `sync_logs` - Audit trail for sync operations
- `dead_letter_queue` - Failed syncs for manual review

## Points Calculation & Sync Flow

### Points Calculation
- **Default:** 1 point per dollar spent (1:1 ratio)
- Calculated when order is completed/paid
- Points are sent to Boomerangme via API call

### Points Sync Flow
1. **Order Processing:**
   - Order webhook received (Treez TICKET or Dutchie transaction)
   - Points calculated based on order total
   - Points sent to Boomerangme via API (`/api/v2/cards/{serial_number}/add-scores`)
   - Order marked as `points_synced = true`
   - **Note:** Local database is NOT updated at this stage

2. **Webhook Update:**
   - Boomerangme sends `CardBalanceUpdatedEvent` webhook
   - System updates `card.bonus_balance` from webhook data
   - System updates `customer.total_points` to match card balance
   - This ensures single source of truth (Boomerangme)

### Customer Matching
The system supports configurable customer matching between POS and Boomerangme:

- **PHONE** (default) - Match customers by phone number
- **EMAIL** - Match customers by email address

Configured per merchant in `integration_configs.customer_match_type`.

**Phone Number Normalization:**
- Boomerangme stores US numbers with "1" prefix (e.g., "19999999999")
- Treez may use format without prefix (e.g., "9999999999")
- System automatically normalizes phone numbers when searching

## Building

```bash
./mvnw clean package
```

## Testing

```bash
./mvnw test
```

## Recent Features & Updates

### Treez Integration
- Full support for Treez POS system
- Customer webhook processing (create/update)
- Order webhook processing (TICKET events)
- Automatic card creation and linking
- Points calculation and sync

### Webhook Enhancements
- **CardBalanceUpdatedEvent** - Handles points balance updates from Boomerangme
- Points sync via webhooks (single source of truth)
- Phone number normalization for matching
- Configurable customer matching (phone/email)

### Web UI
- Treez Integration Dashboard (`/treez`)
- Logs Viewer (`/logs`)
- Real-time statistics and filtering
- Search and pagination support

### API Enhancements
- Admin API key authentication
- Sync logs API with filtering
- Treez integrations API
- Integration statistics endpoints

## Database Management

### Dropping All Tables
For development/testing, you can drop all tables using this SQL:

```sql
DO $$ 
DECLARE 
    r RECORD;
BEGIN
    FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public') 
    LOOP
        EXECUTE 'DROP TABLE IF EXISTS ' || quote_ident(r.tablename) || ' CASCADE';
    END LOOP;
END $$;
```

**Note:** This will delete all data. Use with caution!

## License

Copyright (c) 2024 HeyMary

