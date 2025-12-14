# Getting Started with HeyMary Integration System

## What is This System?

A loyalty program integration that connects:
- **Treez POS** (Point of Sale system)
- **Boomerangme** (Digital loyalty cards)
- Automatically awards points when customers make purchases

## Quick Overview

```
Customer makes purchase in Treez POS
              ↓
    Webhook sent to your system
              ↓
  System calculates loyalty points
              ↓
Points added to customer's Boomerangme card
```

## What's Already Built ✅

### Infrastructure
- ✅ Multi-POS support (ready for Treez, Dutchie, and future integrations)
- ✅ Secure webhook endpoints with Bearer token authentication
- ✅ Database schema for customers, cards, orders
- ✅ Comprehensive logging system (unified + per-integration logs)
- ✅ Error handling with retry logic and dead letter queue

### Webhooks Implemented
- ✅ **Boomerangme webhooks:** CardIssued, CardInstalled
- ✅ **Treez webhooks:** CUSTOMER, PRODUCT, TICKET (with logging)
- ✅ **Security:** Bearer token validation for Treez

### Logging
- ✅ Separate log files per integration (treez-webhooks.log, boomerangme-webhooks.log)
- ✅ Unified logs to see everything (webhooks.log)
- ✅ Error-only logs (errors.log)
- ✅ All logs also in Docker console

## What Needs to Be Built 🔨

### Priority 1: Boomerangme API Client
Communicate with Boomerangme to create cards and award points.

**Methods needed:**
- Create card for customer
- Add points to card
- Subtract points from card
- Get card information

### Priority 2: Customer Sync
When customer created in Treez → Create loyalty card in Boomerangme

### Priority 3: Points Awarding
When order completed in Treez → Award loyalty points to customer's card

### Priority 4: Order Cancellations
When order canceled in Treez → Reverse points if already awarded

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                    HeyMary System                        │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  ┌─────────────┐         ┌──────────────┐             │
│  │   Treez     │◄───────►│   Webhooks   │             │
│  │    POS      │         │  Controllers │             │
│  └─────────────┘         └──────┬───────┘             │
│                                  │                      │
│  ┌─────────────┐         ┌──────▼───────┐             │
│  │ Boomerangme │◄───────►│   Service    │             │
│  │   Loyalty   │         │    Layer     │             │
│  └─────────────┘         └──────┬───────┘             │
│                                  │                      │
│                          ┌───────▼────────┐            │
│                          │    Database    │            │
│                          │  - Customers   │            │
│                          │  - Cards       │            │
│                          │  - Orders      │            │
│                          │  - Sync Logs   │            │
│                          └────────────────┘            │
└──────────────────────────────────────────────────────────┘
```

## Webhook URLs

Configure these in your respective platforms:

### Treez Webhook
```
https://your-domain.com/webhooks/treez?config_id=1
```
**Auth:** Bearer token (configure in database)

### Boomerangme Webhook
```
https://your-domain.com/webhooks/boomerangme/card?config_id=1
```
**Auth:** HMAC signature (optional)

## Database Configuration

Your `integration_configs` table needs:

```sql
UPDATE integration_configs 
SET 
    -- Integration type
    integration_type = 'TREEZ',
    
    -- Treez credentials
    treez_api_key = 'your_treez_api_key',
    treez_dispensary_id = 'partnersandbox3',
    treez_webhook_secret = 'ESWS8ATRMS78YZO16MJWQHSB',
    
    -- Boomerangme credentials
    boomerangme_api_key = 'your_bmg_api_key',
    boomerangme_program_id = 'your_program_id',
    boomerangme_webhook_secret = 'your_bmg_webhook_secret',
    
    -- Points rules (to be added)
    points_per_dollar = 1.0,
    minimum_purchase_for_points = 5.0,
    
    enabled = true
WHERE merchant_id = 'Evergreen';
```

## Running the System

### Start Everything
```bash
docker-compose up --build -d
```

### View Logs
```bash
# All webhooks (unified view)
tail -f logs/webhooks.log

# Treez webhooks only
tail -f logs/treez-webhooks.log

# Boomerangme webhooks only
tail -f logs/boomerangme-webhooks.log

# All errors
tail -f logs/errors.log

# Docker console logs
docker-compose logs -f app
```

### Check Health
```bash
# Application health
curl http://localhost:8080/health

# Treez webhook health
curl http://localhost:8080/webhooks/treez/health
```

## Testing Webhooks

### Test Treez Webhook
```bash
curl -X POST "http://localhost:8080/webhooks/treez?config_id=1" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ESWS8ATRMS78YZO16MJWQHSB" \
  -d '{"root":{"event_type":"CUSTOMER","data":{"customer_id":"123"}}}'
```

### Test Boomerangme Webhook
```bash
curl -X POST "http://localhost:8080/webhooks/boomerangme/card?config_id=1" \
  -H "Content-Type: application/json" \
  -d '{"event":"CardInstalledEvent","data":{"serial_number":"123"}}'
```

## Current Status of Webhooks

### Boomerangme Webhooks: ✅ Partially Working
- ✅ Receives CardIssuedEvent (card created)
- ✅ Receives CardInstalledEvent (card installed on phone)
- ✅ Stores card data in database
- ⚠️ TODO: Create customer in Treez when card installed

### Treez Webhooks: ⚠️ Receiving, Not Processing
- ✅ Receives CUSTOMER events
- ✅ Receives TICKET events (orders)
- ✅ Receives PRODUCT events
- ✅ Bearer token authentication working
- ⚠️ TODO: Create Boomerangme card when customer created
- ⚠️ TODO: Award points when order completed

## What Webhooks You Need from Treez

Based on your use case, configure these in Treez:

1. **✅ Customer Webhook** - ON
   - Trigger: On Creation, On Profile Update
   - Why: Sync customers to Boomerangme

2. **✅ Ticket Webhook** - ON  
   - Trigger: On Completion ONLY
   - Why: Award loyalty points

3. **✅ Ticket Status Webhook** - ON
   - Trigger: COMPLETED, CANCELED
   - Why: Award points and handle cancellations

4. **❌ Product Webhook** - OFF (not needed for basic loyalty)

## Key Files to Understand

### Controllers (Receive Webhooks)
- `TreezWebhookController.java` - Treez webhooks
- `BoomerangmeWebhookController.java` - Boomerangme webhooks
- `DutchieWebhookController.java` - Dutchie webhooks (future)

### Services (Business Logic)
- `TreezWebhookService.java` - Process Treez events
- `CustomerSyncService.java` - Sync customers between systems
- `PointsSyncService.java` - Handle points calculations
- `BoomerangmeApiClient.java` - Call Boomerangme API (needs implementation)

### Models (Database)
- `Card.java` - Boomerangme loyalty cards
- `Customer.java` - Customers (one-to-one with cards)
- `IntegrationConfig.java` - Configuration per merchant
- `SyncLog.java` - Track all sync operations

### Database Migrations
- `V1__create_integration_configs_table.sql` - Initial schema
- `V2__insert_sample_merchant_config.sql` - Sample data
- `V3__add_cards_and_update_customers_schema.sql` - Cards support
- `V4__add_treez_webhook_secret.sql` - Treez auth

## Documentation Index

### Getting Started
- **[GETTING_STARTED.md](GETTING_STARTED.md)** ← You are here
- **[QUICK_START.md](QUICK_START.md)** - Quick commands and setup
- **[IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md)** - Detailed task breakdown

### Integration Guides
- **[LOYALTY_INTEGRATION_PLAN.md](LOYALTY_INTEGRATION_PLAN.md)** - Complete integration design
- **[INTEGRATION_ARCHITECTURE.md](INTEGRATION_ARCHITECTURE.md)** - System architecture

### Webhook Guides
- **[TREEZ_WEBHOOK_GUIDE.md](TREEZ_WEBHOOK_GUIDE.md)** - Treez integration details
- **[TREEZ_WEBHOOK_SECURITY.md](TREEZ_WEBHOOK_SECURITY.md)** - Bearer token auth
- **[BOOMERANGME_WEBHOOK_GUIDE.md](BOOMERANGME_WEBHOOK_GUIDE.md)** - Boomerangme integration

### Technical Docs
- **[LOGGING_GUIDE.md](LOGGING_GUIDE.md)** - Logging system details
- **[LOG_ARCHITECTURE.md](LOG_ARCHITECTURE.md)** - Log file structure
- **[digitalwallet_boomerangme.OAPI.doc.json](digitalwallet_boomerangme.OAPI.doc.json)** - Boomerangme API spec

## Next Steps

### For Developers

1. **Read the Integration Plan**
   - [LOYALTY_INTEGRATION_PLAN.md](LOYALTY_INTEGRATION_PLAN.md)
   - Understand the complete flow

2. **Review the Roadmap**
   - [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md)
   - See what needs to be built

3. **Start with Phase 1**
   - Implement Boomerangme API Client
   - Create card creation methods
   - Add points awarding methods

4. **Test with Sandbox**
   - Use Treez sandbox
   - Use Boomerangme test program
   - Verify end-to-end flow

### For Configuration

1. **Set up Treez webhooks**
   - Add webhook URL to Treez dashboard
   - Configure Bearer token
   - Test connection

2. **Set up Boomerangme**
   - Get API credentials
   - Create program/template
   - Test API access

3. **Configure Database**
   - Update integration_configs
   - Set points rules
   - Enable integration

## Common Commands

```bash
# Start system
docker-compose up --build -d

# View Treez webhooks
tail -f logs/treez-webhooks.log

# View all webhooks
tail -f logs/webhooks.log

# Check for errors
tail -f logs/errors.log

# Restart system
docker-compose down && docker-compose up --build -d

# Check database
docker exec -it integrations-postgres psql -U postgres -d integrations_db

# View sync logs
SELECT * FROM sync_logs ORDER BY created_at DESC LIMIT 10;

# View customers
SELECT * FROM customers ORDER BY created_at DESC LIMIT 10;

# View cards
SELECT * FROM cards ORDER BY created_at DESC LIMIT 10;
```

## Getting Help

### Check Logs First
```bash
# Recent errors
tail -50 logs/errors.log

# Recent Treez activity
tail -50 logs/treez-webhooks.log

# Failed syncs in database
SELECT * FROM sync_logs WHERE status = 'FAILED' ORDER BY created_at DESC LIMIT 10;
```

### Common Issues

**Issue:** Webhook not received
- Check webhook URL is correct
- Check ngrok or domain is accessible
- Check Bearer token is configured
- Check logs for connection attempts

**Issue:** Points not awarded
- Check order status is COMPLETED
- Check customer has Boomerangme card
- Check points calculation logic
- Check Boomerangme API logs

**Issue:** Customer not synced
- Check CUSTOMER webhook is configured
- Check customer data is complete
- Check Boomerangme API credentials
- Check sync_logs for errors

## Support Contacts

- Treez Support: [Treez contact]
- Boomerangme Support: [Boomerangme contact]
- Development Team: [Your team]

---

**Ready to start?** Read [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) for detailed tasks! 🚀

