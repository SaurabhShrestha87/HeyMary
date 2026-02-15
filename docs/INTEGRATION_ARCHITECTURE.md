# Integration Architecture Documentation

## Overview

This document describes the multi-POS integration architecture that supports both Treez and Dutchie POS systems with Boomerangme loyalty cards.

## Architecture Diagram

```
┌─────────────────┐
│  Boomerangme    │
│  Webhook Events │
└────────┬────────┘
         │
         │ CardIssuedEvent
         │ CardInstalledEvent
         │ CardRemovedEvent
         │ CardUpdatedEvent
         ▼
┌──────────────────────────────┐
│  BoomerangmeWebhookController│
└──────────┬───────────────────┘
           │
           ▼
┌──────────────────────┐
│ CustomerSyncService  │
└──────────┬───────────┘
           │
           ├─────────────────┬─────────────────┐
           │                 │                 │
           ▼                 ▼                 ▼
      ┌────────┐       ┌─────────┐      ┌──────────┐
      │  Card  │◄─────►│Customer │      │Integration│
      │  Table │ 1:1   │  Table  │      │  Config   │
      └────────┘       └─────────┘      └──────────┘
                            │
                ┌───────────┴───────────┐
                │                       │
                ▼                       ▼
          ┌──────────┐            ┌──────────┐
          │  Treez   │            │ Dutchie  │
          │   POS    │            │   POS    │
          └──────────┘            └──────────┘
```

## Data Models

### 1. IntegrationType Enum

Defines the POS integration type for each merchant.

```java
public enum IntegrationType {
    TREEZ("treez", "Treez"),
    DUTCHIE("dutchie", "Dutchie");
}
```

### 2. Card Entity

Represents a Boomerangme loyalty card. One-to-one relationship with Customer.

**Key Fields:**
- `serialNumber` - Unique card identifier from Boomerangme
- `cardholderId` - Unique cardholder ID from Boomerangme
- `status` - Card status: "not_installed" or "installed"
- `cardholderEmail`, `cardholderPhone`, `cardholderFirstName`, `cardholderLastName` - Customer info
- `bonusBalance`, `countVisits` - Loyalty metrics
- `issuedAt`, `installedAt` - Lifecycle timestamps

**Database Table:** `cards`

### 3. Customer Entity

Represents a customer in the POS system with linked loyalty card.

**Key Fields:**
- `merchantId` - Merchant identifier
- `integrationType` - POS type (TREEZ or DUTCHIE)
- `posCustomerId` - Generic POS customer ID (works with any POS)
- `card` - One-to-one relationship with Card entity
- `email`, `phone`, `firstName`, `lastName` - Customer details
- `totalPoints` - Loyalty points balance

**Legacy Fields (deprecated):**
- `externalCustomerId` - Old Dutchie-specific field
- `boomerangmeCardId` - Old card reference

**Database Table:** `customers`

### 4. IntegrationConfig Entity

Configuration for each merchant's POS integration.

**Key Fields:**
- `merchantId` - Merchant identifier
- `integrationType` - POS type for this merchant (TREEZ or DUTCHIE)
- `boomerangmeApiKey`, `boomerangmeProgramId` - Boomerangme credentials
- `dutchieApiKey`, `dutchieAuthHeader` - Dutchie credentials
- `treezApiKey`, `treezAuthHeader`, `treezDispensaryId` - Treez credentials

**Database Table:** `integration_configs`

## Webhook Flow

### Event 1: CardIssuedEvent

Triggered when a card is created/issued in Boomerangme (before customer installs it).

**Payload Example:**
```json
{
  "timestamp": 1765606857,
  "event": "CardIssuedEvent",
  "data": {
    "serial_number": "452159-632-730",
    "cardholder_id": "01981ee3-9a17-70fa-b90d-66109e6d9dae",
    "status": "not_installed",
    "cardholder_email": "maria@heymary.co",
    "cardholder_phone": "12132938005",
    "cardholder_first_name": "Maria",
    "cardholder_last_name": "Sanchez",
    "bonus_balance": 0
  }
}
```

**Processing:**
1. Create or update `Card` record
2. Set status to "not_installed"
3. Store cardholder information
4. No POS sync yet (customer hasn't installed the card)

### Event 2: CardInstalledEvent

Triggered when customer installs the card on their device (Apple Wallet, Google Pay, PWA).

**Payload Example:**
```json
{
  "timestamp": 1765606860,
  "event": "CardInstalledEvent",
  "data": {
    "serial_number": "452159-632-730",
    "cardholder_id": "01981ee3-9a17-70fa-b90d-66109e6d9dae",
    "status": "installed",
    "device_type": "Google Pay",
    "cardholder_email": "maria@heymary.co",
    "cardholder_phone": "12132938005",
    "cardholder_first_name": "Maria",
    "cardholder_last_name": "Sanchez"
  }
}
```

**Processing:**
1. Update `Card` record to "installed" status
2. Set `installedAt` timestamp and `deviceType`
3. **Sync customer to POS:**
   - Check `IntegrationConfig.integrationType`
   - If **TREEZ**: Call `syncCustomerToTreez()`
   - If **DUTCHIE**: Call `syncCustomerToDutchie()`
4. Create or link `Customer` record
5. Establish one-to-one relationship between `Card` and `Customer`
6. **Treez:** Add customer to `HEYMARY_LOYALTY` group via PATCH

### Event 3: CardRemovedEvent

Triggered when customer removes/uninstalls the card from their device.

**Payload Example:**
```json
{
  "event": "CardRemovedEvent",
  "data": {
    "cardholder_id": "01981ee3-9a17-70fa-b90d-66109e6d9dae",
    "serial_number": "452159-632-730"
  }
}
```

**Processing:**
1. Update `Card` record to "not_installed" status
2. Unlink `Card` from `Customer` (set `customer.card_id` to null)
3. **Treez:** PATCH customer to remove from `HEYMARY_LOYALTY` group

## Integration-Specific Logic

### Treez Integration

**Status:** ✅ Implemented

**Method:** `CustomerSyncService.syncCustomerToTreez()`

**Current Behavior:**
1. Check if customer exists by phone (Treez format)
2. If exists: Link card to existing customer, PATCH to add `HEYMARY_LOYALTY` group
3. If not exists: Create customer via Treez API with `customer_groups: ["HEYMARY_LOYALTY"]`
4. **Card installed:** Customer added to `HEYMARY_LOYALTY` group via PATCH
5. **Card removed:** `processCardRemoved()` PATCHes to remove from `HEYMARY_LOYALTY` group

**Configuration Required:**
- `treezApiKey` - API key for Treez
- `treezAuthHeader` - Auth header (if different from API key)
- `treezDispensaryId` - Dispensary identifier

### Dutchie Integration

**Status:** ✅ Implemented

**Method:** `CustomerSyncService.syncCustomerToDutchie()`

**Current Behavior:**
1. Check if customer exists by email
2. If exists: Link card to existing customer
3. If not exists:
   - Call Dutchie API `createOrUpdateCustomer`
   - Create `Customer` record with `integrationType = DUTCHIE`
   - Set `posCustomerId` to Dutchie customer ID
   - Link to `Card`

**API Endpoint:** Dutchie EcomCustomerEdit API

**Field Mapping:**
- `FirstName` ← `cardholder_first_name`
- `LastName` ← `cardholder_last_name`
- `EmailAddress` ← `cardholder_email`
- `Phone` ← `cardholder_phone`
- `Status` ← "Active"

## Database Schema

### cards Table

```sql
CREATE TABLE cards (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(100) NOT NULL,
    serial_number VARCHAR(100) NOT NULL UNIQUE,
    cardholder_id VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL DEFAULT 'not_installed',
    
    -- Cardholder info
    cardholder_email VARCHAR(255),
    cardholder_phone VARCHAR(50),
    cardholder_first_name VARCHAR(100),
    cardholder_last_name VARCHAR(100),
    cardholder_birth_date DATE,
    
    -- Metrics
    bonus_balance INTEGER DEFAULT 0,
    count_visits INTEGER DEFAULT 0,
    
    -- Timestamps
    issued_at TIMESTAMP,
    installed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

### customers Table (Updated)

```sql
CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(100) NOT NULL,
    integration_type VARCHAR(50) NOT NULL,  -- NEW: TREEZ or DUTCHIE
    pos_customer_id VARCHAR(100),           -- NEW: Generic POS ID
    card_id BIGINT UNIQUE,                  -- NEW: FK to cards table
    
    email VARCHAR(255),
    phone VARCHAR(50),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    birth_date DATE,                        -- NEW
    total_points INTEGER DEFAULT 0,
    
    -- Legacy fields (deprecated)
    external_customer_id VARCHAR(100),
    boomerangme_card_id VARCHAR(100),
    
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_customers_card FOREIGN KEY (card_id) REFERENCES cards(id)
);

-- Unique constraint: one customer per merchant/POS combination
CREATE UNIQUE INDEX uk_customers_merchant_pos 
ON customers(merchant_id, pos_customer_id, integration_type);

-- One-to-one with card
CREATE UNIQUE INDEX uk_customers_card ON customers(card_id);
```

### integration_configs Table (Updated)

```sql
ALTER TABLE integration_configs 
ADD COLUMN integration_type VARCHAR(50) NOT NULL DEFAULT 'DUTCHIE',
ADD COLUMN treez_api_key VARCHAR(500),
ADD COLUMN treez_auth_header VARCHAR(500),
ADD COLUMN treez_dispensary_id VARCHAR(100);
```

## Configuration

### Example Integration Config (Treez)

```sql
INSERT INTO integration_configs (
    merchant_id,
    integration_type,
    boomerangme_api_key,
    boomerangme_program_id,
    treez_api_key,
    treez_dispensary_id,
    enabled
) VALUES (
    'evergreen-treez',
    'TREEZ',
    'bmg_api_key_here',
    'program_123',
    'treez_api_key_here',
    'dispensary_456',
    true
);
```

### Example Integration Config (Dutchie)

```sql
INSERT INTO integration_configs (
    merchant_id,
    integration_type,
    boomerangme_api_key,
    boomerangme_program_id,
    dutchie_api_key,
    enabled
) VALUES (
    'evergreen-dutchie',
    'DUTCHIE',
    'bmg_api_key_here',
    'program_123',
    'dutchie_api_key_here',
    true
);
```

## Migration Path

### Step 1: Run Database Migration

```bash
# The migration runs automatically on app startup via Flyway
docker-compose down
docker-compose up --build
```

### Step 2: Verify Migration

```sql
-- Check new tables and columns
\d cards
\d customers
\d integration_configs

-- Verify existing data migrated
SELECT id, merchant_id, integration_type, pos_customer_id, external_customer_id 
FROM customers LIMIT 5;
```

### Step 3: Update Integration Config

```sql
-- Set integration type for your merchant
UPDATE integration_configs 
SET integration_type = 'TREEZ',  -- or 'DUTCHIE'
    treez_api_key = 'your_api_key',
    treez_dispensary_id = 'your_dispensary_id'
WHERE merchant_id = 'your_merchant_id';
```

### Step 4: Test Webhook

1. Trigger a CardIssuedEvent from Boomerangme
2. Install the card on a device (triggers CardInstalledEvent)
3. Check logs: `tail -f logs/webhooks.log`
4. Verify database:
   ```sql
   SELECT * FROM cards WHERE serial_number = '452159-632-730';
   SELECT * FROM customers WHERE card_id = (SELECT id FROM cards WHERE serial_number = '452159-632-730');
   ```

## Next Steps (TODOs)

### High Priority
- [ ] Implement Treez API client
- [ ] Complete `syncCustomerToTreez()` method
- [ ] Add Treez customer creation API call
- [ ] Test end-to-end Treez integration

### Medium Priority
- [ ] Add Treez points sync
- [ ] Add transaction sync (Treez → Boomerangme)
- [ ] Add order polling for Treez
- [ ] Error handling for Treez API failures

### Low Priority
- [ ] Remove deprecated fields (`external_customer_id`, `boomerangme_card_id`)
- [ ] Add customer opt-out handling
- [ ] Add reward redemption events

## API Reference

### BoomerangmeWebhookController

**Endpoint:** `POST /webhooks/boomerangme/card?config_id={id}`

**Supported Events:**
- `CardIssuedEvent` - Card created/issued
- `CardInstalledEvent` - Card installed on device
- `CardRemovedEvent` / `card.removed` - Card removed/uninstalled (Treez: remove from HEYMARY_LOYALTY group)
- `card.created` / `card.updated` - Card information updated
- `points.updated` - Points balance changed
- `UserTemplateUpdatedEvent` - Template/design updated (no sync)

### CustomerSyncService

**Methods:**
- `processCardIssued(config, webhookData)` - Handle card issued event
- `processCardInstalled(config, webhookData)` - Handle card installed event
- `processCardRemoved(config, webhookData)` - Handle card removed event (Treez: remove from HEYMARY_LOYALTY group)
- `syncCustomerToTreez(config, card)` - Sync customer to Treez POS (add to HEYMARY_LOYALTY group)
- `syncCustomerToDutchie(config, card)` - Sync customer to Dutchie POS
- `syncCustomerFromBoomerangme(merchantId, cardData)` - Legacy sync method

## Troubleshooting

### Card not syncing to Treez

1. Check integration type:
   ```sql
   SELECT integration_type FROM integration_configs WHERE merchant_id = 'your_merchant';
   ```
2. Verify Treez credentials are configured
3. Check logs for TODO warnings
4. Implement Treez API integration (currently placeholder)

### Customer duplicate issues

- Unique constraint: `(merchant_id, pos_customer_id, integration_type)`
- A customer can exist in both Treez AND Dutchie for same merchant
- Cards have unique `serial_number` and `cardholder_id`

### Migration errors

- If migration fails, check Flyway history: `SELECT * FROM flyway_schema_history;`
- To revert: Drop tables and re-run migrations
- Backup data before migration: `pg_dump integrations_db > backup.sql`

## Resources

- [Boomerangme Webhook Guide](BOOMERANGME_WEBHOOK_GUIDE.md)
- [Logging Guide](LOGGING_GUIDE.md)
- [Treez API Documentation](https://docs.treez.io/) (TODO: Add link)
- [Dutchie API Documentation](https://docs.dutchie.com/)


