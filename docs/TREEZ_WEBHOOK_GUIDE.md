# Treez Webhook Integration Guide

## Overview

This guide covers the Treez POS webhook integration with Boomerangme loyalty system. The integration captures customer, product, and transaction events from Treez and syncs them with Boomerangme for loyalty program management.

## Webhook URL

Configure this URL in your Treez dashboard:

```
https://your-domain.com/webhooks/treez?config_id=1
```

Replace `config_id=1` with your actual integration config ID from the database.

## Treez Webhook Structure

Treez sends webhooks in the following format:

```json
{
  "root": {
    "event_type": "CUSTOMER",  // Can be: CUSTOMER, PRODUCT, or TICKET
    "data": {
      // Event-specific data here
    }
  }
}
```

## Supported Event Types

### 1. CUSTOMER Events

Triggered when customer data changes in Treez (create, update, delete).

**Endpoint:** `POST /webhooks/treez?config_id={ID}`

**Expected Data Structure:** (To be confirmed with actual webhooks)
```json
{
  "root": {
    "event_type": "CUSTOMER",
    "data": {
      "customer_id": "12345",
      "email": "customer@example.com",
      "phone": "+1234567890",
      "first_name": "John",
      "last_name": "Doe",
      "created_at": "2025-12-13T10:00:00Z",
      "updated_at": "2025-12-13T10:00:00Z"
    }
  }
}
```

**Current Implementation:**
- ✅ Receives and logs customer event data
- ✅ Extracts customer ID
- ⚠️ **TODO**: Sync customer to Boomerangme
- ⚠️ **TODO**: Link with existing Boomerangme cards

### 2. PRODUCT Events

Triggered when product or inventory changes occur in Treez.

**Expected Data Structure:** (To be confirmed)
```json
{
  "root": {
    "event_type": "PRODUCT",
    "data": {
      "product_id": "prod_123",
      "name": "Product Name",
      "sku": "SKU123",
      "price": 29.99,
      "inventory_count": 100,
      "updated_at": "2025-12-13T10:00:00Z"
    }
  }
}
```

**Current Implementation:**
- ✅ Receives and logs product event data
- ℹ️ Currently logged only (not processed)
- 💡 Can be implemented if product sync is needed

### 3. TICKET Events (Transactions/Orders)

Triggered when transactions occur in Treez. "Ticket" is Treez terminology for a transaction/order.

**Expected Data Structure:** (To be confirmed)
```json
{
  "root": {
    "event_type": "TICKET",
    "data": {
      "ticket_id": "ticket_789",
      "customer_id": "12345",
      "total_amount": 129.99,
      "subtotal": 119.99,
      "tax": 10.00,
      "items": [
        {
          "product_id": "prod_123",
          "quantity": 2,
          "price": 59.99
        }
      ],
      "status": "completed",
      "created_at": "2025-12-13T10:00:00Z"
    }
  }
}
```

**Current Implementation:**
- ✅ Receives and logs ticket/transaction data
- ⚠️ **TODO**: Calculate loyalty points based on transaction
- ⚠️ **TODO**: Sync points to Boomerangme
- ⚠️ **TODO**: Link transaction to customer's Boomerangme card

## Configuration

### Database Setup

Your integration config should have Treez settings:

```sql
-- Update your existing config
UPDATE integration_configs 
SET 
    integration_type = 'TREEZ',
    treez_api_key = 'your_treez_api_key',
    treez_dispensary_id = 'your_dispensary_id',
    treez_webhook_secret = 'YOUR_BEARER_TOKEN',  -- For webhook authentication
    -- Keep Boomerangme settings
    boomerangme_api_key = 'your_bmg_key',
    boomerangme_program_id = 'your_program_id'
WHERE id = 1;  -- Your config ID
```

### Webhook Security

**IMPORTANT:** Treez uses Bearer token authentication. Configure the webhook secret to secure your endpoint.

From your webhook test, the Bearer token is:
```
ESWS8ATRMS78YZO16MJWQHSB
```

See [TREEZ_WEBHOOK_SECURITY.md](TREEZ_WEBHOOK_SECURITY.md) for complete security configuration guide.

### Integration Config Fields

| Field | Description | Required | Security |
|-------|-------------|----------|----------|
| `integration_type` | Set to `TREEZ` | Yes | - |
| `treez_api_key` | Treez API key | Yes (for API calls) | 🔒 Secret |
| `treez_auth_header` | Treez auth header | Optional | 🔒 Secret |
| `treez_dispensary_id` | Your dispensary ID | Yes | - |
| `treez_webhook_secret` | Bearer token for webhook auth | **Highly Recommended** | 🔒 Secret |
| `boomerangme_api_key` | Boomerangme API key | Yes | 🔒 Secret |
| `boomerangme_program_id` | Boomerangme program ID | Yes | - |
| `boomerangme_webhook_secret` | Boomerangme webhook secret | Recommended | 🔒 Secret |

## Webhook Testing

### Test Webhook Reception

Treez typically sends a test webhook when you configure the URL. Monitor your logs:

```bash
# View live webhook logs
tail -f logs/webhooks.log

# Filter for Treez webhooks only
tail -f logs/webhooks.log | grep "TREEZ WEBHOOK"

# View all Treez events in Docker logs
docker-compose logs -f app | grep "TREEZ"
```

### What You'll See in Logs

```
=== TREEZ WEBHOOK RECEIVED ===
Config ID: 1
Signature Header: NOT PROVIDED
--- All Headers ---
host: your-domain.com
user-agent: Treez-Webhook/1.0
content-type: application/json
--- Raw Payload ---
Payload length: 234 bytes
Raw payload: {"root":{"event_type":"CUSTOMER","data":{...}}}
--- Parsed JSON (prettified) ---
{
  "root" : {
    "event_type" : "CUSTOMER",
    "data" : {
      "customer_id" : "12345",
      ...
    }
  }
}
--- Event Processing ---
Event Type: CUSTOMER
Merchant ID: Evergreen
Available fields in root: [event_type, data]
ACTION: Processing CUSTOMER event from Treez
--- Customer Event Data ---
Customer data:
{
  "customer_id" : "12345",
  "email" : "customer@example.com",
  ...
}
Available customer fields: [customer_id, email, phone, first_name, last_name]
Extracted customer ID: 12345
TODO: Implement Treez customer sync to Boomerangme
Customer event processed successfully
=== TREEZ WEBHOOK PROCESSING COMPLETED SUCCESSFULLY ===
```

## Event Processing Flow

### Customer Event Flow

```
Treez → Webhook → TreezWebhookController
                        ↓
                TreezWebhookService.processCustomerEvent()
                        ↓
                    [TODO: Implementation]
                        ↓
                1. Find/Create Customer in DB
                2. Link with Boomerangme Card (if exists)
                3. Sync customer data to Boomerangme
                4. Update loyalty points
```

### Transaction Event Flow

```
Treez → Webhook → TreezWebhookController
                        ↓
                TreezWebhookService.processTicketEvent()
                        ↓
                    [TODO: Implementation]
                        ↓
                1. Extract transaction details
                2. Find customer by ID
                3. Calculate loyalty points earned
                4. Sync points to Boomerangme
                5. Log transaction
```

## Implementation TODOs

### High Priority

- [ ] **Implement Customer Sync**
  - Extract customer data from webhook
  - Find or create customer in database
  - Link with Boomerangme card (match by email/phone)
  - Sync customer details to Boomerangme

- [ ] **Implement Transaction Processing**
  - Extract transaction data from TICKET event
  - Calculate loyalty points based on purchase amount
  - Find customer's Boomerangme card
  - Award points via Boomerangme API

- [ ] **Create Treez API Client**
  - Implement API calls to Treez
  - Customer creation/update methods
  - Product lookup methods
  - Transaction history methods

### Medium Priority

- [ ] **Points Calculation Logic**
  - Define points earning rules
  - Calculate based on transaction amount
  - Handle different product categories
  - Apply multipliers/bonuses

- [ ] **Bidirectional Sync**
  - When Boomerangme card installed → Create customer in Treez
  - When customer updates in Treez → Update Boomerangme
  - When points redeemed in Boomerangme → Update Treez

- [ ] **Error Handling**
  - Retry failed syncs
  - Dead letter queue for failed events
  - Alert on repeated failures

### Low Priority

- [ ] **Webhook Signature Validation**
  - Confirm if Treez provides signature
  - Implement signature validation
  - Store webhook secret in config

- [ ] **Product Event Processing**
  - Sync product inventory changes (if needed)
  - Update product catalogs

## Data Mapping

### Customer: Treez → Boomerangme

| Treez Field | Boomerangme Field | Notes |
|-------------|-------------------|-------|
| `customer_id` | `externalId` | Unique customer ID |
| `email` | `email` | Customer email |
| `phone` | `phone` | Customer phone |
| `first_name` | `firstName` | First name |
| `last_name` | `lastName` | Last name |
| - | `cardholderId` | From existing card if linked |

### Transaction: Treez → Points

| Treez Field | Calculation | Notes |
|-------------|-------------|-------|
| `total_amount` | Points earned | e.g., $1 = 1 point |
| `items[]` | Bonus points | Category-based bonuses |
| `customer_id` | Link to card | Find customer's card |

## API Endpoints

### Health Check

```bash
GET /webhooks/treez/health
```

Response:
```json
{
  "status": "healthy",
  "service": "treez-webhook",
  "timestamp": "1734087600000"
}
```

### Main Webhook Endpoint

```bash
POST /webhooks/treez?config_id=1
Content-Type: application/json

{
  "root": {
    "event_type": "CUSTOMER",
    "data": { ... }
  }
}
```

## Testing Checklist

- [ ] Configure webhook URL in Treez dashboard
- [ ] Verify test webhook is received and logged
- [ ] Test CUSTOMER event with real data
- [ ] Test TICKET event with real transaction
- [ ] Test PRODUCT event (if needed)
- [ ] Verify data appears in logs with all fields
- [ ] Document actual field names from Treez
- [ ] Update field extraction logic if needed
- [ ] Test customer linking with Boomerangme cards
- [ ] Test points calculation and sync

## Troubleshooting

### Webhook not received

1. Check Treez webhook configuration
2. Verify URL is accessible from internet
3. Check firewall/security rules
4. Test with ngrok or similar tool
5. Check application logs for errors

### Event not processing

1. Check logs: `tail -f logs/webhooks.log`
2. Verify `integration_type = 'TREEZ'` in config
3. Check config_id parameter matches database
4. Look for error messages in `logs/errors.log`
5. Check sync_logs table for failed syncs

### Data not syncing

1. Verify TODO implementations are complete
2. Check field names match actual webhook data
3. Test Boomerangme API connectivity
4. Check API credentials in integration config
5. Review dead_letter_queue for failed syncs

## Log Queries

### View Recent Treez Events

```sql
SELECT 
    id,
    merchant_id,
    sync_type,
    entity_id,
    status,
    created_at,
    error_message
FROM sync_logs
WHERE source_system = 'TREEZ'
ORDER BY created_at DESC
LIMIT 20;
```

### Count Events by Type

```sql
SELECT 
    sync_type,
    status,
    COUNT(*) as count
FROM sync_logs
WHERE source_system = 'TREEZ'
GROUP BY sync_type, status;
```

### Failed Syncs

```sql
SELECT 
    entity_id,
    sync_type,
    error_message,
    created_at
FROM sync_logs
WHERE source_system = 'TREEZ' 
  AND status = 'FAILED'
ORDER BY created_at DESC;
```

## Next Steps

1. **Test with Real Webhooks**
   - Trigger each event type in Treez
   - Capture actual webhook payloads
   - Document field names and structures

2. **Implement Customer Sync**
   - Use captured webhook data structure
   - Implement CustomerSyncService methods
   - Test with real customers

3. **Implement Transaction Processing**
   - Define points calculation rules
   - Implement PointsSyncService for Treez
   - Test points awarding

4. **Create Treez API Client**
   - Research Treez API documentation
   - Implement necessary API calls
   - Add error handling

## Related Documentation

- [Integration Architecture](INTEGRATION_ARCHITECTURE.md)
- [Boomerangme Webhook Guide](BOOMERANGME_WEBHOOK_GUIDE.md)
- [Logging Guide](LOGGING_GUIDE.md)
- [Treez API Documentation](https://docs.treez.io/) (if available)

