@Treez Ticket Webhooks 

I need to implement a system with webhooks primarily, or whatever would be required.

This will be basically an integration between boomerangme sub accounts and third party POS, like dutchie/treez etc.

1st milestone -
Customer creation/order in POS automatically creates a customer in heymary. (Card installation has to be done manually)
Any card installation in heymary, automatically creates a POS customer, if phone number / email doesn't exists.
Any POS order completed will add the loyalty points to HeyMary card automatically.

2nd Milestone
Redeem heymary loyalty points in POS via discounts
Manual checkup on available points in customer's wallet, then adding the valid discount
The discount will automatically consume the points available in the card/wallet of customer.
This is specifically for points based loyalty programmes. There are other types of loyalty cards as well in HM.

3rd milestone -
Production release of integrations app
You will be required to provide a hosting service or I can arrange one and billing can be done from your end
Any bugs or modifications required.

### 1st milestone -
1. determine how database will be setup if our boomerangme loyalty program is going to be for sub accounts, and each subaccount will link to specific integration partner -
Like dutchie/treez.

    - If it's treez, then what URL endpoint will they need to put and with what settings.
    - we skip dutchie integration for now, but the system will be setup to be able to include different integrations later on.

    - this will have a basic web facing UI in our integration server (if needed, to easilty setup subaccounts' integration and their info), where we will show fetched subdomain that can subscribe to specific integration, which will have UI to show specific settings for that integration setup.

    - If it's possible to instead make a boomerangeme application to do some of this stuff, then we should do that. ( more info on this: (https://docs.boomerangme.cards/marketplace/create-an-application )


    Basically, the orders from POS will come in, will have some way of processing orders (normalizing maybe since there's different integration with many POS), then sent over to HeyMary(our main account in boomerangme, which contains all sub-accounts)

    I think we need to make a boomerangme application first that can handle the loyalty point and customer interaction to our this integration server first,
    then think about the POS system

### TODO (next) :

```
OG:
2. create customer in treez when a card was made and no existing matching customer from treez is found.
    
        I kinda want to have like an initial fetch of all cards and customer, when a new integration config is added (from a normal POST request).

        The api used to fetch all the card from the boomerangme_api_key will be /api/v2/cards?page=1&itemsPerPage=50
        yk with the logic to seach next pages if needed.

        This will add 
        - the card to the cards table,
        - customer info from each card, and the data from treez API for fetching customer (via matching phone Number (https://api.treez.io/v2.0/dispensary/
dispensary_name/customer/phone/phone_number) and/or Email (https://api.treez.io/v2.0/dispensary/
dispensary_name/customer/email/email_id))
```
2. ✅ COMPLETED - Initial fetch of all cards and customers when integration config is created
    
    **Implementation Summary:**
    - Created `InitialSyncService` that performs initial sync of all cards from Boomerangme
    - Added `getCards()` method to `BoomerangmeApiClient` with pagination support (page, itemsPerPage)
    - Automatically triggered when new integration config is created via POST /api/integration-configs
    - Can also be manually triggered via POST /api/integration-configs/{merchantId}/sync
    
    **What it does:**
    1. Fetches all cards from Boomerangme API using pagination (/api/v2/cards?page=1&itemsPerPage=50)
    2. Saves each card to the cards table with all card data (cardholder info, balance, status, etc.)
    3. For installed cards, attempts to link with existing Treez customers by:
       - Searching local database first (by email or phone based on customer_match_type config)
       - If not found locally, searches Treez POS API by email or phone
       - Creates Customer record linking the card to the Treez customer
    4. Handles phone number normalization (removes "1" prefix from Boomerangme format for Treez)
    5. Runs asynchronously in background to avoid blocking the API response
    
    **Files Modified:**
    - `src/main/java/heymary/co/integrations/service/BoomerangmeApiClient.java` - Added getCards() method
    - `src/main/java/heymary/co/integrations/service/InitialSyncService.java` - NEW service for initial sync
    - `src/main/java/heymary/co/integrations/controller/IntegrationConfigController.java` - Triggers sync on config creation
    
    **API Endpoints:**
    - POST /api/integration-configs - Creates config and triggers initial sync automatically
    - POST /api/integration-configs/{merchantId}/sync - Manually trigger initial sync for existing merchant

3. redeeming rewards