# Customer Sync Implementation

## Overview

Bidirectional customer synchronization between Treez POS and Boomerangme loyalty cards with intelligent deduplication.

## ✅ What's Implemented

### 1. Treez → Boomerangme Flow

**Trigger:** Customer created or updated in Treez

**Flow:**
```
Treez Customer Event (CUSTOMER webhook)
          ↓
Extract: customer_id, email, phone, name
          ↓
Check if customer exists in our DB:
  - By Treez customer_id
  - By email (deduplication)
  - By phone (optional)
          ↓
If EXISTS:
  ├─ Update customer information
  ├─ Update linked card (if exists)
  └─ Mark synced_at timestamp
          ↓
If NOT EXISTS:
  ├─ Check if Boomerangme card exists (by email/phone)
  ├─ If card exists: Link it
  ├─ If no card: Create new card (TODO: API call)
  └─ Create Customer record
```

### 2. Boomerangme → Treez Flow

**Trigger:** Card installed on customer's phone

**Flow:**
```
Boomerangme CardInstalledEvent
          ↓
Card saved to database (already working)
          ↓
processCardInstalled() called
          ↓
Check if Treez customer exists:
  - By email (primary)
  - By phone (optional)
          ↓
If EXISTS:
  ├─ Link card to customer
  ├─ Update customer info from card
  └─ Mark synced_at timestamp
          ↓
If NOT EXISTS:
  ├─ Create Treez customer (TODO: API call)
  ├─ Create Customer record
  └─ Link card
```

## 🔄 Deduplication Logic

### Scenario 1: Customer exists in Treez but no card yet

```
1. Treez customer #123 created (email: john@example.com)
2. CUSTOMER webhook received
3. Check DB: No customer with Treez ID #123
4. Check DB by email: No customer with john@example.com
5. Check Boomerangme: No card with john@example.com
6. Create Customer record (without card)
7. TODO: Create Boomerangme card via API
```

### Scenario 2: Card exists but no Treez customer

```
1. Card installed (email: jane@example.com)
2. CardInstalledEvent received
3. Card saved to DB
4. Check DB: No customer with jane@example.com
5. TODO: Create Treez customer via API
6. Create Customer record with card link
```

### Scenario 3: Both exist, need linking

```
1. Treez customer #456 (email: bob@example.com)
2. Boomerangme card exists (email: bob@example.com)
3. CUSTOMER webhook received
4. Check DB by email: No match
5. Check Boomerangme cards by email: MATCH!
6. Create Customer record linking both
7. Now synchronized!
```

### Scenario 4: Customer updated in Treez

```
1. Treez customer #123 updates phone number
2. CUSTOMER webhook received
3. Check DB: Customer #123 exists
4. Update customer.phone in DB
5. Update linked card.cardholderPhone
6. Update synced_at timestamp
```

## 📊 Data Storage Strategy

### What We Store

```sql
Customer table:
- merchant_id: Which merchant/dispensary
- integration_type: TREEZ or DUTCHIE
- pos_customer_id: Treez customer ID
- card_id: Foreign key to Card
- email: Customer email (cached)
- phone: Customer phone (cached)
- first_name, last_name: Name (cached)
- birth_date: Birthday (cached)
- total_points: Points balance (cached)
- synced_at: Last sync timestamp

Card table:
- serial_number: Boomerangme card ID
- cardholder_id: Boomerangme cardholder ID
- cardholder_email: Email (cached)
- cardholder_phone: Phone (cached)
- cardholder_first_name, last_name: Name (cached)
- bonus_balance: Points from Boomerangme
- synced_at: Last sync timestamp
```

### Why Cache Customer Data?

✅ **Fast Lookups** - No API calls needed for matching
✅ **Offline Capability** - Works during API downtime
✅ **Performance** - Instant deduplication checks
✅ **Historical Record** - Know what data was at sync time

### Keeping Cache Fresh

✅ **Webhook Updates** - Update on every webhook event
✅ **Timestamp Tracking** - `synced_at` shows freshness
✅ **Reconciliation** - Periodic job to catch missed updates (future)

### Data Consistency

The `synced_at` timestamp tells you:
- When was this data last confirmed?
- Is it fresh or potentially stale?
- Should we re-sync from source?

## 🔍 Deduplication Methods

### Primary: Email Matching

```java
// Check if customer exists by email
Optional<Customer> findByMerchantIdAndEmail(merchantId, email);

// Check if card exists by email
Optional<Card> findByCardholderEmail(email);
```

**Why Email First?**
- Most reliable identifier
- Rarely changes
- Used for communication

### Secondary: Phone Matching (Optional)

```java
// Can be added if needed
Optional<Card> findByCardholderPhone(phone);
```

**When to Use Phone:**
- Customer has no email
- Email doesn't match but phone does
- Additional verification

### Tie-Breaker: Manual Review

If multiple matches found:
1. Log warning
2. Add to dead letter queue
3. Manual review required
4. Choose most recently updated

## 🛡️ One-to-One Enforcement

### Database Constraints

```sql
-- One card per customer
UNIQUE constraint on customers(card_id)

-- One Treez customer per merchant
UNIQUE constraint on customers(merchant_id, pos_customer_id, integration_type)
```

### Application Logic

```java
// Before linking card
if (customer.getCard() != null) {
    log.warn("Customer already has card - replacing");
}
customer.setCard(card);
```

## 📝 Implementation Details

### TreezWebhookService.processCustomerEvent()

```java
// Extract data
String treezCustomerId = extractCustomerId(data);
String email = extractField(data, "email", "customer_email");
String phone = extractField(data, "phone", "phone_number");

// Check existing
Optional<Customer> existingCustomer = 
    findCustomerByTreezId(merchantId, treezCustomerId);

if (existingCustomer.isEmpty()) {
    // Deduplication check
    existingCustomer = 
        findCustomerByEmailOrPhone(merchantId, email, phone);
}

if (existingCustomer.isPresent()) {
    // Update existing
    updateCustomerFromTreezData(...);
} else {
    // Create new
    createCustomerWithBoomerangmeCard(...);
}
```

### CustomerSyncService.syncCustomerToTreez()

```java
// Find existing customer
Customer existingCustomer = 
    findExistingTreezCustomer(merchantId, card.getCardholderEmail(), card.getCardholderPhone());

if (existingCustomer != null) {
    // Link card to existing customer
    linkCardToCustomer(existingCustomer, card);
} else {
    // Create new Treez customer
    createNewTreezCustomer(config, card);
}
```

## 🚧 TODO Items (Marked in Code)

### High Priority

1. **Boomerangme Card Creation API**
   ```java
   // Location: TreezWebhookService.createCustomerWithBoomerangmeCard()
   // TODO: Call Boomerangme API to create card
   log.warn("TODO: Call Boomerangme API to create card");
   ```

2. **Treez Customer Creation API**
   ```java
   // Location: CustomerSyncService.createNewTreezCustomer()
   // TODO: Call Treez API to create customer
   log.warn("TODO: Implement Treez customer creation API call");
   ```

### Medium Priority

3. **Phone-Based Lookup**
   ```java
   // Location: TreezWebhookService.findCustomerByEmailOrPhone()
   // TODO: Add findByMerchantIdAndPhone to repository
   ```

4. **Card Update API**
   ```java
   // Location: TreezWebhookService.updateCardHolderInfo()
   // TODO: Call Boomerangme API to update card
   ```

## 🧪 Testing Checklist

### Test Scenario 1: New Treez Customer
- [ ] Create customer in Treez
- [ ] Webhook received
- [ ] Customer record created in DB
- [ ] TODO: Boomerangme card created

### Test Scenario 2: New Boomerangme Card
- [ ] Install card on phone
- [ ] Webhook received
- [ ] Card saved to DB
- [ ] Customer record created in DB
- [ ] TODO: Treez customer created

### Test Scenario 3: Duplicate Prevention (Same Email)
- [ ] Card exists with email@test.com
- [ ] Treez customer created with email@test.com
- [ ] Webhook received
- [ ] System links existing card (no duplicate)
- [ ] One customer record with both IDs

### Test Scenario 4: Customer Update
- [ ] Treez customer updates phone
- [ ] Webhook received
- [ ] Customer.phone updated in DB
- [ ] Card.cardholderPhone updated
- [ ] synced_at timestamp updated

### Test Scenario 5: Multiple Merchants
- [ ] Merchant A: customer@test.com
- [ ] Merchant B: customer@test.com
- [ ] Two separate customer records
- [ ] No cross-merchant linking

## 📊 Database Queries for Monitoring

### Check Sync Status

```sql
-- Customers synced in last hour
SELECT COUNT(*) 
FROM customers 
WHERE synced_at > NOW() - INTERVAL '1 hour';

-- Customers without cards
SELECT id, email, phone, merchant_id 
FROM customers 
WHERE card_id IS NULL;

-- Cards without customers
SELECT c.serial_number, c.cardholder_email 
FROM cards c 
LEFT JOIN customers cu ON cu.card_id = c.id 
WHERE cu.id IS NULL;

-- Recently synced customers
SELECT 
    c.id,
    c.merchant_id,
    c.email,
    c.pos_customer_id,
    ca.serial_number,
    c.synced_at
FROM customers c
LEFT JOIN cards ca ON c.card_id = ca.id
ORDER BY c.synced_at DESC
LIMIT 10;
```

### Find Potential Duplicates

```sql
-- Multiple customers with same email
SELECT email, COUNT(*) 
FROM customers 
WHERE email IS NOT NULL 
GROUP BY email, merchant_id 
HAVING COUNT(*) > 1;

-- Multiple cards with same email
SELECT cardholder_email, COUNT(*) 
FROM cards 
WHERE cardholder_email IS NOT NULL 
GROUP BY cardholder_email, merchant_id 
HAVING COUNT(*) > 1;
```

## 🔐 Security Considerations

### Data Privacy

- Store only necessary customer data
- Respect data retention policies
- Log access to customer records
- Encrypt sensitive fields (future)

### API Security

- Use Bearer tokens for Treez
- Use API keys for Boomerangme
- Validate all webhook signatures
- Rate limit API calls

## 📈 Performance

### Expected Performance

- Customer lookup: < 10ms (database index)
- Deduplication check: < 50ms (2-3 queries)
- Total sync time: < 500ms (including API calls)

### Optimization

- Database indexes on email/phone
- Async processing (already implemented)
- Batch operations (future)
- Caching (if needed)

## 🐛 Troubleshooting

### Customer Not Syncing

1. Check webhook received: `grep "CUSTOMER" logs/treez-webhooks.log`
2. Check for errors: `grep "ERROR" logs/treez-webhooks.log`
3. Check sync_logs: `SELECT * FROM sync_logs WHERE status = 'FAILED'`
4. Check dead letter queue: `SELECT * FROM dead_letter_queue`

### Duplicate Customers Created

1. Check email/phone matching logic
2. Verify database constraints
3. Check for race conditions (simultaneous webhooks)
4. Review sync_logs for timing

### Data Out of Sync

1. Check synced_at timestamps
2. Verify webhooks are being received
3. Check for failed API calls
4. Run manual reconciliation

## 📚 Related Documentation

- [LOYALTY_INTEGRATION_PLAN.md](LOYALTY_INTEGRATION_PLAN.md)
- [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md)
- [TREEZ_WEBHOOK_GUIDE.md](TREEZ_WEBHOOK_GUIDE.md)
- [BOOMERANGME_WEBHOOK_GUIDE.md](BOOMERANGME_WEBHOOK_GUIDE.md)

## ✅ Status Summary

| Feature | Status | Notes |
|---------|--------|-------|
| Treez CUSTOMER webhook processing | ✅ Done | Extracts data, handles deduplication |
| Customer record creation | ✅ Done | Creates with proper linking |
| Email-based deduplication | ✅ Done | Primary matching method |
| Card-Customer linking | ✅ Done | One-to-one relationship |
| Update existing customers | ✅ Done | Handles updates properly |
| Boomerangme card sync | ✅ Done | Links existing cards |
| Data caching strategy | ✅ Done | Stores customer data with timestamps |
| Boomerangme API card creation | ⚠️ TODO | Needs BoomerangmeApiClient implementation |
| Treez API customer creation | ⚠️ TODO | Needs TreezApiClient implementation |
| Phone-based deduplication | ⚠️ TODO | Optional enhancement |
| Reconciliation job | ⚠️ TODO | Future enhancement |

**Ready for testing once API clients are implemented!** 🚀

