# Boomerangme Webhook Integration Guide

## Current Status

✅ Webhook endpoint is set up and logging all incoming data  
✅ Template update events are being received  
⚠️ Customer/card events need testing to discover data structure

## Webhook URL

Configure this URL in your Boomerangme dashboard:
```
https://your-domain.com/webhooks/boomerangme/card?config_id=1
```

## Events Received So Far

### 1. **UserTemplateUpdatedEvent** ✅ (Admin action - template changes)
```json
{
  "timestamp": 1765579011,
  "event": "UserTemplateUpdatedEvent",
  "data": {
    "id": 979611,
    "name": "TREEZ integration test",
    "user": "maria@ddropdelivery.com",
    "timestamp": 1765579010
  }
}
```
**Action:** This is logged but not synced (no customer data to sync)

## Events to Test and Discover

### Priority 1: Customer Card Events (HIGH PRIORITY)
These events should trigger customer sync to Dutchie:

| Event Name (Expected) | Trigger Action | What to Test |
|----------------------|----------------|--------------|
| `CardInstalledEvent` or `card.installed` | Customer installs loyalty app for first time | Have a new customer download and install the app |
| `CardCreatedEvent` or `card.created` | New loyalty card created | Register a new customer in Boomerangme |
| `CardUpdatedEvent` or `card.updated` | Customer updates profile | Edit customer email, phone, or name |

**Expected Data Structure:**
```json
{
  "event": "CardInstalledEvent",
  "card": {
    "id": "card_123",
    "customer": {
      "email": "customer@example.com",
      "phone": "+1234567890",
      "first_name": "John",
      "last_name": "Doe"
    },
    "points": 100
  }
}
```

### Priority 2: Points Events (HIGH PRIORITY)
These events should trigger points sync:

| Event Name (Expected) | Trigger Action | What to Test |
|----------------------|----------------|--------------|
| `PointsUpdatedEvent` or `points.updated` | Points balance changes | Award points to a customer manually |
| `CardPointsUpdatedEvent` or `card.points.updated` | Card points updated | Make a test purchase to earn points |
| `PointsRedeemedEvent` or `points.redeemed` | Customer redeems points | Have customer redeem a reward |

**Expected Data Structure:**
```json
{
  "event": "PointsUpdatedEvent",
  "card": {
    "id": "card_123",
    "points": 250,
    "previous_points": 100
  }
}
```

### Priority 3: Transaction Events (MEDIUM PRIORITY)
These might be sent when purchases are made:

| Event Name (Expected) | Trigger Action | What to Test |
|----------------------|----------------|--------------|
| `TransactionCreatedEvent` or `transaction.created` | New purchase/transaction | Complete a test transaction |
| `PurchaseCompletedEvent` or `purchase.completed` | Purchase finalized | Make a purchase in POS |

### Priority 4: Other Events (LOW PRIORITY)
- `CardDeletedEvent` / `card.deleted`: Customer uninstalls app or deletes card
- `RewardRedeemedEvent` / `reward.redeemed`: Customer claims a reward
- `CustomerOptOutEvent` / `customer.opt_out`: Customer opts out of marketing

## How to Discover Event Data Structures

### Step 1: Check Boomerangme Documentation
- Look for their API/Webhook documentation
- Check for event types and payload examples
- Look for webhook signature validation details

### Step 2: Test Real Customer Actions
For each event type you want to handle:

1. **Perform the action** (e.g., install app, make purchase)
2. **Check your logs** at `webhook.log` or application logs
3. **Look for the prettified JSON output** - it shows the complete structure
4. **Copy the event structure** and document it

Example log section to look for:
```
=== BOOMERANGME WEBHOOK RECEIVED ===
Event Type: CardInstalledEvent
--- Parsed JSON (prettified) ---
{
  "event": "CardInstalledEvent",
  ...
}
```

### Step 3: Update the Webhook Handler
Once you know the event structure:

1. **Add the event name** to the appropriate case in `BoomerangmeWebhookController.java`
2. **Verify the data mapping** in `CustomerSyncService.syncCustomerFromBoomerangme()`
3. **Test the sync** to ensure data flows correctly to Dutchie

## Current Webhook Handler Logic

### Events Currently Handled:
```java
// Customer sync events
case "CardInstalledEvent":
case "CardCreatedEvent":
case "CardUpdatedEvent":
    → Calls: customerSyncService.syncCustomerFromBoomerangme()

// Points sync events
case "PointsUpdatedEvent":
case "CardPointsUpdatedEvent":
    → Calls: pointsSyncService.syncPointsFromBoomerangme()

// Template events (no action needed)
case "UserTemplateUpdatedEvent":
    → Logged only, no sync

// Unknown events
default:
    → Logged with warning
    → Attempts sync if card structure detected
```

## Data Mapping Requirements

### What You Need to Map for Customer Sync

To sync customers to Dutchie, you need to extract from Boomerangme webhook:

| Dutchie Field | Boomerangme Source | Status |
|---------------|-------------------|---------|
| `email` | `card.customer.email` or similar | ❓ Unknown structure |
| `phone` | `card.customer.phone` or similar | ❓ Unknown structure |
| `firstName` | `card.customer.first_name` | ❓ Unknown structure |
| `lastName` | `card.customer.last_name` | ❓ Unknown structure |
| `externalId` | `card.id` or `card.customer.id` | ❓ Unknown structure |

**Action Required:** Test customer events to discover the actual field names

### What You Need to Map for Points Sync

| Field | Boomerangme Source | Status |
|-------|-------------------|---------|
| `cardId` | `card.id` or root `id` | ✅ Handled |
| `points` | `card.points` or `points` | ❓ To be verified |

## Security: Webhook Signature Validation

### Current Status:
- ✅ Code is ready to validate signatures
- ⚠️ Webhook secret not configured in database
- ✅ Signature header detection fixed (`x-signature` now supported)

### Headers Sent by Boomerangme:
- `x-signature`: Contains HMAC signature
- Algorithm: Likely HMAC-SHA256 (to be confirmed)

### To Enable Signature Validation:

1. **Get webhook secret from Boomerangme**
2. **Update your integration config:**
   ```sql
   UPDATE integration_configs 
   SET boomerangme_webhook_secret = 'your_secret_here'
   WHERE merchant_id = 'Evergreen';
   ```
3. **Webhook will automatically validate** on next request

## Testing Checklist

- [ ] Get customer to install loyalty app → Check logs for event
- [ ] Award points to customer → Check logs for points event
- [ ] Update customer profile → Check logs for update event
- [ ] Make test purchase → Check logs for transaction/points event
- [ ] Redeem reward → Check logs for redemption event
- [ ] Configure webhook secret → Verify signature validation works
- [ ] Document all discovered event structures
- [ ] Update webhook handler with confirmed event names
- [ ] Test end-to-end sync to Dutchie

## Next Steps

1. **Contact Boomerangme Support:**
   - Request webhook documentation
   - Ask for list of all event types
   - Confirm signature algorithm and header name

2. **Test Customer Actions:**
   - Install app as new customer
   - Make purchases to earn points
   - Update customer profile
   - Monitor `webhook.log` for each action

3. **Update Code:**
   - Add discovered event types to switch statement
   - Verify data mapping in sync services
   - Add any missing field mappings

4. **Enable Security:**
   - Configure webhook secret
   - Test signature validation

## Helpful Log Commands

View webhook logs in real-time:
```bash
docker-compose logs -f integrations-app | grep "BOOMERANGME WEBHOOK"
```

View only event types received:
```bash
docker-compose logs integrations-app | grep "Event Type:"
```

View all prettified JSON payloads:
```bash
docker-compose logs integrations-app | grep -A 20 "Parsed JSON (prettified)"
```

