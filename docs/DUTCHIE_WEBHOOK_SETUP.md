# Dutchie Webhook Configuration Guide

## Overview
This guide explains how to configure webhooks in Dutchie POS to send order and customer events to the HeyMary Integrations service.

## Prerequisites
- Access to Dutchie POS admin/merchant account
- Your HeyMary integration service URL (e.g., `https://your-domain.com` or `http://localhost:8080` for local testing)
- Merchant ID: `Drop Delivery` (or your configured merchant ID)

## Step 1: Access Dutchie Admin Settings

1. Log into your Dutchie POS account
2. Navigate to **Settings** → **Integrations** or **API Settings**
3. Look for **Webhooks** or **Webhook Configuration** section

## Step 2: Configure Order Webhook

**Webhook URL:**
```
http://your-server:8080/webhooks/dutchie/order?config_id=1
```

**For local testing (using ngrok or similar):**
```
https://doubly-subglobular-kayden.ngrok-free.dev/webhooks/boomerangme/card?config_id=1
```

**Important:** Replace `1` with the actual database ID from the `integration_configs` table. Find the ID:
```sql
SELECT id, merchant_id FROM integration_configs WHERE merchant_id = 'Drop Delivery';
```

**Why use config_id instead of merchant_id?**
- More secure: Database IDs are not guessable business identifiers
- Faster lookup: Direct primary key lookup is more efficient
- Standard practice: Using primary keys in URLs is a common REST pattern

**Events to Subscribe:**
- Order Created
- Order Completed
- Order Updated

**HTTP Method:** POST

**Headers:**
- `Content-Type: application/json`
- `X-Dutchie-Signature: <signature>` (if webhook secret is configured)

## Step 3: Configure Customer Webhook

**Webhook URL:**
```
http://your-server:8080/webhooks/dutchie/customer?config_id=1
```

**For local testing:**
```
https://your-ngrok-url.ngrok.io/webhooks/dutchie/customer?config_id=1
```

**Important:** Replace `1` with the actual database ID from the `integration_configs` table.

**Events to Subscribe:**
- Customer Created
- Customer Updated

**HTTP Method:** POST

## Step 4: Configure Webhook Secret (Optional but Recommended)

1. In Dutchie webhook settings, generate or copy the **Webhook Secret**
2. Update your integration config in the database:

**Via REST API:**
```bash
PUT http://localhost:8080/api/integration-configs/Drop Delivery
Content-Type: application/json

{
  "merchantId": "Drop Delivery",
  "boomerangmeApiKey": "9184de46e48cbcfd5305260afb6f1013",
  "dutchieApiKey": "ed7b410b422d4542b9cf848c48b1fcef",
  "dutchieWebhookSecret": "your-webhook-secret-from-dutchie",
  "boomerangmeProgramId": "9389",
  "enabled": true
}
```

**Via pgAdmin:**
```sql
UPDATE integration_configs 
SET dutchie_webhook_secret = 'your-webhook-secret-from-dutchie'
WHERE merchant_id = 'Drop Delivery';
```

## Step 5: Testing Webhooks Locally

If testing locally, you'll need to expose your local server:

### Option 1: Using ngrok (Recommended)
```bash
# Install ngrok: https://ngrok.com/download
ngrok http 8080

# Use the https URL provided by ngrok in your webhook configuration
```

### Option 2: Using localtunnel
```bash
npx localtunnel --port 8080
```

## Step 6: Verify Webhook Configuration

1. Create a test order in Dutchie
2. Check the application logs:
   ```bash
   docker-compose logs app -f
   ```
3. Verify the order appears in the database:
   ```sql
   SELECT * FROM orders ORDER BY created_at DESC LIMIT 5;
   ```
4. Check sync logs:
   ```sql
   SELECT * FROM sync_logs ORDER BY created_at DESC LIMIT 10;
   ```

## Troubleshooting

### Webhook Not Receiving Events
1. **Check webhook URL is accessible:**
   - Test with: `curl -X POST http://your-server:8080/webhooks/dutchie/order?config_id=1` (replace `1` with your config ID)
   
2. **Verify config ID is correct:**
   - The `config_id` in the URL must match the `id` from your `integration_configs` table
   - Check: `SELECT id, merchant_id FROM integration_configs WHERE enabled = true;`
   
3. **Check application logs:**
   ```bash
   docker-compose logs app | grep -i webhook
   ```

### Invalid Signature Error
- Ensure `dutchie_webhook_secret` is set correctly in the database
- Verify Dutchie is sending the signature in `X-Dutchie-Signature` header

### Order Not Syncing
1. Check if customer exists and is linked to Boomerangme
2. Verify order has a customer associated
3. Check `sync_logs` table for error messages
4. Review `dead_letter_queue` for failed syncs

## Webhook Payload Examples

### Order Webhook Payload
```json
{
  "id": "order-123",
  "customer_id": "customer-456",
  "total": "50.00",
  "created_at": "2024-12-05T10:30:00Z",
  "status": "completed"
}
```

### Customer Webhook Payload
```json
{
  "id": "customer-456",
  "email": "customer@example.com",
  "phone": "+1234567890",
  "first_name": "John",
  "last_name": "Doe"
}
```

## Support

If you need help:
1. Check Dutchie API documentation: https://api.pos.dutchie.com
2. Review application logs for detailed error messages
3. Check the `sync_logs` and `dead_letter_queue` tables for sync issues

