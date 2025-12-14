# Treez Webhook Security Configuration

## Overview

Treez webhooks use **Bearer token authentication** to verify that webhook requests are genuinely coming from Treez. The token is sent in the `Authorization` header.

## Security Flow

```
┌─────────┐                          ┌──────────────────┐
│  Treez  │                          │  Your Webhook    │
│   API   │                          │    Endpoint      │
└────┬────┘                          └────────┬─────────┘
     │                                        │
     │  POST /webhooks/treez?config_id=1      │
     │  Authorization: Bearer TOKEN           │
     ├───────────────────────────────────────►│
     │                                        │
     │                                        │ 1. Extract Bearer token
     │                                        │ 2. Compare with stored secret
     │                                        │ 3. Reject if mismatch
     │                                        │
     │       200 OK or 401 Unauthorized       │
     │◄───────────────────────────────────────┤
     │                                        │
```

## Configuration Steps

### Step 1: Get Your Bearer Token from Treez

When you configure the webhook in Treez, they will provide you with a **Bearer token**. 

From your logs, I can see:
```
authorization: Bearer ESWS8ATRMS78YZO16MJWQHSB
```

The token is: `ESWS8ATRMS78YZO16MJWQHSB`

### Step 2: Apply Database Migration

The migration will automatically run on next restart:

```bash
docker-compose down
docker-compose up --build -d
```

This adds the `treez_webhook_secret` column to `integration_configs`.

### Step 3: Configure the Token in Database

```sql
-- Update your integration config with the Bearer token
UPDATE integration_configs 
SET treez_webhook_secret = 'ESWS8ATRMS78YZO16MJWQHSB'  -- Your actual token from Treez
WHERE id = 1;  -- Your config ID

-- Verify it's set
SELECT id, merchant_id, integration_type, treez_webhook_secret 
FROM integration_configs 
WHERE id = 1;
```

### Step 4: Test Webhook Security

#### Test 1: Valid Token (Should Work)
```bash
curl -X POST "https://your-domain.com/webhooks/treez?config_id=1" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ESWS8ATRMS78YZO16MJWQHSB" \
  -d '{"test":"test"}'
```

Expected response: `200 OK - Webhook received`

#### Test 2: Invalid Token (Should Fail)
```bash
curl -X POST "https://your-domain.com/webhooks/treez?config_id=1" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer WRONG_TOKEN" \
  -d '{"test":"test"}'
```

Expected response: `401 Unauthorized - Invalid authorization token`

#### Test 3: No Token (Should Fail)
```bash
curl -X POST "https://your-domain.com/webhooks/treez?config_id=1" \
  -H "Content-Type: application/json" \
  -d '{"test":"test"}'
```

Expected response: `401 Unauthorized - Authorization required`

## What Happens in the Logs

### Valid Token
```
Merchant ID: Evergreen
Bearer token validation: VALID
✓ Bearer token validated successfully
--- Event Processing ---
```

### Invalid Token
```
Merchant ID: Evergreen
Bearer token validation: INVALID
Expected token starts with: ESWS8ATRMS...
Provided token starts with: WRONG_TOKE...
Invalid Bearer token for Treez webhook from merchant: Evergreen
```

### No Token Configured (Warning)
```
Merchant ID: Evergreen
Treez webhook secret not configured - skipping authorization validation
SECURITY WARNING: Configure treez_webhook_secret in integration_configs for merchant: Evergreen
```

## Security Benefits

✅ **Prevent Unauthorized Access** - Only Treez can trigger your webhook
✅ **Protect Against Spoofing** - Attackers can't fake Treez webhooks
✅ **Simple Implementation** - Just Bearer token comparison
✅ **Easy to Rotate** - Update token in database if compromised

## Token Format

Treez sends the token in standard Bearer format:
```
Authorization: Bearer ESWS8ATRMS78YZO16MJWQHSB
```

The application handles both formats:
- `Bearer ESWS8ATRMS78YZO16MJWQHSB` (standard format)
- `ESWS8ATRMS78YZO16MJWQHSB` (raw token)

## Token Storage

- Stored in: `integration_configs.treez_webhook_secret`
- Type: VARCHAR(500)
- Encrypted: Not yet (TODO: Consider encryption at rest)
- Access: Only via database or application

## Best Practices

### ✅ DO:
- Store the token immediately after Treez provides it
- Test webhook security after configuration
- Monitor logs for failed authentication attempts
- Rotate token if you suspect compromise
- Keep token secret (don't commit to git)

### ❌ DON'T:
- Share the token publicly
- Commit token to version control
- Use the same token across multiple environments
- Ignore authentication failures in logs

## Rotating the Token

If you need to change the token:

1. **Get new token from Treez dashboard**
2. **Update database:**
   ```sql
   UPDATE integration_configs 
   SET treez_webhook_secret = 'NEW_TOKEN_HERE'
   WHERE id = 1;
   ```
3. **No restart needed** - takes effect immediately
4. **Test with new token:**
   ```bash
   curl -X POST "https://your-domain.com/webhooks/treez?config_id=1" \
     -H "Authorization: Bearer NEW_TOKEN_HERE" \
     -d '{"test":"test"}'
   ```

## Troubleshooting

### Webhooks Being Rejected (401)

**Check 1: Token Configured?**
```sql
SELECT treez_webhook_secret FROM integration_configs WHERE id = 1;
```

If NULL, configure it!

**Check 2: Token Matches?**
```sql
-- Compare with token from Treez webhook logs
SELECT 
    treez_webhook_secret,
    LENGTH(treez_webhook_secret) as token_length
FROM integration_configs 
WHERE id = 1;
```

**Check 3: Check Logs**
```bash
# View authentication attempts
grep "Bearer token validation" logs/treez-webhooks.log

# View failures
grep "Invalid Bearer token" logs/treez-webhooks.log
```

### Warning: Token Not Configured

If you see this in logs:
```
SECURITY WARNING: Configure treez_webhook_secret in integration_configs
```

This means webhooks are being accepted **without authentication**! Configure the token immediately.

### Token Appears Correct But Still Fails

Check for:
- Extra whitespace in database value
- Wrong token format (should not include "Bearer " prefix in database)
- Case sensitivity (tokens are case-sensitive)

## Multiple Environments

For different environments (dev, staging, prod):

```sql
-- Development
UPDATE integration_configs 
SET treez_webhook_secret = 'DEV_TOKEN_HERE'
WHERE merchant_id = 'Evergreen' AND environment = 'dev';

-- Production
UPDATE integration_configs 
SET treez_webhook_secret = 'PROD_TOKEN_HERE'
WHERE merchant_id = 'Evergreen' AND environment = 'prod';
```

Each environment should have its own unique token.

## Monitoring

### Count Failed Authentication Attempts
```bash
grep -c "Invalid Bearer token" logs/treez-webhooks.log
```

### View Recent Authentication Events
```bash
grep "Bearer token validation" logs/treez-webhooks.log | tail -20
```

### Alert on Repeated Failures
```bash
# If you see many failures, investigate
if [ $(grep -c "Invalid Bearer token" logs/treez-webhooks.log) -gt 10 ]; then
    echo "⚠️ Multiple authentication failures detected!"
fi
```

## Database Schema

```sql
-- Integration config with Treez webhook secret
CREATE TABLE integration_configs (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(100) NOT NULL,
    integration_type VARCHAR(50) NOT NULL,
    treez_webhook_secret VARCHAR(500),  -- Bearer token
    -- ... other fields
);
```

## Quick Commands Reference

```bash
# Configure token
psql -d integrations_db -c "UPDATE integration_configs SET treez_webhook_secret = 'YOUR_TOKEN' WHERE id = 1;"

# Verify token
psql -d integrations_db -c "SELECT treez_webhook_secret FROM integration_configs WHERE id = 1;"

# Test webhook
curl -X POST "http://localhost:8080/webhooks/treez?config_id=1" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{"test":"test"}'

# View authentication logs
tail -f logs/treez-webhooks.log | grep "Bearer token"

# Count auth failures
grep -c "Invalid Bearer token" logs/treez-webhooks.log
```

## Related Documentation

- [TREEZ_WEBHOOK_GUIDE.md](TREEZ_WEBHOOK_GUIDE.md) - Complete Treez integration guide
- [INTEGRATION_ARCHITECTURE.md](INTEGRATION_ARCHITECTURE.md) - System architecture
- [QUICK_START.md](QUICK_START.md) - Quick start guide

