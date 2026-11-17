# Domain Glossary — UB Backend

## Finance / Ledger

* **JournalEntry** — the aggregate root for a double-entry bookkeeping post. Owns its `JournalLine` children and guarantees they balance (`debits == credits`) before persistence.
* **JournalLine** — a single debit or credit line within a `JournalEntry`. Never manipulated directly by application services; created via `JournalEntry.debit(...)` and `JournalEntry.credit(...)`.
* **LedgerPostingPort** — the seam through which all journal entries are persisted. Callers build a `JournalEntry`, then hand it to the port. The port ensures standard accounts exist, asserts balance, and persists header + lines atomically.
* **LedgerAccountResolver** — resolves canonical `LedgerAccountCodes` (e.g., `SALES_REVENUE`) to persisted `LedgerAccount` IDs per business.
* **LedgerAccount** — a tenant-scoped chart-of-accounts row. Identified by a stable business-level `code`.
* **LedgerBootstrapService** — ensures the standard chart of accounts exists for a business. Called automatically by `LedgerPostingPort`; callers should not invoke it directly.

## Sales

* **Sale** — a completed point-of-sale transaction. When completed, triggers journal posting for revenue, COGS, inventory, and tender splits via `LedgerPostingPort`.
* **Refund** — reversal of a prior sale. Posts a reversal journal that mirrors the original sale journal.
* **Void** — cancellation of a sale on the same shift. Posts a full reversal journal.
* **Shift** — a cashier session (open → close). Closing may post a variance journal if counted cash differs from expected.

## Credits

* **CreditsJournalService** — credits-domain vocabulary for common two-line journals (wallet top-ups, AR payments, loyalty accruals/reversals). Maps business operations to the correct debit/credit account codes, then delegates to `LedgerPostingPort`.

## Service sales and staff earnings (ADR 0014)

* **Service line** — a `sale_items` row with `line_kind = SERVICE`: a service, the staff profile that performed it and its planned minutes; no item, no batch, no stock movement, no COGS. Its revenue posts to `SERVICE_REVENUE` (4010), not `SALES_REVENUE`.
* **Performer** — the staff profile that did the work on a service line. Not the cashier (`Sale.soldBy`). A staff profile may have no login.
* **Tip** — money taken with a sale on behalf of a performer. Stored as `sales.tip_total`; not part of `grand_total` and never revenue. The sale's payments cover `Sale.amountDue()` = grand total + tips, and the sale journal credits `TIPS_PAYABLE` (2310).
* **Staff ledger** — `staff_ledger_entries`: one signed row per commission, tip, reversal, adjustment or payout (payouts negative). A staff member's balance is the sum of their rows. Rows are never edited; a correction is another row. `StaffEarningsService` is the only writer.
* **Commission** — whole shillings, half up, on the line total after discount and excluding VAT. Dr `COMMISSION_EXPENSE` (6035) / Cr `COMMISSIONS_PAYABLE` (2300), posted when the sale completes whatever the tender.
* **Payout** — an amount paid to a staff member against their balance; partial payouts are normal, tips are settled first. From the till it is a paid-out movement on the open shift (`DrawerPaidOutService`), not a cash drawout.
* **Reconciliation** — the sum of the staff ledger for a business must equal the credit balance of 2300 + 2310. The commission statement reports it as `reconciled`.

## Held customer money, bookings and packages (ADR 0015)

* **Booking** — a service ticket that has not arrived yet: the same `pos_drafts` row, stage `BOOKED`, with a scheduled start and end. `arrive` makes it a waiting ticket. A staff member cannot hold two overlapping bookings.
* **Deposit** — money taken in advance against a ticket, held in `CUSTOMER_DEPOSITS` (2320) for a named customer. Never in the wallet until it is used, returned as credit or forfeited. `HeldMoneyService` is the only writer.
* **Package** — sessions of one service paid for up front (`customer_service_packages`), held in `DEFERRED_SERVICE_REVENUE` (2330) and recognised one session at a time. The last session carries the rounding.
* **Release to wallet** — how held money is spent: Dr 2320 or 2330 / Cr `CUSTOMER_WALLET_LIABILITY`, then the sale takes the ordinary `customer_wallet` tender. There is no "deposit" or "package" tender.
* **Helper** — a staff profile that shared a service line with its performer, with a share in percent (`service_helpers`, up to five). The performer keeps the rest. Each earns on their own share.
* **Service staff** — the `service_staff` role: someone who serves customers and signs in only to see their own earnings (`/api/v1/service-team/me`). No till.
* **Redo** — a ticket with `redo_of_sale_id`; it closes at Done with no sale and no commission.
* **Booking hours** — `branches.booking_hours`; set means customers may book that branch online, null means they may not.

## Customer visit (ADR 0016)

* **Visit** — what the screens call a service ticket: one customer, one reference, several service lines, one payment. Stored as `pos_drafts` (kind `SERVICE`); never a separate table.
* **Service line execution** — each `pos_draft_lines` row has its own performer, helpers, resource, `line_stage` (`WAITING` / `IN_SERVICE` / `DONE`) and start and finish times. The visit's stage is derived from its lines.
* **Resource** — a chair, bed, room or machine at a branch (`service_resources`), used by one service at a time, with a type, a cleaning time and a block. Independent of people: starting a service needs a free person *and*, if one is named, a free resource.
* **Assign vs start** — assigning names who does a line (and where) without starting it; starting also takes the person and the resource. Payment needs every line assigned, not started.
* **Shop's share** — a line's price less every commission on it. Tips are never part of it.
* **Set amount** — what a person is paid for one service when typed on the visit, replacing their plan for that line only (`performer_amount`, or the third field of a helper). A commission decision: needs permission when set, not when paid.
* **Time off** — dated periods on a staff profile when they cannot be booked (`staff_profiles.time_off`). It stops bookings, not walk-ins.
* **Calendar entry** — one block on the branch calendar: a booking (from the ticket's scheduled time), a service under way or done (from the line's own start and finish), time off, or a resource out of use.


## Restaurant orders and the kitchen (ADR 0020)

* **Menu item** — what is tapped on the order screen (`menu_items`). A **dish** is made to order: own name, price and tax rate, no stock. A **product** menu item points at a catalogue `Item` and is sold through the ordinary retail path at its shelf price.
* **Dish line** — a `sale_items` row with `line_kind = DISH`: a menu item, a snapshot of the chosen modifiers, an optional note; no item, no batch, no stock movement. Cost of goods is zero until recipes exist, so its `profit` equals its total and is not a margin. Revenue posts to `SALES_REVENUE` (4000); a separate food account is undecided.
* **Modifier group / option** — a set of choices on a dish with a minimum and maximum, and what each choice adds to the price. `RestaurantSaleSupport.priceDish` is the only place a dish is priced; a client's price is checked against it.
* **Modifier snapshot** — the chosen options stored with the line as JSON (`sale_items.modifiers`, `pos_draft_lines.modifiers`, `kitchen_ticket_lines.modifiers`). Renaming or removing an option never changes it.
* **Order** — a `pos_drafts` row of kind `ORDER`; never a separate table and never a held cart (`PosDraftConstants.isCart`). In phase 1a it is written already completed, in the sale's transaction, for a sale with something for a kitchen. Its ticket number is the **order number** called out to the customer. `OrderTicketService` is the only writer.
* **Kitchen station** — where food or drink is prepared (`kitchen_stations`). A dish goes to its own station, else its heading's, else the one implicit kitchen (station null). A product goes to a kitchen only when a station is named for it.
* **Kitchen ticket** — what one station was told to make for one order in one send (`kitchen_tickets`, `kitchen_ticket_lines`). Its lines are a snapshot and are never edited. Type `NEW`, or `VOID` when a sale is voided with food still being made.
* **Kitchen stage** — `NEW → PREPARING → READY → SERVED` (or `VOIDED`), kept on the order line (`pos_draft_lines.line_stage`), not the ticket. A ticket's status (`OPEN` / `READY` / `SERVED` / `VOIDED`) is worked out from its lines. Changing a stage never touches the order row or its version.
* **Handed over** — the customer has collected the order: every ready line becomes `SERVED`. Refused while the kitchen is still making part of it.
* **Sold offline** — a sale whose own time is more than two minutes old when it reaches the server. A dish on it keeps the price the customer paid and is accepted even if it has since run out; choices that never fitted the dish are still refused.

## Restaurant: orders paid later, bills and recipes (ADR 0020, phases 1b and 1.5)

* **Open order** — an `ORDER` ticket that is `pending`: a table (`DINE_IN`), a `TAKEAWAY` or a `DELIVERY`. Opened, added to, sent in rounds and settled later. Idempotent on the device's `clientOrderId`. Survives a shift close.
* **Table** — a `service_resources` row of type `TABLE` with a capacity and an area. It is occupied while an open order points at it; several orders may sit at one table. `ServiceResourceService.tables` never creates the numbered chairs.
* **Table's waiter** — the staff profile that looks after a table (`service_resources.assigned_staff_profile_id`, ADR 0027). A new dine-in order at the table is served by them unless another is named; it is a default, and the server can still be changed on the order.
* **Table takings** — what a table took over a period: the sales of the `PAID` bills of the orders that sat at it (`RestaurantReportsService.tableTakings`). Derived, never stored. Tips are beside it, not in it; refunds made afterwards are not taken off. A waiter's takings are those of the orders they served.
* **Draft line** — a line on an open order that has not been sent (`line_stage = DRAFT`): freely changed or removed. `client_line_id` stops a repeated request adding it twice.
* **Send / round** — sending turns every draft line into `NEW` (a dish, or a product with a station) or straight to `READY` (a product with none), and writes one kitchen ticket per station with the next `round_no`. Sending twice sends nothing.
* **Cancelled line** — a sent line is never edited. It is set to `VOIDED` with a reason and who did it (`restaurant.orders.void_sent`); the kitchen gets a `VOID` ticket if it was still making it. A line on a bill cannot be cancelled.
* **Price fixed when added** — a dish line keeps the price and choices it was rung with; the bill charges them even if the menu has changed or the dish was removed (`OrderSettlement.FrozenDish`). A product line is brought up to today's shelf price when its bill is paid, and the payment is refused once (409) so the customer sees the new figure.
* **Bill** — some or all of an order's lines settled together (`order_bills`, `order_bill_lines`, by quantity). One bill is one ordinary sale whose idempotency key is `bill-<id>`. Statuses `OPEN`, `PAID`, `COMP` (on the house), `UNPAID` (left without paying), `VOIDED` (its sale was voided). A dish is not shared between bills in parts.
* **Settled** — an order completes when nothing is unsent, no bill is open and every line that was not cancelled is fully on closed bills. Voiding a paid bill's sale sets the bill `VOIDED` and reopens the order.
* **Order settlement** — `OrderSettlement.settling(...)` marks a sale as the payment of a bill, for the length of the call: dishes are taken as ordered, no second order is written, and a tip may be for whoever served the order.
* **Served by** — the staff profile serving the order (`pos_drafts.served_by_staff_profile_id`); defaults to whoever opened it. A tip on a bill goes to them through the staff ledger. Not the cook and not necessarily who takes the money.
* **Booking (table)** — an open order with `service_stage = BOOKED` and a scheduled start and end. Two bookings cannot hold one table at overlapping times; a walk-in is not stopped. `seat` makes it an ordinary open order.
* **Recipe** — the stocked ingredients of a dish or of a modifier option (`recipe_lines`), each with a plated quantity and an optional yield percent. Ingredients need not be for sale.
* **Ingredient use** — when a dish with a recipe is sold, `InventoryBatchPickerService.pickAndApplyIngredientUse` takes its ingredients from the batches (stock movements with `reference_type = sale_recipe`) and their cost becomes the line's cost of goods, posted with the sale. A short count never refuses the sale: the remainder drives on-hand negative, costed at the last known cost. Voiding the sale puts them back (`sale_recipe_void`).
* **Menu engineering** — among dishes with a recipe: sells at least 70% of an even share, and a plate earns at least the average plate → `STAR`; sells but earns little → `PLOWHORSE`; earns but sells little → `PUZZLE`; neither → `DOG`.
* **Can make** — how many portions of a dish the branch's usable stock allows, if only that dish were made (ADR 0026): the smallest, over the dish's counted ingredients, of usable stock divided by what one portion takes from stock, rounded down. `RecipeService.makeable` is the only place it is worked out. Two dishes sharing an ingredient each see all of it. It is advice and never refuses a sale.
* **Usable stock** — what is left in a branch's active batches that have not expired, read through `StockOnHandReader`. An ingredient's stand-in counts with it. An ingredient that is not stocked (salt, water) is not counted and never limits a dish.
* **Must use today** — the part of a dish's can-make that depends on stock whose batch expires today.
* **Pantry** — a branch's ingredients restated as portions (`PantryService`, ADR 0026): what each dish can make, how long that lasts, what expires within three days, what was last paid for it, and what was lost in the last seven days. Read-only except for a loss.
* **Days of cover** — a dish's can-make divided by its average daily sales at the branch over the last fourteen days. None when it has not sold.
* **Stated dish** — the dish an ingredient's stock or loss is counted in: of the dishes that use it, the one that sells most (by name when none has sold).
* **Service plan** — portions per dish for a coming service, turned into what must be bought: each ingredient's need added up across the dishes, less usable stock, priced at the last unit cost paid (else the product's buying price). A calculation; nothing is stored. The shortfall goes to the order pad.
* **Kitchen loss** — an ingredient written off from the pantry as spoiled, broken, expired or other. It is inventory's standalone wastage (`recordStandaloneWastage`: one stock movement, Dr `INVENTORY_SHRINKAGE` / Cr `INVENTORY`), allowed only for a product that is an ingredient of a dish on the menu, and never for more than is on the shelf. A loss given as expired is taken from the batches past their date first; inventory prices it at what those batches cost.

## Restaurant: charges, delivery, batches, timed menus and QR ordering (ADR 0020, amendment 3)

* **Restaurant settings** — one row per business (`restaurant_settings`): service charge percent, pooled tips, packaging charge, usual delivery fee, the minute the business day starts, and whether customers may order from a table's QR code. No row means none of them.
* **Charge line** — a `sale_items` row with `line_kind = CHARGE`: a service charge, packaging or a delivery fee. No item, no stock, no commission. Only the server adds one, while a bill is being settled (`OrderSettlement`); a client that sends one is refused. Its revenue posts to `SALES_REVENUE`.
* **Service charge** — a percent of a table bill's lines, to the whole shilling, added when the bill is paid and stored on the bill. Not charged on takeaway or delivery.
* **Packaging charge / delivery fee** — charged once per takeaway or delivery order, on the first bill that can carry it. A delivery order's own fee beats the usual one.
* **Pooled tips** — when on, a tip is split equally among every staff profile marked as serving customers; cents left over go to whoever served the order.
* **Joining orders** — every line of one open order moves to another at the same branch, with its kitchen stage; the kitchen's tickets follow; the emptied order is cancelled with "Joined with order N". Refused if the order has a bill.
* **Cooked-food wastage** — cancelling a line that was `PREPARING`, `READY` or `SERVED` takes its recipe's ingredients off the count as a wastage movement (`reference_type = order_line_waste`) and posts Dr `INVENTORY_SHRINKAGE` / Cr `INVENTORY`. A line still `NEW` used nothing.
* **Prepared batch** — a stocked item made in bulk from a recipe (`recipe_lines.produced_item_id`). Making a batch takes the ingredients (`prep_batch` movements) and records a stock increase of the item at the ingredients' cost; the two journals cancel on `OPENING_BALANCE_EQUITY`, so only inventory moves between products.
* **Serving hours** — a menu item can be served only between two minutes of the Nairobi day (`serve_from_minute`, `serve_to_minute`); outside them it cannot be ordered. An end before the start runs past midnight.
* **Happy hour** — a dish's price between two minutes on named days (`happy_price`, `happy_from_minute`, `happy_to_minute`, `happy_days`). `MenuClock` decides; the till must charge the price in force when the sale is made. A line on an open order keeps the price it was added at.
* **Business day** — reports count a day from `day_starts_at_minute`, Nairobi time, to the same minute the next day.
* **Table QR code** — `service_resources.qr_token`, a 48-character random secret an owner can replace. `PublicTableOrderService` lets a customer who scans it add **unsent** lines to the table's open order; staff must send them. Works only while `qr_ordering` is on, and only under the business's own slug.
* **Order number on a receipt** — a restaurant sale's receipt footer starts with `Order N`: the counter order it produced or the table order whose bill it paid.

## Lodging: rooms, stays and the folio (ADR 0028)

* **Room** — a `service_resources` row of type `ROOM` with a nightly rate (`nightly_rate`). Managed at the front desk (`/api/v1/rooms`), kept off the service floor and the restaurant floor like a table. A lodging tenant is a bar or restaurant that also lets rooms, so managing rooms reuses `restaurant.menu.manage`.
* **Stay** — an open order on channel `LODGING`, booked for later: `pos_drafts` with `service_stage = BOOKED`, `scheduled_start`/`scheduled_end` the check-in and check-out, `table_resource_id` the room. Two stays cannot hold one room over overlapping nights. Its folio is the open order; lines, bills and payment reuse the restaurant open-order path.
* **Check-in** — seating the booking. It charges the room for all its nights at once: one `sale_items` row with `line_kind = ROOM`, quantity the nights, unit price the nightly rate **frozen onto the line at check-in** (a later rate change never moves a live folio). No item, no batch, no stock movement.
* **Room-night revenue** — a `ROOM` line posts to `ROOM_REVENUE` (4020), separate from `SALES_REVENUE` (4000) and `SERVICE_REVENUE` (4010). The sale's revenue split is three-way (goods, services, rooms) in the sale and void journals. A room night cannot be refunded line by line (no item), so only a void reverses it; the void split mirrors the sale.
* **Charge to the room** — bar and kitchen lines are added to the stay order; stock moves when the line is added, the money when the folio settles. Checkout settles the whole folio as one sale (`BillService.pay`, key `bill-<id>`); a held deposit (`CUSTOMER_DEPOSITS` 2320, via `HeldMoneyService`) is applied as a wallet tender.
