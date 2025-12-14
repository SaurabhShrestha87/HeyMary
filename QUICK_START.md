# Quick Start Guide

## Webhook URLs

Configure these URLs in your respective platforms:

### Boomerangme Webhook
```
https://your-domain.com/webhooks/boomerangme/card?config_id=1
```

### Treez Webhook
```
https://your-domain.com/webhooks/treez?config_id=1
```

Replace `config_id=1` with your actual integration config ID.

## Integration Setup

### 1. Database Configuration

```sql
-- Update your integration config for Treez
UPDATE integration_configs 
SET 
    integration_type = 'TREEZ',
    treez_api_key = 'your_api_key',
    treez_dispensary_id = 'your_dispensary_id',
    treez_webhook_secret = 'ESWS8ATRMS78YZO16MJWQHSB',  -- Bearer token for webhook auth
    boomerangme_api_key = 'your_bmg_key',
    boomerangme_program_id = 'your_program_id',
    enabled = true
WHERE merchant_id = 'Evergreen';
```

⚠️ **Security Note:** Always configure `treez_webhook_secret` to prevent unauthorized webhook requests.
See [TREEZ_WEBHOOK_SECURITY.md](TREEZ_WEBHOOK_SECURITY.md) for details.

### 2. Apply Database Migration

```bash
# Restart to apply V3 migration (adds cards table and updates schema)
docker-compose down
docker-compose up --build -d
```

### 3. Monitor Logs

```bash
# View all logs (Docker)
docker-compose logs -f app

# View ALL webhook logs (unified - all integrations)
tail -f logs/webhooks.log

# View Treez webhooks ONLY
tail -f logs/treez-webhooks.log

# View Boomerangme webhooks ONLY
tail -f logs/boomerangme-webhooks.log

# View errors
tail -f logs/errors.log
```

## Current Status

### ✅ Implemented

- **Boomerangme Integration**
  - ✅ CardIssuedEvent - Stores card data
  - ✅ CardInstalledEvent - Triggers POS sync
  - ✅ Customer sync to Dutchie (fully working)
  - ✅ Comprehensive logging

- **Treez Integration**
  - ✅ Webhook endpoint created
  - ✅ CUSTOMER event handler (logs data)
  - ✅ PRODUCT event handler (logs data)
  - ✅ TICKET event handler (logs data)
  - ✅ Comprehensive logging

- **Data Models**
  - ✅ IntegrationType enum (TREEZ, DUTCHIE)
  - ✅ Card model (one-to-one with Customer)
  - ✅ Customer model (updated for multi-POS)
  - ✅ IntegrationConfig (supports both POS types)

### ⚠️ TODO (Treez-specific)

- [ ] Implement customer sync from Treez to Boomerangme
- [ ] Implement transaction processing and points calculation
- [ ] Create Treez API client
- [ ] Implement bidirectional sync (Boomerangme → Treez)
- [ ] Add webhook signature validation (if Treez provides it)

## Event Flow

### Boomerangme → Treez

```
1. Customer installs Boomerangme card
   ↓
2. CardInstalledEvent webhook received
   ↓
3. Card saved to database
   ↓
4. Customer synced to Treez POS
   (TODO: Implement Treez API call)
```

### Treez → Boomerangme

```
1. Transaction occurs in Treez
   ↓
2. TICKET webhook received
   ↓
3. Transaction data logged
   ↓
4. Points calculated and synced to Boomerangme
   (TODO: Implement points sync)
```

## Testing

### Test Boomerangme Webhooks

1. Issue a card in Boomerangme
2. Install the card on a device
3. Check logs:
   ```bash
   # View Boomerangme logs only
   tail -f logs/boomerangme-webhooks.log
   
   # Or view unified webhook log
   tail -f logs/webhooks.log | grep "BOOMERANGME"
   ```

### Test Treez Webhooks

1. Configure webhook in Treez dashboard
2. Treez will send test webhook
3. Check logs:
   ```bash
   # View Treez logs only
   tail -f logs/treez-webhooks.log
   
   # Or view unified webhook log
   tail -f logs/webhooks.log | grep "TREEZ"
   ```

### Verify Database

```sql
-- Check cards
SELECT * FROM cards ORDER BY created_at DESC LIMIT 5;

-- Check customers
SELECT * FROM customers ORDER BY created_at DESC LIMIT 5;

-- Check sync logs
SELECT * FROM sync_logs ORDER BY created_at DESC LIMIT 10;

-- Check integration config
SELECT id, merchant_id, integration_type, enabled FROM integration_configs;
```

## Architecture

```
┌──────────────┐         ┌──────────────┐
│  Boomerangme │────────>│   Webhook    │
│    Events    │         │  Controller  │
└──────────────┘         └──────┬───────┘
                                │
┌──────────────┐                │         ┌────────────┐
│    Treez     │────────────────┴────────>│  Service   │
│   Events     │                          │   Layer    │
└──────────────┘                          └─────┬──────┘
                                                │
                                                ▼
                                          ┌───────────┐
                                          │ Database  │
                                          ├───────────┤
                                          │  Cards    │
                                          │ Customers │
                                          │ SyncLogs  │
                                          └───────────┘
                                                │
                                                ▼
                                          ┌───────────┐
                                          │    POS    │
                                          │  Systems  │
                                          └───────────┘
```

## Log Files

### Unified Logs (Everything)
- **logs/integrations.log** - ALL application logs
- **logs/webhooks.log** - ALL webhook logs (all integrations)
- **logs/errors.log** - ALL errors

### Integration-Specific Logs (Filtered)
- **logs/boomerangme-webhooks.log** - Boomerangme webhooks only
- **logs/treez-webhooks.log** - Treez webhooks only
- **logs/dutchie-webhooks.log** - Dutchie webhooks only

💡 **Tip:** Integration-specific logs appear in BOTH their specific file AND the unified webhooks.log

## Quick Commands

```bash
# Rebuild and restart
docker-compose up --build -d

# View ALL webhook logs (unified)
tail -f logs/webhooks.log

# View Treez webhooks only
tail -f logs/treez-webhooks.log

# View Boomerangme webhooks only
tail -f logs/boomerangme-webhooks.log

# View errors
tail -f logs/errors.log

# View multiple integrations side-by-side
tail -f logs/treez-webhooks.log logs/boomerangme-webhooks.log

# Search for specific event (unified)
grep "CardInstalledEvent" logs/webhooks.log

# Search in specific integration
grep "CUSTOMER" logs/treez-webhooks.log

# Count Treez events
grep "TREEZ WEBHOOK" logs/treez-webhooks.log | wc -l

# Check application status
docker-compose ps

# View database logs
docker-compose logs postgres

# Clean restart
docker-compose down && docker-compose up --build -d
```

## API Health Checks

```bash
# Application health
curl http://localhost:8080/health

# Treez webhook health
curl http://localhost:8080/webhooks/treez/health
```

## Documentation

- **[INTEGRATION_ARCHITECTURE.md](INTEGRATION_ARCHITECTURE.md)** - Complete architecture overview
- **[BOOMERANGME_WEBHOOK_GUIDE.md](BOOMERANGME_WEBHOOK_GUIDE.md)** - Boomerangme integration details
- **[TREEZ_WEBHOOK_GUIDE.md](TREEZ_WEBHOOK_GUIDE.md)** - Treez integration details
- **[LOGGING_GUIDE.md](LOGGING_GUIDE.md)** - Logging configuration and usage

## Support

If you encounter issues:

1. Check logs first: `tail -f logs/webhooks.log`
2. Check errors: `tail -f logs/errors.log`
3. Verify database config is correct
4. Check sync_logs table for failure details
5. Review dead_letter_queue for stuck events

## Next Steps

1. **Test Treez webhook** - Configure URL in Treez and verify test webhook received
2. **Capture real data** - Document actual field names from Treez webhooks
3. **Implement customer sync** - Complete TODO items in TreezWebhookService
4. **Implement transaction processing** - Add points calculation logic
5. **Create Treez API client** - For bidirectional sync

