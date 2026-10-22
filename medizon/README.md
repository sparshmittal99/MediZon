# MediZon

Online medicine ordering with dose reminders, supply tracking, manual or automatic refills,
AI-read prescriptions that a pharmacist approves and that fill the cart by themselves, and a built-in chat assistant.

**Stack:** Java 17, Spring Boot 3.3, Spring Security, Spring Data JPA, PostgreSQL, Thymeleaf + Bootstrap 5,
Apache POI (Excel export). Tests use JUnit 5 and Mockito.
The assistant is a separate Python service (FastAPI + scikit-learn) in [`../medizon-assistant`](../medizon-assistant); it needs no external AI service or API key.
Google Gemini (optional, `GEMINI_API_KEY`) reads uploaded prescriptions. Groq (optional, `GROQ_API_KEY`) answers chat
questions the assistant can't, with Gemini as backup.

## Run it

```bash
# 1. Start PostgreSQL
docker compose up -d db

# 2. The one admin account's sign-in (applied on every start)
export MEDIZON_ADMIN_EMAIL=...
export MEDIZON_ADMIN_PASSWORD=...

# 3. Start the assistant (in another terminal)
cd ../medizon-assistant
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn assistant.app:app --port 8001

# 4. Run MediZon
mvn spring-boot:run
```

Open http://localhost:8080. Tables are created and the starter catalog is seeded into a "main store" on first start;
add PIN codes for it under **Admin > Delivery areas** before customers can order.
Customers sign up from **Create account**. There is exactly one admin: its email and password come from the variables
above on every start (change them there and restart), and any other admin account from an older version is disabled.
The admin creates every other account under **Admin > Users** with a role (see Roles and stores).

Everything runs in `Asia/Kolkata` by default; change it with `MEDIZON_TZ`.
To run the app and the assistant in Docker as well: `docker compose --profile app up --build`.
MediZon works without the assistant running (the chat uses Groq or Gemini, or says it's offline) and without Gemini
(prescriptions go to the pharmacist unread). See [When an AI service is down](#when-an-ai-service-is-down).

## Configuration

All settings are in `application.properties` and can be overridden with environment variables:

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection |
| `ASSISTANT_URL` | Where the assistant service runs (default `http://localhost:8001`) |
| `ASSISTANT_TOKEN` | Optional shared secret; set the same value on both services |
| `GEMINI_API_KEY` | Google Gemini key (web app only): prescription reading and the backup chat fallback. Empty turns both off |
| `GROQ_API_KEY`, `GROQ_MODEL` | Groq key (web app only) for the chat fallback; models tried in order (default `openai/gpt-oss-120b,openai/gpt-oss-20b`) |
| `GEMINI_MODEL` | Gemini model (default `gemini-flash-latest`; `gemini-flash-lite-latest` allows more free requests) |
| `AI_PROVIDER`, `AI_FAST_TRACK_SCORE` | Which AI reads prescriptions (default `gemini`); AI score from which a prescription is fast-tracked (default 80) |
| `MAIL_ENABLED`, `MAIL_HOST`, `MAIL_USER`, `MAIL_PASSWORD` | Email copies of reminders and alerts (logged only when disabled) |
| `UPLOAD_DIR` | Where prescription files and return photos are stored |
| `SELLER_NAME`, `SELLER_GSTIN`, `SELLER_DRUG_LICENSE`, `SELLER_ADDRESS`, `SELLER_STATE`, `SELLER_PHONE`, `SELLER_EMAIL` | Seller details on GST invoices and in the site footer. `SELLER_STATE` decides CGST + SGST (same state as the customer) or IGST. Placeholder values are set in `application.properties` until real ones are given |
| `BASE_URL` | Used for links inside emails |
| `MEDIZON_ADMIN_EMAIL`, `MEDIZON_ADMIN_PASSWORD`, `MEDIZON_ADMIN_NAME` | The one admin account: created if missing, its password reset to this one on every start |
| `MONITORING_TOKEN` | Lets a Prometheus scraper read `/actuator/prometheus` with `Authorization: Bearer <token>` (admins can always) |
| `SLOW_REQUEST_MS`, `SLOW_QUERY_MS` | Requests (default 1000 ms) and database queries (default 500 ms) slower than this are logged |
| `ERROR_ALERT_SEVERITY` | Errors at or above this severity (default `HIGH`) notify the admin |
| `REMEMBER_ME_KEY` | Secret for "Keep me signed in"; without it those sign-ins end when the app restarts |
| `MEDIZON_TZ` | Time zone for reminders and reports (default `Asia/Kolkata`) |

## Features

**Customers**: search and browse by category, cart, checkout (COD, simulated UPI/card, MediZon Money for all or
part of an order, promo codes), order tracking,
cancel before shipping, buy again, prescriptions (upload up to 5 at once, AI-read, checked by the customer before
sending, medicines into the cart on approval; see Prescriptions below), "Notify me" back-in-stock alerts on
out-of-stock products (the home page shows only what's in stock), profile and address, notification centre,
desktop notifications, chat assistant, family members (each with their own medicines, reminders and supply),
per-device alert settings (everyone, or only some members), choosing who each cart item is for (or the household's
common OTC shelf, where medicines sold without a prescription start), GST tax invoices, and returns in two steps (items, reason and photos; then pickup address,
pickup date and where the refund goes: original method, MediZon Money, UPI or bank account). A "Delivers to" location in the header
(device location, a pin on the map, an address search or a PIN code, geocoded with OpenStreetMap) says whether
MediZon delivers there. Visitors can fill a cart before signing in; it moves into their account when they sign in
and "Sign in to checkout" brings them back to checkout. Saved addresses (home, office or a named other place,
for the customer or for someone else with the receiver's name and phone); checkout and the header picker choose
between them. Automatic refills go to the address chosen on each reminder, or the default one. One billing address
(optional, with GSTIN) is set from the account menu; checkout shows it next to the shipping address and the tax
invoice prints both. Without it, orders are billed to the account holder at the delivery address. In the cart a medicine can also be for "Others"
(someone outside the family): it's delivered but not added to My medicines, and can share a line with family members.
Each family member has a patient name (as on prescriptions), shown in the cart, at checkout and on the invoice.
Account settings: change password (current password first, then the new one twice) and sign out other devices.

**Back office**: dashboard (with sales by store for any day), customer order pipeline (Placed → Confirmed → Shipped → Delivered / Cancelled),
prescription review (AI score and fast-track, medicines and packs pre-filled from the AI reading and the customer's
cart, doctor, patient and date confirmed, validity date), GST tax invoices, return review (approve or reject, pickup with optional
restock, moving a pickup to another day, refund), promo codes (admin), return settings (admin: return window,
pickup range, refund days), a sales and returns report (a date range, a month or a financial year, day-wise or month-wise; Excel export with summary and GST totals plus optional month-wise, day-wise, orders, store-wise, medicines sold, HSN summary and returns sheets; CSV), the master catalogue and each store's catalogue, low-stock list, stores and delivery areas, user accounts by role, customer feedback, monitoring, and a chat assistant for the admin (it reads and analyses reviews and feedback, through Groq or Gemini too). Orders and Returns show the newest 20 for the chosen years, months or days (this year by default), and staff can find one by its number; tickets are found by number too. Every product gets a unique product code (for the admin and stores only, never shown to customers), and catalogues are searched by name or code; a store adds several master products at once by code or name. Both catalogues also import from Excel (.xlsx, .xls), CSV or a text PDF: the admin's sheet adds or updates master products (name, category, price, MRP, tax ...), a store's sets its stock, batch expiry and OTC prices by product code or name; **Download sheet** gives the headings with what's there now, and rows that can't be imported are listed with the reason. The admin can set a new password for any other account. Customers keep their newest 20 notifications. Staff accounts are for running the shop only; they can't shop, order or set reminders.

## Roles and stores

| Role | Can do |
|---|---|
| Admin (one, from `MEDIZON_ADMIN_*`) | Everything: all stores, master catalogue, delivery areas, promos, return settings, users, reports, monitoring |
| Pharmacist | Read, approve and reject prescriptions |
| Support | Read feedback and reviews; look up orders and invoices to help customers |
| Finance | Sales, GST and Excel/CSV reports; look up orders and invoices |
| Store | One store: its catalogue (stock, expiry, OTC prices, new products), its orders, deliveries and returns |
| Customer | Shop, order, prescriptions, My medicines; delete their own account |

- **Stores** (Admin > Stores) each sell from the master catalogue with their own stock, batch expiry and, for OTC
  medicines, their own price (never above MRP). Prescription medicines sell at the admin's catalogue price everywhere.
  A store's GSTIN, address and state go on the invoices for its orders.
- **Delivery areas**: the admin chooses which stores deliver to each PIN code and how long each takes there. The
  quickest store is the nearest. Shoppers see what the stores delivering to their location sell; each product comes
  from the nearest store that has enough in stock, else the next nearest, with that store's price and delivery time.
- **Checkout** splits a cart across stores into one order per store (shared checkout reference), paid for once; the
  delivery fee is worked out on the whole cart and charged on the biggest order; each order is rounded on its own and
  the cart shows the same total.
- **Deleting accounts**: customers delete their own (Account settings, with their password); the admin can delete any
  other account. Personal data is deleted (profile, addresses, family, medicines, prescriptions and files, cart,
  reviews, feedback, notifications); orders, invoices and refunds stay for the accounts without name, phone or
  address (a business buyer's GSTIN and billing name stay, as GST requires). The FAQ explains this to customers.
- **Upgrading** an existing database: on first start the catalogue's stock and expiry move into a store named after
  `SELLER_NAME`, it serves every listed PIN code (1 to 3 days plus the PIN code's old extra days) and existing orders
  are assigned to it.

## How reminders and refills work

```
 Order delivered ──► each member's supply += their share of packs × units per pack ──► refill alert re-armed
                                                        │
 Every 10 seconds (ReminderScheduler)                   ▼
   dose time reached on a dose day ──► DoseLog created, reminder sent, dose deducted from supply
                                                        │
                    days left = dose days the supply still covers (walks the real schedule)
                                                        │
                       days left ≤ "act when down to" threshold?
                          │                                   │
                       MANUAL                               AUTO
          "Running low" alert + Reorder now        OrderService.placeAutoReorder()
          (adds the usual packs to cart)           COD order to the plan's refill address (or the default
                                                   one), customer notified. Falls back to an alert if there's
                                                   no address, the PIN code isn't delivered to, no stock,
                                                   expired stock, or no valid prescription for an Rx medicine.
```

- A reminder counts the dose as taken. Tapping **Skip** adds it back, so the count stays right even if the customer never taps anything.
- `reorderPending` makes sure there is one alert or one auto order per low-supply cycle. It clears when the refill is delivered or the customer edits the plan's supply.
- A daily 9 AM sweep clears expired stock, switches off courses that have no dose days left, and re-checks every plan.
- Each cart line records how many packs each person gets. On delivery the units go to those members' plans in the
  same proportion (`domain/Shares` splits them so they always add up; older orders without per-person packs are
  split evenly). "Others" lines are delivered but not tracked. A member with no plan for that medicine yet gets one
  that holds the supply and waits for its first schedule: dose times plus every day, certain weekdays, or specific dates.
- Days left follow the real schedule, so weekly and custom-date plans aren't counted as daily.
- Items bought as **Common (OTC)** go straight onto the household's common shelf, which only lists what's at home and
  its expiry: no reminders or refill alerts. Adding that medicine to a person's list moves stock off the shelf: what the
  rest of their course needs (or all of it for an ongoing medicine); an emptied shelf entry disappears.
- Each store listing carries the expiry of its batch: set by the store, shown on the product page, printed on the
  invoice, and carried to the supply at home on delivery (the earliest expiry still at home is shown). Expired stock
  can't be ordered.
- Every morning (and on startup) expired stock at home is cleared with an alert: common-shelf entries and delivered
  medicines that were never scheduled are removed from My medicines; a scheduled plan keeps its reminders but its
  expired units are written off, which triggers the usual refill alert or auto order.
- Each reminder becomes an in-app notification (bell), a desktop notification (the browser polls `/api/notifications/poll` every 30 s), and an email if mail is enabled.
  Each browser has an `mz_device` cookie; a device that follows only some members doesn't pop up or count the others' alerts.

## Prescriptions

```
 upload 1-5 files ──► DRAFT (only the customer sees it) ──► Gemini reads each (PrescriptionScanService.scanAll)
        │                                                    score = PrescriptionScore (Java checks, not AI confidence)
        ▼
 /prescriptions/review: customer removes wrong files, adds the doctor's name, sends ──► PENDING
        │   requestedPacks = cart Rx lines for that patient, minus what approved prescriptions already cover
        ▼
 /admin/prescriptions: fast-track first; rows pre-filled (AI read / In cart / Check on file); pharmacist
        confirms doctor, patient, date, medicines and packs ──► APPROVED with PrescriptionItems
        │
        ▼ PrescriptionCartFiller (after commit): each medicine into the cart for the member whose patient name
          matches (PatientNames), else "Others" with the name; only what that patient doesn't already have
        │
 checkout ──► PrescriptionService.cover(): each Rx line from the valid prescriptions with packs left, the
          patient's own first, split if needed; uncovered medicines named ──► PrescriptionUse per order line
        │
 all Rx packs ordered ──► USED_UP (customer told); cancelling an order gives its packs back
```

- `Prescription.isValid()`: approved, medicines recorded, not expired, not used up, not removed. Older approvals
  without medicines stay on record but can't be used.
- Removing: never used for an order, deleted with its file; used, hidden from the customer (`removed`) but kept.
- Staff never see drafts (queue, "Recently reviewed", or the file).
- Invoices print each line's patient and prescriber (`OrderItem.patientName`, `prescribedBy`), copied at checkout.

## When an AI service is down

`GeminiClient`, `GroqClient` and `AssistantClient` skip a failing service for a while (Gemini 2 minutes, 15 when the
quota ran out; Groq 2 minutes, 5 when the quota ran out; the assistant 30 seconds, after waiting at most 8 seconds, so an
asleep assistant doesn't stall chat) and Groq and Gemini run at most 4 calls each at once, so a slow or dead service
never ties up the web server. Prescriptions then go to the pharmacist unread; chat is answered by whichever is up
(Python, then Groq, then Gemini), and says it's offline only when all are down. All show in `/actuator/health` as DEGRADED, never DOWN. `AiOutageTest` covers it.

## Monitoring

**Admin > Monitoring** shows the health of each part (database, disk, assistant service, database pool), errors and
performance, with no outside service needed:

- **Errors** are grouped like an issue tracker: unhandled request exceptions, anything logged at ERROR (scheduled jobs
  included) and JavaScript errors from shoppers' browsers. Each group shows how often it happened, where, for whom and
  the latest stack trace; resolve it and it reopens if it comes back. Every log line and the error page carry a
  request id, so a reference a customer quotes leads straight to the error.
- **Performance**: response times per endpoint (average, p95, max), slow requests, page load times measured in
  browsers, background job timings, memory and CPU, and the assistant service's own timings and errors.
- `/actuator/health` is public for load balancers (a stopped assistant shows as DEGRADED but the shop stays UP);
  `/actuator/prometheus` feeds Grafana or any Prometheus-compatible tool.

## Invoices and returns

- Prices are GST-inclusive. When an order ships it gets the next invoice number of the financial year
  (`MZ/2026-27/00001`); the invoice works out each line's taxable value from its HSN code and GST rate (set per
  medicine in the catalog). Delivery is taxed at the main item's rate as a composite supply. It prints Bill to (the
  billing address and GSTIN, or the buyer) and Ship to (the receiver and delivery address), both copied onto the order
  at checkout. Print or save it as PDF from the browser.
- Bills are rounded to the nearest rupee when the order is placed; the difference is shown as "Round off" in the cart,
  at checkout, on the order and on the invoice. Reports keep it as its own ledger line, outside taxable value and GST.
  Orders placed before rounding keep their paise.
- Every order, including automatic refills, must go to a PIN code listed and active under **Admin > Delivery areas**;
  `OrderService.createOrder` checks it, so checkout and refills follow the same rule.
- Customers can ask to return delivered items within the return window (**Admin > Returns > Settings**, 7 days by default),
  with a reason and 1 to 3 photos, then choose the pickup address (where the order went, a saved address or a new one
  in a served PIN code) and where the refund goes. Prepaid orders: the original method or MediZon Money. Cash on
  delivery: MediZon Money, a UPI ID or a bank account (bank numbers are masked to the customer). Orders paid fully with
  MediZon Money are refunded to it.
- The pickup day is picked in the admin's range (1 to 3 days by default) and shown before the request is sent;
  staff can move it on the return. An admin approves or rejects it with a note; approved returns are marked picked up
  (the packs leave the member's supply count, and can go back into stock).
- Refunds are what was paid for the packs, after any promo. The MediZon Money part is paid at pickup; the rest lands
  on its day (UPI 2, bank and card 7 days after pickup by default), paid by `RefundScheduler` every hour, or sooner
  from the return page. Each return keeps the refund timing it was promised when it was requested.

## Promos and MediZon Money

- **Promos** (`coupons` table, managed under **Admin > Promos**): percent (optionally capped) or flat, a minimum
  order of medicines, uses per customer each day, an optional last day, live or off. Five sample codes are added on a
  fresh database. Checkout lists the live ones; the applied code sits in the session and is checked again when the
  order is placed. `Discounts.split` spreads the discount over the lines by price, so cart, orders and invoices agree to
  the paisa; GST is worked out on the price after the discount. A code that has been used keeps its code.
- **MediZon Money** (`wallet_transactions` ledger, `WalletService`): refunds and cancellations pay in, orders pay out,
  and the balance is the sum. Each movement locks the account's row. At checkout it pays each order of a split cart as
  far as it goes and the chosen method pays the rest (`Order.walletUsed`); cancelled orders get it back at once.
  Deleting an account deletes its unspent balance.

## Help & support

- **Support** (in the category bar, the account menu and the side menu) starts with **Quick answers**: common questions
  (where is my order, returns, refunds, delivery charges, payment, cancelling, prescriptions) answered by the assistant.
  **Still need help?** raises a ticket: a topic, a subject, a description, optionally an order and up to 3 photos.
  Each order page has **Get help** for a ticket about that order.
- A ticket starts with the **support** team. Support replies, or passes it to a team: **pharmacy**, **payments**
  (finance), the **store** that handled the order, or the **admin**. The customer is told it's with that team; the
  handover note is internal. The team talks to the customer on the ticket and hands it back to support once solved;
  support closes it with a message. Support can follow a ticket after passing it on and add internal notes; the admin
  sees every ticket and can act on any that isn't closed.
- Closed tickets can be rated (1 to 5 stars) and reopened for 7 days, which puts them back with support.
- The customer is notified (in the app and by email) of every reply and change; staff get a notification when a
  ticket reaches their team. An open ticket page shows new replies without reloading.
- Staff work tickets under **Admin > Support**: what's waiting for them, what's with the teams, closed and all. A
  customer can have 5 open tickets and raise 10 a day. Deleting an account deletes its tickets and photos.

## JSON API

The site's pages are HTML (Thymeleaf). Everything under `/api` is JSON, errors included. POSTs need the CSRF token
(`X-CSRF-TOKEN` header, from the page's `_csrf` meta tag).

| Endpoint | What it returns |
|---|---|
| `GET /api/cart` | `cartId` of the open cart (`null` before the first medicine goes in, and for a signed-out visitor, whose cart lives in the session), lines with `cartItemId`, totals, and `shipmentCount` (how many stores it ships from) |
| `POST /api/cart/{medicineId}` `{"quantity": n}` | The line's new quantity and `cartItemId`, and the cart totals |
| `GET /api/orders` | The signed-in customer's orders, newest first |
| `GET /api/orders/{id}` | One order with its lines and totals |
| `GET /api/orders/{id}/invoice` | The GST invoice for an order that has shipped |
| `POST /api/stock-alerts/{medicineId}` `{"on": true}` | Turns a back-in-stock alert on or off for the signed-in customer |

Amounts are numbers in rupees (`1234.5`), never formatted text; the page adds the `₹` and the commas.

An account has one open cart (`carts` table, status `ACTIVE`). It keeps its id across sign-ins and devices; checkout
marks it `CHECKED_OUT`, the orders keep its id, and the next medicine added opens a new cart. Errors look like
`{"timestamp", "status", "error", "path", "requestId"}`, with `error` holding the message: 401 when not signed in, 403
without access or with a bad CSRF token, 404 for an unknown path or another account's order, 400 for a rule broken.

## Chat assistant

A chat button sits in the bottom-right corner of every page once you sign in. The conversation follows you across
pages, offers quick-reply chips and product cards, and can open pages for you (for example checkout).

**Customers** can ask it to:
- find medicines by name, salt or symptom ("something for a headache"), and explain a product;
- add items to the cart ("add 2 packs of cetirizine", "add the first one") and go to checkout;
- set dose reminders, asking for anything missing ("remind me to take metformin at 8 am and 8 pm");
- show today's doses, mark a dose taken or skipped, list medicines and supply left, refill what's running low;
- track orders, open the prescription upload page, send feedback or a question for the pharmacist;
- set one-off reminders ("remind me to book a blood test on friday at 9").

**Hybrid**: Python answers first. When it couldn't answer (`"unresolved": true` in its reply), or is down,
`ai/ChatFallback` answers instead (Groq, then Gemini when Groq is down or out of quota), with health guardrails (no
diagnosis or dose changes, emergencies to 112, never inventing orders, stock or prices) in the customer's language.
Groq can act through the same `AssistantTools` as Python (orders, returns, cart, dose reminders and reminders,
always as the signed-in user), so chat still does things while Python is asleep. Gemini can't act; it points to the
right page.

**Admins** can ask it for:
- what needs attention: orders to confirm and ship, prescriptions to review, low stock, recent bad reviews;
- review insights: it reads every review and feedback message, works out sentiment and topics (delivery, packaging,
  order accuracy, pricing, stock, prescriptions, support, website) and suggests improvements ranked by how often
  customers complain, with example quotes. Also on **Admin > Reviews > Get improvement ideas**;
- stock for a medicine, the low-stock list, and their own reminders ("remind me to call the supplier at 4 pm").

Emergencies ("chest pain", "can't breathe", overdose, self-harm) always get a call-112 reply first.

### How it works

```
 Browser widget ──► /api/assistant/chat (Spring) ──► Python /v1/chat
                                   ▲                       │ intent + details, or "run these actions"
                                   │                       ▼
                    AssistantTools runs actions ◄── tool_calls: search_medicines, add_to_cart, create_plan ...
                    as the signed-in user        ──► tool_results back to Python ──► reply, cards, chips
```

- **Python** (`medizon-assistant/assistant`): `nlu.py` trains a TF-IDF + logistic-regression intent model per role at
  startup from `intents.py`; `text.py` parses times, dates, quantities and medicine names; `dialog.py` runs the
  conversation flows and follow-up questions; `insights.py` analyses reviews. It never touches the database.
- **Java**: `ai/AssistantService` calls the service and runs requested actions through `ai/AssistantTools`, which
  has a separate allow-list for customers and admins, so the assistant can only do what that user could do.
- Conversation state and the last 40 messages are kept in the HTTP session.
- To teach it new wording, add phrases to `intents.py` and restart. Tests: `python -m unittest discover -s tests`.

## Project layout

```
src/main/java/com/medizon
├── config/      Security (role-based access), login, catalog seed, the one admin, upgrade to stores
├── domain/      JPA entities and enums (family members, plans, orders, addresses, delivery PIN codes, returns,
│                invoice counters ...) and Shares (splits packs between people)
├── repository/  Spring Data repositories
├── service/     Business logic, scheduler, notifications, email, file storage, addresses, delivery areas,
│                visitor cart, GST invoices, returns and refund payouts, promos, MediZon Money, return settings,
│                sales report (SQL aggregation) and Excel export
├── ai/          Assistant client, actions it may run, request loop; Gemini and Groq clients, prescription reader, chat fallback
├── web/         MVC controllers (Thymeleaf pages), customer and admin; DeliveryLocations ("Delivers to" cookies)
├── api/         JSON endpoints: notification polling, cart quantities and packs per person, location check,
│                current-password check, assistant chat
└── dto/         Forms and view models (address, billing, invoice, report period, sales rows ...)
src/main/resources
├── templates/   Bootstrap 5 pages (fragments/layout.html holds navbar, location picker, footer, cards)
└── static/      medizon.css theme, medizon.js (polling, desktop alerts, location picker, forms), assistant.js (chat widget)
src/test/java/com/medizon
├── domain/      MedicationPlanScheduleTest (days left for daily, weekly and custom-date schedules), SharesTest
├── service/     InvoiceMathTest, ReportPeriodTest, ExpirySweepTest, PrescriptionCoverageTest, PrescriptionDraftTest,
│                PatientNamesTest, SharedMedicineTest, CartOthersTest, ReturnableItemsTest (pickup, refund modes),
│                PromoAndWalletTest, AdminSettingsTest (Mockito)
├── dto/         PrescriptionScoreTest
└── ai/          AiOutageTest (assistant, Groq and Gemini down, slow or all)
```

Run the tests with `mvn test` (they are plain unit tests and need no database).

## Before production

- Replace `ddl-auto=update` with Flyway migrations.
- Connect a real payment gateway (Razorpay or similar) in `OrderService.createOrder`, and real payouts for
  UPI, bank and card refunds in `ReturnService.refundDue` (they're marked paid on their day, but no money moves yet).
- Move prescription files to object storage (S3) and keep them encrypted. On hosts with a wiped disk (e.g. Render
  without a persistent disk) uploads are lost on every deploy.
- Use a paid Gemini key: the free tier may use what's sent to improve Google's models.
- Add SMS / WhatsApp / mobile push in `NotificationService.notify` for reminders, since browsers only show notifications while a tab is open.
- Run one scheduler instance (or add ShedLock) if you scale horizontally.
- Rate-limit `/api/assistant/chat`, log assistant actions for audit, and set `ASSISTANT_TOKEN`.
- Have a licensed pharmacist confirm the dispensing flow meets local regulations for online pharmacies.
