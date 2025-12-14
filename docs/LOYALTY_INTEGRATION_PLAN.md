# Loyalty Integration Plan: Treez ↔ Boomerangme

## System Goal

Keep customers synchronized between Treez POS and Boomerangme loyalty cards, track completed orders, and automatically award/sync loyalty points.

## Required Treez Webhooks

### 1. ✅ Customer Webhook (CUSTOMER)
**When to Use:**
- On Creation → Create matching Boomerangme card
- On Profile Updates → Update card holder information

**Why Needed:**
- Sync customer data between systems
- Ensure customer exists before awarding points
- Keep contact info up-to-date

**Implementation Priority:** 🔴 HIGH

---

### 2. ✅ Ticket Webhook (TICKET) - On Completion
**When to Use:**
- On Completion → Calculate and award loyalty points

**Why Needed:**
- Award points when purchase is finalized
- This is the PRIMARY points earning event

**Implementation Priority:** 🔴 HIGH

**Note:** We only need "On Completion" events. Skip:
- On Creation (order not final yet)
- Item editing (order still in progress)
- Status updates during processing

---

### 3. ✅ Ticket Status Webhook - COMPLETED/CANCELED
**When to Use:**
- COMPLETED → Award points (backup/confirmation)
- CANCELED → Potentially reverse points if awarded prematurely

**Why Needed:**
- Confirm order completion
- Handle edge cases (refunds, cancellations)

**Implementation Priority:** 🟡 MEDIUM

---

### 4. ❌ Product Webhook
**Not Needed** for basic loyalty system.

**Future Use Case:** If you want category-based point multipliers (e.g., "2x points on premium products"), you'd need product data.

**Implementation Priority:** ⚪ LOW / OPTIONAL

---

## Boomerangme Card Setup

### Recommended Card Type: **Reward Card (ID 7)**

**Why Reward Card:**
- ✅ Supports points mechanics
- ✅ Can add scores (points)
- ✅ Can subtract scores (redemptions)
- ✅ Flexible for various reward tiers
- ✅ Most common loyalty card type

### Card Configuration in Boomerangme

```json
{
  "card_type_id": 7,  // Reward card
  "mechanics_type": "Points",
  "program_id": "your_program_id",
  "template_id": "your_template_id"
}
```

---

## Required Boomerangme API Methods

### For Customer Management

#### 1. **Create card for customer** (Issue card)
**API:** `POST /api/2.0/programs/{program_id}/cards`
**When:** Customer created in Treez → Issue Boomerangme card
**Maps to:** Treez CUSTOMER webhook (creation)

```json
{
  "cardholder_email": "customer@example.com",
  "cardholder_phone": "+1234567890",
  "cardholder_first_name": "John",
  "cardholder_last_name": "Doe"
}
```

#### 2. **Get card** (Get information on a card)
**API:** `GET /api/2.0/cards/{serial_number}`
**When:** Need to check current points balance
**Maps to:** Before awarding points, verify card exists

#### 3. **List of cards** (Search for cards)
**API:** `GET /api/2.0/cards?email={email}`
**When:** Find customer's card by email/phone from Treez
**Maps to:** Link Treez customer to existing Boomerangme card

---

### For Points Management

#### 4. **Add scores to card** ⭐ PRIMARY METHOD
**API:** `POST /api/2.0/cards/{serial_number}/scores/add`
**When:** Treez order completed → Award points
**Maps to:** Treez TICKET webhook (completion)

```json
{
  "scores": 100,  // Points to add
  "comment": "Purchase of $100.00 - Order #12345"
}
```

#### 5. **Subtract scores from card**
**API:** `POST /api/2.0/cards/{serial_number}/scores/subtract`
**When:** Customer redeems reward in-store
**Maps to:** Manual redemption or future redemption tracking

```json
{
  "scores": 50,  // Points to subtract
  "comment": "Redeemed $5 discount - Order #12346"
}
```

---

## Integration Flow Diagrams

### Flow 1: New Customer Registration

```
┌─────────────────────────────────────────────────────────┐
│  New Customer Created in Treez                          │
└───────────────────┬─────────────────────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │ CUSTOMER Webhook      │
        │ event_type: CUSTOMER  │
        │ data: {               │
        │   customer_id: 123    │
        │   email: ...          │
        │   phone: ...          │
        │ }                     │
        └───────────┬───────────┘
                    │
                    ▼
        ┌────────────────────────────┐
        │ CustomerSyncService        │
        │ processTreezCustomer()     │
        └───────────┬────────────────┘
                    │
                    ├─ 1. Check if card exists (by email)
                    │  GET /api/2.0/cards?email={email}
                    │
                    ├─ 2. If NOT exists:
                    │  POST /api/2.0/programs/{id}/cards
                    │  (Create Boomerangme card)
                    │
                    ├─ 3. Save to database:
                    │  Card table + Customer table
                    │
                    └─ 4. Link customer to card (one-to-one)
                    
┌─────────────────────────────────────────────────────────┐
│  Customer now has linked Boomerangme loyalty card       │
└─────────────────────────────────────────────────────────┘
```

---

### Flow 2: Order Completion & Points Award

```
┌─────────────────────────────────────────────────────────┐
│  Order Completed in Treez                               │
└───────────────────┬─────────────────────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │ TICKET Webhook        │
        │ event_type: TICKET    │
        │ data: {               │
        │   ticket_id: 789      │
        │   customer_id: 123    │
        │   total_amount: 100.0 │
        │   status: COMPLETED   │
        │ }                     │
        └───────────┬───────────┘
                    │
                    ▼
        ┌────────────────────────────┐
        │ TreezWebhookService        │
        │ processTicketEvent()       │
        └───────────┬────────────────┘
                    │
                    ├─ 1. Verify order is COMPLETED
                    │
                    ├─ 2. Find customer by customer_id
                    │  (from database)
                    │
                    ├─ 3. Get customer's Boomerangme card
                    │  (from Customer.card relationship)
                    │
                    ├─ 4. Calculate points earned
                    │  Example: $1 = 1 point
                    │  total_amount = $100 → 100 points
                    │
                    ├─ 5. Award points to card
                    │  POST /api/2.0/cards/{serial}/scores/add
                    │  { "scores": 100, "comment": "Order #789" }
                    │
                    ├─ 6. Update customer.total_points
                    │
                    └─ 7. Log transaction in sync_logs
                    
┌─────────────────────────────────────────────────────────┐
│  Customer earned points, card updated                   │
└─────────────────────────────────────────────────────────┘
```

---

### Flow 3: Bidirectional Sync (Boomerangme → Treez)

```
┌─────────────────────────────────────────────────────────┐
│  Customer Installs Boomerangme Card on Phone            │
└───────────────────┬─────────────────────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │ CardInstalledEvent    │
        │ (Boomerangme webhook) │
        │ data: {               │
        │   cardholder_email    │
        │   cardholder_phone    │
        │   serial_number       │
        │ }                     │
        └───────────┬───────────┘
                    │
                    ▼
        ┌────────────────────────────┐
        │ CustomerSyncService        │
        │ processCardInstalled()     │
        └───────────┬────────────────┘
                    │
                    ├─ 1. Find customer in Treez by email
                    │  (Search Treez API or check database)
                    │
                    ├─ 2. If NOT exists in Treez:
                    │  TODO: Create customer in Treez
                    │  (Treez customer creation API)
                    │
                    ├─ 3. Link Treez customer_id to card
                    │
                    └─ 4. Save relationship in database
                    
┌─────────────────────────────────────────────────────────┐
│  Card now linked to Treez customer                      │
└─────────────────────────────────────────────────────────┘
```

---

## Points Calculation Rules

### Basic Rule
```
Points Earned = Total Purchase Amount × Multiplier
```

### Examples

**Rule 1: $1 = 1 Point**
```javascript
const pointsEarned = Math.floor(totalAmount);
// $99.99 → 99 points
// $150.00 → 150 points
```

**Rule 2: $1 = 10 Points**
```javascript
const pointsEarned = Math.floor(totalAmount * 10);
// $99.99 → 999 points
// $150.00 → 1500 points
```

**Rule 3: Category-Based Multipliers**
```javascript
const basePoints = Math.floor(totalAmount);
const multiplier = isPremiumProduct ? 2 : 1;
const pointsEarned = basePoints * multiplier;
// Regular product: $100 × 1 = 100 points
// Premium product: $100 × 2 = 200 points
```

### Recommended Starting Rule
**$1 = 1 Point** (simple, easy to understand)

Configure in database or environment variable:
```sql
ALTER TABLE integration_configs 
ADD COLUMN points_per_dollar DECIMAL(10,2) DEFAULT 1.0;
```

---

## Database Schema Updates

### Add Points Configuration

```sql
-- Add points calculation settings to integration_configs
ALTER TABLE integration_configs 
ADD COLUMN IF NOT EXISTS points_per_dollar DECIMAL(10,2) DEFAULT 1.0,
ADD COLUMN IF NOT EXISTS minimum_purchase_for_points DECIMAL(10,2) DEFAULT 0.0,
ADD COLUMN IF NOT EXISTS points_rounding_mode VARCHAR(20) DEFAULT 'FLOOR';

COMMENT ON COLUMN integration_configs.points_per_dollar IS 'Points earned per dollar spent (e.g., 1.0 = 1 point per $1)';
COMMENT ON COLUMN integration_configs.minimum_purchase_for_points IS 'Minimum purchase amount to earn points';
COMMENT ON COLUMN integration_configs.points_rounding_mode IS 'How to round points: FLOOR, CEIL, or ROUND';
```

---

## Implementation Checklist

### Phase 1: Customer Sync (Week 1)
- [ ] Implement Treez CUSTOMER webhook processing
- [ ] Create Boomerangme card when Treez customer created
- [ ] Handle customer profile updates
- [ ] Link Boomerangme card to Treez customer in database
- [ ] Test: Create customer in Treez → Verify card created in Boomerangme

### Phase 2: Points Earning (Week 2)
- [ ] Implement Treez TICKET webhook processing (completion)
- [ ] Add points calculation logic
- [ ] Call Boomerangme "Add scores to card" API
- [ ] Update customer.total_points in database
- [ ] Log all point transactions
- [ ] Test: Complete order in Treez → Verify points awarded

### Phase 3: Bidirectional Sync (Week 3)
- [ ] Implement Treez customer creation API call
- [ ] Handle CardInstalledEvent from Boomerangme
- [ ] Create customer in Treez if not exists
- [ ] Test: Install card in Boomerangme → Verify customer in Treez

### Phase 4: Edge Cases & Cancellations (Week 4)
- [ ] Implement TICKET STATUS webhook (CANCELED)
- [ ] Handle points reversal for canceled orders
- [ ] Handle partial refunds
- [ ] Add duplicate transaction prevention
- [ ] Test cancellation scenarios

### Phase 5: Monitoring & Optimization (Ongoing)
- [ ] Add points sync reconciliation job
- [ ] Monitor sync failures
- [ ] Add retry logic for failed syncs
- [ ] Performance optimization
- [ ] Analytics dashboard

---

## API Method Mapping Summary

| Treez Event | Boomerangme API | Priority |
|-------------|-----------------|----------|
| CUSTOMER (create) | `POST /api/2.0/programs/{id}/cards` | 🔴 HIGH |
| CUSTOMER (update) | `GET /api/2.0/cards/{serial}` + update | 🟡 MEDIUM |
| TICKET (complete) | `POST /api/2.0/cards/{serial}/scores/add` | 🔴 HIGH |
| TICKET STATUS (cancel) | `POST /api/2.0/cards/{serial}/scores/subtract` | 🟡 MEDIUM |
| CardInstalledEvent | Create customer in Treez (if needed) | 🟡 MEDIUM |

---

## Configuration Example

```sql
-- Complete integration configuration
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
    
    -- Points configuration
    points_per_dollar = 1.0,  -- $1 = 1 point
    minimum_purchase_for_points = 5.0,  -- Min $5 purchase
    points_rounding_mode = 'FLOOR',
    
    enabled = true
WHERE merchant_id = 'Evergreen';
```

---

## Next Steps

1. **Read Boomerangme API Documentation**
   - Review the full API spec in `digitalwallet_boomerangme.OAPI.doc.json`
   - Understand authentication requirements
   - Test API endpoints in sandbox

2. **Implement Boomerangme API Client**
   - Create `BoomerangmeApiClient` service
   - Implement card creation, search, and points methods
   - Add error handling and retries

3. **Implement Customer Sync**
   - Update `TreezWebhookService.processCustomerEvent()`
   - Call Boomerangme API to create/update cards
   - Store card serial number in database

4. **Implement Points Awarding**
   - Update `TreezWebhookService.processTicketEvent()`
   - Calculate points from order total
   - Call Boomerangme API to add scores
   - Handle failures gracefully

5. **Test End-to-End**
   - Create test customer in Treez
   - Complete test order
   - Verify points awarded in Boomerangme
   - Check all logs and database records

---

## Questions to Answer Before Implementation

1. **Points Ratio:** How many points per dollar? (Recommended: 1 point = $1)
2. **Minimum Purchase:** Minimum purchase to earn points? (e.g., $5 minimum)
3. **Rounding:** Round up, down, or nearest? (Recommended: Floor)
4. **Excluded Items:** Any products that don't earn points? (e.g., taxes, fees)
5. **Redemption:** How many points for $1 discount? (e.g., 100 points = $1)
6. **Expiration:** Do points expire? After how long?
7. **Welcome Bonus:** Give new customers starting points? How many?
8. **Cancellations:** Reverse points immediately or wait for final status?

---

## Related Documentation

- [TREEZ_WEBHOOK_GUIDE.md](TREEZ_WEBHOOK_GUIDE.md)
- [BOOMERANGME_WEBHOOK_GUIDE.md](BOOMERANGME_WEBHOOK_GUIDE.md)
- [INTEGRATION_ARCHITECTURE.md](INTEGRATION_ARCHITECTURE.md)
- [TREEZ_WEBHOOK_SECURITY.md](TREEZ_WEBHOOK_SECURITY.md)
- `digitalwallet_boomerangme.OAPI.doc.json` - Full Boomerangme API spec

