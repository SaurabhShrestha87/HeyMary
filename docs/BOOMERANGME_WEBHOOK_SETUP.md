# Boomerangme Webhook Configuration Guide

## Overview
This guide explains how to configure webhooks in Boomerangme to receive real-time notifications when customers install the app or create loyalty cards. When these events occur, the system will automatically create corresponding customers in Dutchie POS.

## Prerequisites
- Access to Boomerangme admin dashboard
- Your HeyMary integration service URL (e.g., `https://your-domain.com` or `http://localhost:8080` for local testing)
- Merchant ID configured in your integration (e.g., `Drop Delivery`)

## Step 1: Access Boomerangme Settings

1. Log into your Boomerangme admin dashboard
2. Navigate to **Settings** → **Webhooks** (or **Settings** → **Integrations** → **Webhooks`)
3. You should see options to configure webhook endpoints

## Step 2: Configure Webhook URL

For each Dutchie merchant/client, you need to configure a webhook URL:

**Webhook URL Format:**
```
https://your-domain.com/webhooks/boomerangme/card?config_id=1
```

**For local testing (using ngrok or similar):**
```
https://doubly-subglobular-kayden.ngrok-free.dev/webhooks/boomerangme/card?config_id=1
```

**Important:** Replace `1` with the actual database ID from the `integration_configs` table. You can find the ID by querying:
```sql
SELECT id, merchant_id FROM integration_configs WHERE merchant_id = 'Drop Delivery';
```

**Why use config_id instead of merchant_id?**
- More secure: Database IDs are not guessable business identifiers
- Faster lookup: Direct primary key lookup is more efficient
- Standard practice: Using primary keys in URLs is a common REST pattern

## Step 3: Select Events to Subscribe

Enable the following events in Boomerangme webhook settings:

- **Card Created** (`card.created`) - When a customer creates a loyalty card
- **Card Installed** (`card.installed`) - When a customer installs the app
- **Card Updated** (`card.updated`) - When customer information is updated
- **Points Updated** (`points.updated` or `card.points.updated`) - When points balance changes

## Step 4: Configure Webhook Secret (Recommended)

1. In Boomerangme webhook settings, generate or copy the **Webhook Secret**
2. Update your integration config in the database:

**Via REST API:**
```bash
PUT http://localhost:8080/api/integration-configs/Drop Delivery
Content-Type: application/json

{
  "merchantId": "Drop Delivery",
  "boomerangmeApiKey": "your-api-key",
  "dutchieApiKey": "your-dutchie-api-key",
  "boomerangmeWebhookSecret": "your-webhook-secret-from-boomerangme",
  "boomerangmeProgramId": "9389",
  "enabled": true
}
```

**Via SQL:**
```sql
UPDATE integration_configs 
SET boomerangme_webhook_secret = 'your-webhook-secret-from-boomerangme'
WHERE merchant_id = 'Drop Delivery';
```

## Step 5: How It Works

When a customer installs the Boomerangme app or creates a loyalty card:

1. **Boomerangme sends webhook** → Your webhook endpoint receives the event
2. **System validates signature** → Ensures the webhook is authentic
3. **Creates Dutchie customer** → Automatically creates customer in Dutchie POS with:
   - Email address
   - Phone number
   - First name
   - Last name
4. **Links accounts** → Links Boomerangme card ID with Dutchie customer ID in your database
5. **Syncs future updates** → Any future changes sync between both systems

## Step 6: Testing Webhooks Locally

If testing locally, you'll need to expose your local server:

### Option 1: Using ngrok (Recommended)
```bash
# Install ngrok: https://ngrok.com/download
ngrok http 8080

# Use the https URL provided by ngrok in your webhook configuration
# Example: https://abc123.ngrok.io/webhooks/boomerangme/card?merchant_id=Drop Delivery
```

### Option 2: Using localtunnel
```bash
npx localtunnel --port 8080
```

## Step 7: Verify Webhook Configuration

1. **Test webhook delivery:**
   - Create a test card in Boomerangme or have a customer install the app
   - Check the application logs:
     ```bash
     docker-compose logs app -f | grep -i webhook
     ```

2. **Verify customer creation:**
   ```sql
   SELECT * FROM customers 
   WHERE merchant_id = 'Drop Delivery' 
   ORDER BY created_at DESC 
   LIMIT 5;
   ```

3. **Check sync logs:**
   ```sql
   SELECT * FROM sync_logs 
   WHERE merchant_id = 'Drop Delivery' 
   AND sync_type = 'CUSTOMER'
   ORDER BY created_at DESC 
   LIMIT 10;
   ```

## Step 8: Multiple Merchants Configuration

If you have multiple Dutchie clients/merchants, you need to configure separate webhooks for each:

**Merchant 1 (assuming config ID = 1):**
```
https://your-domain.com/webhooks/boomerangme/card?config_id=1
```

**Merchant 2 (assuming config ID = 2):**
```
https://your-domain.com/webhooks/boomerangme/card?config_id=2
```

To find the config ID for each merchant:
```sql
SELECT id, merchant_id FROM integration_configs WHERE enabled = true;
```

Each merchant must have:
- A unique `config_id` (database primary key) in the webhook URL
- Their own integration config in the database
- Their own Boomerangme API key and program ID

## Troubleshooting

### Webhook Not Receiving Events

1. **Check webhook URL is accessible:**
   ```bash
   curl -X POST https://your-domain.com/webhooks/boomerangme/card?config_id=1 \
     -H "Content-Type: application/json" \
     -d '{"test": "data"}'
   ```
   (Replace `1` with your actual config ID)

2. **Verify config ID is correct:**
   - The `config_id` in the URL must match the `id` from your `integration_configs` table
   - Check: `SELECT id, merchant_id FROM integration_configs WHERE enabled = true;`

3. **Check application logs:**
   ```bash
   docker-compose logs app | grep -i "boomerangme.*webhook"
   ```

### Invalid Signature Error

- Ensure `boomerangme_webhook_secret` is set correctly in the database
- Verify Boomerangme is sending the signature in `X-Boomerangme-Signature` header
- Check that the webhook secret in Boomerangme matches what's in your database

### Customer Not Created in Dutchie

1. **Check if customer data is valid:**
   - Verify the webhook payload contains email, phone, or other required fields
   - Check sync logs for error messages

2. **Verify Dutchie API key:**
   - Ensure the Dutchie API key in integration config is valid
   - Check Dutchie API logs/errors

3. **Check customer already exists:**
   - The system will update existing customers instead of creating duplicates
   - Check if customer exists: `SELECT * FROM customers WHERE merchant_id = 'Drop Delivery' AND boomerangme_card_id = 'card-id';`

## Webhook Payload Examples

### Card Created/Installed Event
```json
{
  "event": "card.created",
  "id": "card-123",
  "customer": {
    "email": "customer@example.com",
    "phone": "+1234567890",
    "firstName": "John",
    "lastName": "Doe"
  },
  "programId": "9389",
  "createdAt": "2024-12-05T10:30:00Z"
}
```

### Card Updated Event
```json
{
  "event": "card.updated",
  "id": "card-123",
  "customer": {
    "email": "customer@example.com",
    "phone": "+1234567890",
    "firstName": "John",
    "lastName": "Smith"
  }
}
```

### Points Updated Event
```json
{
  "event": "points.updated",
  "card": {
    "id": "card-123",
    "points": 150
  }
}
```

## Support

If you need help:
1. Check Boomerangme API documentation: https://docs.boomerangme.cards
2. Review application logs for detailed error messages
3. Check the `sync_logs` and `dead_letter_queue` tables for sync issues
4. Verify webhook configuration in Boomerangme dashboard

