@Treez Ticket Webhooks 
I need to implement a system with webhooks primarily, or whatever would be required.

This will be basically an integration between boomerangme sub accounts and third party POS, like dutchie/treez etc.


1. determine how database will be setup if our boomerangme loyalty program is going to be for sub accounts, and each subaccount will link to specific integration partner -
Like dutchie/treez.

- If it's treez, then what URL endpoint will they need to put and with what settings.
- we skip dutchie integration for now, but the system will be setup to be able to include different integrations later on.

- this will have a basic web facing UI in our integration server (if needed, to easilty setup subaccounts' integration and their info), where we will show fetched subdomain that can subscribe to specific integration, which will have UI to show specific settings for that integration setup.

- If it's possible to instead make a boomerangeme application to do some of this stuff, then we should do that. ( more info on this: (https://docs.boomerangme.cards/marketplace/create-an-application )


Basically, the orders from POS will come in, will have some way of processing orders (normalizing maybe since there's different integration with many POS), then sent over to HeyMary(our main account in boomerangme, which contains all sub-accounts)

I think we need to make a boomerangme application first that can handle the loyalty point and customer interaction to our this integration server first,
then think about the POS system
