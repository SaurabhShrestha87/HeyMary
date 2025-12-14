# Implementation Roadmap

## Summary

Based on your integration plan, here's the prioritized roadmap for implementing the Treez ↔ Boomerangme loyalty system.

---

## ✅ COMPLETED (Already Done)

### Infrastructure
- ✅ Multi-POS architecture (IntegrationType enum: TREEZ, DUTCHIE)
- ✅ Card model (one-to-one with Customer)
- ✅ Customer model (supports multiple POS types)
- ✅ Database schema (V3 migration)
- ✅ Treez webhook endpoint with Bearer token auth
- ✅ Boomerangme webhook endpoint (CardIssued, CardInstalled events)
- ✅ Comprehensive logging (unified + integration-specific logs)
- ✅ Error handling and dead letter queue
- ✅ Sync logs for tracking

---

## 🔴 PHASE 1: Boomerangme API Client (Priority: CRITICAL)

### Goal: Communicate with Boomerangme API

**Duration:** 2-3 days

### Tasks:

#### 1.1 Create BoomerangmeApiClient Service
- [ ] Create `BoomerangmeApiClient.java` (extends current one or new)
- [ ] Configure API base URL and authentication
- [ ] Add WebClient configuration for Boomerangme API 2.0

#### 1.2 Implement Card Management Methods
- [ ] `createCard(programId, customerData)` - Issue card for customer
- [ ] `getCard(serialNumber)` - Get card by serial number
- [ ] `searchCards(email)` - Find card by email
- [ ] `listCards(programId, filters)` - List all cards

#### 1.3 Implement Points Management Methods
- [ ] `addScoresToCard(serialNumber, scores, comment)` - Award points
- [ ] `subtractScoresFromCard(serialNumber, scores, comment)` - Redeem points
- [ ] `getCardBalance(serialNumber)` - Check current points

#### 1.4 Error Handling
- [ ] Handle 404 (card not found)
- [ ] Handle 400 (invalid request)
- [ ] Handle 401 (authentication error)
- [ ] Retry logic with exponential backoff
- [ ] Log all API calls and responses

#### 1.5 Testing
- [ ] Unit tests for each method
- [ ] Integration tests with sandbox
- [ ] Test error scenarios
- [ ] Verify authentication works

**Acceptance Criteria:**
- Can create cards programmatically
- Can add/subtract points
- Proper error handling and logging
- All tests pass

---

## 🔴 PHASE 2: Customer Sync (Priority: HIGH)

### Goal: Sync customers from Treez to Boomerangme

**Duration:** 3-4 days

### Tasks:

#### 2.1 Implement Treez CUSTOMER Webhook Processing
- [ ] Update `TreezWebhookService.processCustomerEvent()`
- [ ] Parse customer data from Treez webhook
- [ ] Extract: customer_id, email, phone, first_name, last_name

#### 2.2 Customer Creation Flow
- [ ] Check if card already exists (search by email)
- [ ] If not exists: Create new Boomerangme card
- [ ] Save card to database (cards table)
- [ ] Create Customer record with card relationship
- [ ] Link Treez customer_id to Customer.posCustomerId

#### 2.3 Customer Update Flow
- [ ] Find existing customer by Treez customer_id
- [ ] Update customer information
- [ ] Update linked Boomerangme card (if info changed)
- [ ] Log sync operation

#### 2.4 Edge Cases
- [ ] Handle duplicate customers (same email)
- [ ] Handle missing email (use phone instead)
- [ ] Handle Boomerangme API failures
- [ ] Add to dead letter queue on failure

#### 2.5 Testing
- [ ] Test customer creation webhook
- [ ] Test customer update webhook
- [ ] Test duplicate handling
- [ ] Verify card created in Boomerangme
- [ ] Check database records

**Acceptance Criteria:**
- New Treez customer → Boomerangme card created
- Customer updates sync correctly
- All data stored in database
- Proper error handling

---

## 🔴 PHASE 3: Order Completion & Points (Priority: HIGH)

### Goal: Award points when orders complete

**Duration:** 4-5 days

### Tasks:

#### 3.1 Implement Treez TICKET Webhook Processing
- [ ] Update `TreezWebhookService.processTicketEvent()`
- [ ] Parse ticket/order data from Treez webhook
- [ ] Extract: ticket_id, customer_id, total_amount, status, items

#### 3.2 Points Calculation Service
- [ ] Create `PointsCalculationService.java`
- [ ] Implement configurable points-per-dollar ratio
- [ ] Support minimum purchase threshold
- [ ] Support rounding modes (floor, ceil, round)
- [ ] Handle excluded items/categories (optional)

#### 3.3 Points Awarding Flow
- [ ] Verify order status is COMPLETED
- [ ] Find customer by Treez customer_id
- [ ] Get customer's Boomerangme card
- [ ] Calculate points earned
- [ ] Call Boomerangme API to add scores
- [ ] Update Customer.totalPoints in database
- [ ] Create transaction log

#### 3.4 Duplicate Prevention
- [ ] Check if ticket_id already processed
- [ ] Add unique constraint or check in database
- [ ] Prevent double-awarding points

#### 3.5 Database Updates
- [ ] Add points_per_dollar to integration_configs
- [ ] Add minimum_purchase_for_points
- [ ] Add points_rounding_mode
- [ ] Create transaction history table (optional)

#### 3.6 Testing
- [ ] Test order completion webhook
- [ ] Test points calculation
- [ ] Verify points awarded in Boomerangme
- [ ] Test duplicate prevention
- [ ] Test various order amounts

**Acceptance Criteria:**
- Completed order → Points awarded automatically
- Points calculation is configurable
- No duplicate point awards
- Transaction logged in database

---

## 🟡 PHASE 4: Order Cancellations (Priority: MEDIUM)

### Goal: Handle canceled/refunded orders

**Duration:** 2-3 days

### Tasks:

#### 4.1 Implement TICKET STATUS Webhook
- [ ] Handle CANCELED status
- [ ] Handle status changes
- [ ] Distinguish between forward/backward progression

#### 4.2 Points Reversal Logic
- [ ] Find original point award transaction
- [ ] Calculate points to reverse
- [ ] Call Boomerangme API to subtract scores
- [ ] Update Customer.totalPoints
- [ ] Log reversal transaction

#### 4.3 Edge Cases
- [ ] Handle partial refunds (if supported)
- [ ] Handle order canceled before completion
- [ ] Handle insufficient points for reversal
- [ ] Time limits for reversals (e.g., 30 days)

#### 4.4 Testing
- [ ] Test order cancellation
- [ ] Test points reversal
- [ ] Test edge cases
- [ ] Verify Boomerangme card updated

**Acceptance Criteria:**
- Canceled order → Points reversed
- Partial refunds handled correctly
- Edge cases handled gracefully

---

## 🟡 PHASE 5: Bidirectional Sync (Priority: MEDIUM)

### Goal: Sync from Boomerangme to Treez

**Duration:** 3-4 days

### Tasks:

#### 5.1 Create Treez API Client
- [ ] Create `TreezApiClient.java`
- [ ] Implement authentication
- [ ] Configure API base URL

#### 5.2 Treez Customer Creation
- [ ] Implement `createCustomer(customerData)` method
- [ ] Map Boomerangme card data to Treez format
- [ ] Handle API errors

#### 5.3 Update CardInstalled Event Handler
- [ ] Find or create Treez customer by email
- [ ] If not exists: Call Treez API to create
- [ ] Link Treez customer_id to database
- [ ] Log sync operation

#### 5.4 Testing
- [ ] Test card installation webhook
- [ ] Test customer creation in Treez
- [ ] Verify customer appears in Treez
- [ ] Check database linkage

**Acceptance Criteria:**
- Card installed → Customer created in Treez (if needed)
- Proper linking between systems
- API errors handled

---

## 🟢 PHASE 6: Monitoring & Analytics (Priority: LOW)

### Goal: Monitor system health and provide insights

**Duration:** 2-3 days

### Tasks:

#### 6.1 Reconciliation Job
- [ ] Create scheduled job to compare points
- [ ] Check Treez orders vs Boomerangme points
- [ ] Report discrepancies
- [ ] Auto-fix if possible

#### 6.2 Dashboard/Reports
- [ ] Total points awarded today/week/month
- [ ] Top customers by points
- [ ] Failed sync count
- [ ] API error rates
- [ ] Points redemption tracking

#### 6.3 Alerts
- [ ] Alert on high error rate
- [ ] Alert on sync failures
- [ ] Alert on API downtime
- [ ] Daily summary email

#### 6.4 Performance Optimization
- [ ] Cache frequently accessed data
- [ ] Batch API calls if possible
- [ ] Optimize database queries
- [ ] Add indexes where needed

**Acceptance Criteria:**
- Monitoring dashboard available
- Alerts working
- Performance optimized
- Reports generated

---

## 📋 Configuration Checklist

Before going live, configure:

- [ ] Treez API credentials
- [ ] Treez dispensary ID
- [ ] Treez webhook secret (Bearer token)
- [ ] Boomerangme API key
- [ ] Boomerangme program ID
- [ ] Boomerangme webhook secret
- [ ] Points per dollar ratio
- [ ] Minimum purchase amount
- [ ] Points rounding mode
- [ ] Card type ID (Reward = 7)

---

## 🧪 Testing Checklist

### End-to-End Tests

- [ ] **Test 1: New Customer**
  1. Create customer in Treez
  2. Verify Boomerangme card created
  3. Check database records

- [ ] **Test 2: Order Completion**
  1. Complete order for existing customer
  2. Verify points awarded
  3. Check Boomerangme card balance
  4. Check database updated

- [ ] **Test 3: Order Cancellation**
  1. Complete order (points awarded)
  2. Cancel order
  3. Verify points reversed
  4. Check Boomerangme card balance

- [ ] **Test 4: Card Installation**
  1. Install card on phone
  2. Verify customer created in Treez (if new)
  3. Check database linkage

- [ ] **Test 5: Customer Update**
  1. Update customer in Treez
  2. Verify Boomerangme card updated
  3. Check database synchronized

- [ ] **Test 6: Error Handling**
  1. Simulate API failures
  2. Verify retry logic
  3. Check dead letter queue
  4. Verify recovery after API restored

---

## 📊 Success Metrics

### Week 1 (After Phase 1-2)
- [ ] 100% customer sync success rate
- [ ] < 5 second average sync time
- [ ] All new customers have cards

### Week 2 (After Phase 3)
- [ ] 100% point award success rate
- [ ] Points awarded within 30 seconds of order completion
- [ ] Zero duplicate point awards

### Month 1 (After All Phases)
- [ ] 99.9% uptime
- [ ] < 1% failed sync rate
- [ ] Automated recovery for failures
- [ ] Points discrepancy < 0.1%

---

## 🚀 Launch Plan

### Pre-Launch (1 week before)
- [ ] Complete all phases
- [ ] Run all tests
- [ ] Load testing with expected volume
- [ ] Document all configurations
- [ ] Train support team
- [ ] Prepare rollback plan

### Soft Launch (Week 1)
- [ ] Enable for 10% of customers
- [ ] Monitor closely
- [ ] Fix any issues immediately
- [ ] Collect feedback

### Full Launch (Week 2)
- [ ] Enable for all customers
- [ ] Continue monitoring
- [ ] Optimize based on real usage
- [ ] Iterate on feedback

---

## 📚 Documentation To Create

- [x] LOYALTY_INTEGRATION_PLAN.md
- [x] INTEGRATION_ARCHITECTURE.md
- [x] TREEZ_WEBHOOK_GUIDE.md
- [x] TREEZ_WEBHOOK_SECURITY.md
- [x] BOOMERANGME_WEBHOOK_GUIDE.md
- [ ] BOOMERANGME_API_GUIDE.md (detailed API usage)
- [ ] POINTS_CALCULATION_GUIDE.md (business rules)
- [ ] TROUBLESHOOTING_GUIDE.md (common issues)
- [ ] DEPLOYMENT_GUIDE.md (how to deploy)
- [ ] API_REFERENCE.md (internal API docs)

---

## Dependencies & Blockers

### External Dependencies
- Treez API documentation (need access)
- Treez sandbox environment (for testing)
- Boomerangme API 2.0 access
- Boomerangme sandbox/test program

### Technical Blockers
- None currently (infrastructure is ready)

### Business Decisions Needed
- Points-per-dollar ratio
- Minimum purchase amount
- Points expiration policy (if any)
- Redemption rules
- Welcome bonus amount

---

## Estimated Timeline

| Phase | Duration | Start | End |
|-------|----------|-------|-----|
| Phase 1: Boomerangme API | 3 days | Week 1 | Week 1 |
| Phase 2: Customer Sync | 4 days | Week 1 | Week 2 |
| Phase 3: Points Award | 5 days | Week 2 | Week 2 |
| Phase 4: Cancellations | 3 days | Week 3 | Week 3 |
| Phase 5: Bidirectional | 4 days | Week 3 | Week 3 |
| Phase 6: Monitoring | 3 days | Week 4 | Week 4 |
| **Testing & Launch** | 1 week | Week 4 | Week 5 |

**Total: ~5 weeks for complete implementation**

Aggressive timeline: **3 weeks** (Phase 1-3 only, minimal features)

---

## Next Immediate Steps (This Week)

1. **Review Boomerangme API Documentation**
   - Read `digitalwallet_boomerangme.OAPI.doc.json`
   - Understand authentication flow
   - Test API endpoints manually (Postman/curl)

2. **Implement Boomerangme API Client**
   - Start with Phase 1 tasks
   - Focus on card creation and points methods
   - Write tests

3. **Configure Points Rules**
   - Decide points-per-dollar ratio
   - Set minimum purchase amount
   - Document business rules

4. **Set Up Testing Environment**
   - Get Boomerangme sandbox access
   - Get Treez sandbox access
   - Create test customers and orders

Ready to start? Begin with **Phase 1: Boomerangme API Client**! 🚀

