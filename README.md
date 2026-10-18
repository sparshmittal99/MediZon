# MediZon

An online pharmacy for the whole family. Customers order medicines for themselves, their family members, a shared household shelf or someone else, deliver to any of their saved addresses, get dose reminders, track how much supply they have at home and when it expires, and get refills manually or automatically. Uploaded prescriptions are read by AI (Google Gemini) and, once a pharmacist approves them, their medicines go into the cart by themselves. Behind the shop, one admin and staff in their own roles (pharmacist, store, support, finance) run several stores, each with its own stock and prices, manage orders, prescriptions, returns and the PIN codes each store delivers to, issue GST tax invoices, answer support tickets and run sales reports with Excel export. Customers and the admin get a built-in chat assistant.

**Live demo:** https://medizon-mze9.onrender.com/

The repository has two services:

| Folder | What it is |
|---|---|
| [`medizon/`](medizon) | The main web app: Spring Boot, server-rendered with Thymeleaf, PostgreSQL |
| [`medizon-assistant/`](medizon-assistant) | The chat assistant: a small Python FastAPI service with its own intent model. It needs no external AI service or API key. |

The web app can also use **Google Gemini** for reading prescriptions, and **Groq** (with Gemini as backup) for chat questions the assistant can't answer. Both are optional: without `GROQ_API_KEY` and `GEMINI_API_KEY` (or while they're down) everything works, prescriptions just go to the pharmacist unread and the chat stays with the Python assistant.

---

## Features

### For customers
- **Shopping:** browse by category, search by name or salt, view product details (including expiry), add to cart, check out.
- **Out of stock:** the home page shows only what can be ordered now. In the shop, out-of-stock products show **Notify me** instead of Add; the customer is told (bell, desktop and email) as soon as a store delivering to them has it again.
- **Shop before signing in:** visitors can fill a cart; it moves into their account when they sign in, and "Sign in to checkout" brings them straight back to checkout.
- **"Delivers to" location:** pick where you want delivery from the header (device location, a pin on the map, an address search or a PIN code, using OpenStreetMap) and see straight away whether MediZon delivers there.
- **Address book:** save up to 15 delivery addresses (Home, Office or a named place), including addresses for someone else with the receiver's name and phone. One is the default; checkout and the header picker choose between them.
- **Billing address:** one optional billing address with an optional GSTIN, set from the account menu. Checkout shows it next to the delivery address and the tax invoice prints both. Without it, orders are billed to you at the delivery address.
- **Family members:** add up to 12 people (mother, son ...), each with a **patient name** (their full name as doctors write it on prescriptions). Each has their own medicines, reminders, supply and refill settings. There is also a **Common (OTC)** shelf for household medicines.
- **Who it's for:** in the cart, say how many packs each person gets (for example 2 for you and 1 for your mother), or put them on the common shelf. **Others** is for someone outside the family: it's delivered but not added to My medicines, and can share a line with family members. Prescription medicines must be for a named person. The cart and checkout show each line's patient.
- **Payments:** cash on delivery, or simulated UPI and card payments. Delivery is free above ₹499, otherwise ₹40.
- **Several stores, one checkout:** each product comes from the nearest store delivering to you that has it. A cart that ships from several stores becomes one order per store, paid for once.
- **Orders:** track status (Placed → Confirmed → Shipped → Delivered), cancel before shipping, buy again, and review delivered orders.
- **GST tax invoices:** available once an order ships, with the patient for each medicine and, for prescription medicines, the prescribing doctor and the prescription date. Print it or save it as PDF from the browser.
- **Returns:** return part or all of a delivered order within 7 days, with a reason and 1 to 3 photos. Track it through approval, pickup and refund.
- **Prescriptions** (see [How prescriptions work](#how-prescriptions-work)):
  - **Upload** up to 5 at once (one file per prescription, e.g. one per family member) from the category bar, the Prescriptions page or checkout.
  - **Check before sending:** each upload shows the medicines, patient and doctor the AI read, and which family member it goes to. Remove a wrong file, add the doctor's name, then send it to the pharmacist.
  - **Straight into the cart:** once a pharmacist approves it, its medicines go into the cart under the family member whose patient name is on it (or **Others**), in the quantities prescribed.
  - **Order part now, the rest later:** a prescription allows the packs prescribed, across any number of orders and automatic refills. When everything's ordered it's **Used up** and a new one is needed.
  - **Several prescriptions in one cart:** checkout takes each medicine from whichever approved prescription has it; nothing to choose. Medicines no prescription covers are named and blocked.
  - **Remove** any prescription, approved or not.
- **Medication plans**, per family member:
  - **Schedules:** dose times, plus every day, certain days of the week, or specific dates; optional course end date.
  - **Dose reminders:** in-app (bell), as a desktop notification, and by email if mail is set up.
  - **Mark doses:** each dose can be marked taken or skipped.
  - **Supply tracking:** delivered orders add to that person's supply at home and each dose subtracts from it. Days left follow the real schedule, so weekly medicines aren't counted as daily.
  - **Expiry tracking:** the earliest expiry still at home is shown. Expired stock is cleared automatically with an alert.
- **Refills:** when supply runs low you get either:
  - **Manual:** a "Running low" alert with a one-click reorder.
  - **Auto:** an order is placed automatically for the same family member, to the address chosen on that reminder (or your default address).
- **Alerts on each device:** choose whether a browser pops up everyone's reminders or only some members' (for example, a parent's tablet shows only their own).
- **One-off reminders**, e.g. "book a blood test on Friday at 9".
- **Account settings:** change your password (current password first, then the new one twice; other devices are signed out) and sign out other devices.
- **Help & support:** quick answers to common questions, then a support ticket (topic, description, an order, up to 3 photos) if still needed. A customer can have 5 open tickets and raise 10 a day.
- **Delete account:** personal data is deleted; orders and invoices are kept without name, phone or address, as the accounts require.
- **Notification centre**, profile, and feedback to the pharmacist.

### For staff

There is exactly **one admin**, whose sign-in comes from `MEDIZON_ADMIN_EMAIL` / `MEDIZON_ADMIN_PASSWORD`. The admin creates every other account under **Admin > Users** with a role, and each role sees only its own pages:

| Role | Can do |
|---|---|
| Admin | Everything: all stores, master catalogue, delivery areas, users, reports, monitoring |
| Pharmacist | Review prescriptions |
| Store | One store: its catalogue (stock, expiry, OTC prices), its orders, deliveries and returns |
| Support | Support tickets, reviews and feedback; looks up orders and invoices |
| Finance | Sales, GST and Excel/CSV reports; looks up orders and invoices |

Staff accounts run the shop only; they can't shop, order or set reminders.

- **Dashboard:** today's sales, net revenue after refunds, **sales by store for any day** (orders, sales, refunds, net and share per store), what needs attention (orders, prescriptions, returns, low stock) and daily site traffic.
- **Roles:** the header shows each staff member's role (Admin, Pharmacist, Store, Support, Finance). Customers' private address tags (Home, Office ...) aren't shown to stores.
- **Order pipeline:** confirm, ship, deliver or cancel orders. Shipping issues the GST invoice number.
- **GST invoices:** CGST + SGST for customers in the seller's state, IGST otherwise; HSN code and GST rate per medicine; consecutive numbers per financial year (`MZ/2026-27/00001`).
- **Returns:** review the photos, approve or reject with a note, mark picked up (choose whether the packs go back into stock), then refund.
- **Sales and returns report:** a date range, a month or a financial year, shown day-wise or month-wise. Export to **Excel** (summary with GST totals, plus optional month-wise, day-wise, orders, medicines sold, HSN summary and returns sheets) or **CSV**.
- **Prescription review:** each prescription arrives with an **AI score** out of 100 built from checks a pharmacist makes anyway (legible, doctor, registration number, signature, date, patient, medicines read, in the catalogue) and any concerns the AI noticed. Clear ones are **fast-tracked** to the top of the queue. The list of medicines and packs is **pre-filled** from the AI reading and the customer's cart (tagged *AI read*, *In cart*, *Check on file*), so the pharmacist checks it against the file, confirms doctor, patient and date, and approves. The pharmacist always decides; the score never approves anything. Uploads the customer hasn't sent are never shown to staff.
- **Stores and catalogues:** the admin keeps the master catalogue (medicines, images, HSN code, GST rate, MRP, prescription-only or not) and the stores. Each store lists products from it with its own stock, batch expiry and, for OTC medicines, its own price (never above MRP); prescription medicines sell at the master price everywhere. Low stock (under 50 packs) is listed, soon-to-expire stock is highlighted and expired stock can't be sold.
- **Delivery areas:** the PIN codes MediZon delivers to (add one or paste a list, with area, city and state; pause or remove), which stores deliver to each and in how many days. Orders to any other PIN code are refused, including automatic refills. The dashboard warns while none are set up, because nobody can order until then.
- **Users (admin):** create customer and staff accounts with a role, enable or disable them, force sign-out, and delete accounts. Customers sign up themselves; nobody can sign up as staff, and there's only ever one admin.
- **Support tickets:** a ticket starts with support, who reply or pass it to the pharmacy, payments, the store that handled the order or the admin; the customer is told who has it and notified of every reply.
- **Monitoring (admin):** health of each part (database, disk, assistant, Groq, Gemini), errors grouped like an issue tracker (server, scheduled jobs and shoppers' browsers) and performance (slow requests, page load times, background jobs). `/actuator/prometheus` feeds Grafana.
- **Reviews and feedback**, with a **"Get improvement ideas"** button. It groups complaints by topic and sentiment and ranks suggestions.

### Chat assistant
It's available on every page after sign-in.
- **Customers** can find medicines by name, salt or symptom, add to cart, set reminders in plain language ("remind me to take metformin at 8 am and 8 pm"), see today's doses, refill, track orders and send feedback.
- **Admins** can ask what needs attention, check stock and the low-stock list, get review insights, and set their own reminders.
- **Emergencies** (chest pain, overdose, self-harm and so on) always get a "call 112" reply first.
- **Hybrid:** the Python assistant answers first (it's free, instant and can act). Questions it can't answer, such as "can I take Dolo with alcohol?", go to Groq (or Gemini when Groq is down or out of quota), which gives general information with health guardrails (no diagnosis or dose changes, emergencies to 112) and replies in the customer's language, Hindi and Hinglish included.
- **Limits:** the assistant can only do what the signed-in user is allowed to do. If the Python service is down or asleep, Groq or Gemini answers instead; if all of them are down, the chat says it's offline. The rest of the app keeps working either way.

---

## Tech stack

### Backend: `medizon/`
| Technology | Version |
|---|---|
| Java | 17 |
| Spring Boot | 3.3.5 |
| Spring Framework | 6.1.14 |
| Spring Security | 6.3.4 |
| Spring Data JPA / Hibernate ORM | 6.5.3.Final |
| Spring JDBC (`JdbcTemplate`, for the sales report queries) | from Spring Boot 3.3.5 |
| Embedded Tomcat | 10.1.31 |
| Thymeleaf (+ Spring Security extras) | 3.1.2 |
| Spring Validation, Spring Mail, Spring Scheduling | from Spring Boot 3.3.5 |
| Apache POI (`poi-ooxml`, Excel export) | 5.3.0 |
| Google Gemini API (optional: prescription reading, backup chat fallback) | `gemini-flash-latest` by default, called over REST |
| Groq API (optional: chat fallback) | `llama-3.3-70b-versatile` by default, called over REST |
| PostgreSQL JDBC driver | 42.7.4 |
| Lombok | 1.18.46 |
| JUnit 5 + Mockito (tests) | from `spring-boot-starter-test` |
| Maven | 3.9+ |

### Frontend
| Technology | Version |
|---|---|
| Bootstrap | 5.3.3 |
| Bootstrap Icons | 1.11.3 |
| Plain JavaScript | chat widget, cart steppers and packs per person, schedule form, return photo previews, location picker, notification polling and desktop alerts |
| Leaflet + OpenStreetMap | Leaflet 1.9.4 map, OpenStreetMap tiles and Nominatim address search for the "Delivers to" picker; loaded in the browser only when the map opens, no API key |

### Assistant: `medizon-assistant/`
| Technology | Version |
|---|---|
| Python | 3.10+ (Docker image uses 3.11) |
| FastAPI | 0.115.6 |
| Uvicorn | 0.30.6 |
| scikit-learn (TF-IDF + logistic regression) | 1.4.2 |
| Pydantic | 2.x |

### Database and tooling
| Technology | Version |
|---|---|
| PostgreSQL | 16 |
| Docker / Docker Compose | optional, for running Postgres or everything |

---

## Run it locally

### 1. Prerequisites
- **JDK 17 or newer.** Check with `java -version`.
- **Maven 3.9+.** Check with `mvn -v`. You can also run the project from IntelliJ or VS Code instead.
- **Python 3.10+.** Check with `python --version`.
- **PostgreSQL 16:** either Docker Desktop, or a local PostgreSQL install.

### 2. Clone
```bash
git clone https://github.com/sparshmittal99/MediZon.git
cd MediZon
```

### 3. Start PostgreSQL
**Option A: Docker (easiest)**
```bash
cd medizon
docker compose up -d db
```
This starts Postgres on `localhost:5432` with database, user and password all set to `medizon`.

**Option B: local PostgreSQL.** Run this in `psql` as the `postgres` user:
```sql
CREATE USER medizon WITH PASSWORD 'medizon';
CREATE DATABASE medizon OWNER medizon;
```

You don't need to create any tables. They are created automatically on the first start, and a starter medicine catalogue is seeded. When you upgrade an existing database, data from older versions (plans and cart lines without a family member) is moved to the account holder automatically on startup.

### 4. Start the assistant (terminal 1)
```bash
cd medizon-assistant
python -m venv .venv

# Windows (PowerShell)
.venv\Scripts\Activate.ps1
# macOS / Linux
source .venv/bin/activate

pip install -r requirements.txt
uvicorn assistant.app:app --port 8001
```
Check it with http://localhost:8001/health, which should return `{"ok": true}`.

### 5. Start the web app (terminal 2)
Set the login for the admin account. It's applied on every start: the account is created if it doesn't exist, its password and name are updated to these, and any other admin account is disabled (there's only ever one). Leave them unset to keep the existing admin as it is.

```powershell
# Windows (PowerShell)
$env:MEDIZON_ADMIN_EMAIL="admin@medizon.local"
$env:MEDIZON_ADMIN_PASSWORD="ChangeMe123"
$env:MEDIZON_ADMIN_NAME="Admin"
```
```bash
# macOS / Linux
export MEDIZON_ADMIN_EMAIL=admin@medizon.local
export MEDIZON_ADMIN_PASSWORD=ChangeMe123
export MEDIZON_ADMIN_NAME=Admin
```

Optionally, set your own seller details for GST invoices and the site footer (see [Configuration](#configuration)), for example `SELLER_STATE="Maharashtra"` and `SELLER_GSTIN=...`. Until you do, placeholder values are used.

Optionally, turn on AI prescription reading with a Gemini API key (free from [Google AI Studio](https://aistudio.google.com)), and the chat fallback with a Groq API key (free from [console.groq.com](https://console.groq.com)). Keep the keys out of the code and out of git:
```powershell
# Windows: saved for new terminals, so open a new one afterwards
setx GEMINI_API_KEY "your-key"
setx GROQ_API_KEY "your-groq-key"
```
```bash
# macOS / Linux
export GEMINI_API_KEY=your-key
export GROQ_API_KEY=your-groq-key
```
The free tier has a small daily limit and may use what's sent to improve Google's models: test it with dummy prescriptions, and use a paid key before real customers' prescriptions go through it.

Then run:
```bash
cd medizon
mvn spring-boot:run
```

Open **http://localhost:8080**:
- Sign in with the admin account above to manage the store.
- **Add at least one delivery area first:** under **Admin > Delivery areas**, add the PIN codes you deliver to. Until then, customers can browse and fill a cart but can't place orders.
- Use **Create account** to sign up as a customer and shop. Save a delivery address whose PIN code is in a delivery area.

### Run everything in Docker instead
```bash
cd medizon
docker compose --profile app up --build
```
This starts PostgreSQL, the assistant and the web app together. Put the `MEDIZON_ADMIN_*` values in a `medizon/.env` file first. That file is gitignored. `GEMINI_API_KEY` and `GROQ_API_KEY` are passed through from the terminal you run it in (or the same `.env` file). After changing code, rebuild with `docker compose --profile app up -d --build`.

`docker-compose.yml` doesn't pass the `MAIL_*` or `SELLER_*` settings to the app yet. To use them in Docker, add them to the `app` service's `environment` list.

### Run the tests
```bash
# Web app: schedule maths, sharing packs between people, GST invoice maths, report periods, expiry sweep,
# prescription coverage, drafts, patient-name matching, AI score, AI outages (JUnit 5 + Mockito)
cd medizon
mvn test

# Assistant: parsers, intent model and conversation flows
cd medizon-assistant
python -m unittest discover -s tests
```

---

## Configuration

Every setting has a default for local development, and each can be overridden with an environment variable.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/medizon` | PostgreSQL JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `medizon` / `medizon` | Database login |
| `MEDIZON_ADMIN_EMAIL` / `MEDIZON_ADMIN_PASSWORD` / `MEDIZON_ADMIN_NAME` | — | The one admin account, applied on every start: created if missing, password and name updated, any other admin disabled. Unset: the existing admin is left alone. |
| `ASSISTANT_URL` | `http://localhost:8001` | Where the assistant service runs |
| `ASSISTANT_TOKEN` | empty | Shared secret. Set the same value on both services. |
| `GEMINI_API_KEY` | empty | Google Gemini key for reading prescriptions and the backup chat fallback. Set it on the **web app**, not the assistant. Empty turns both off. |
| `GROQ_API_KEY` | empty | Groq key for the chat fallback (answers what the Python assistant can't, or everything while it's down or asleep). Set it on the **web app**. |
| `GROQ_MODEL` | `llama-3.3-70b-versatile` | Groq model for the chat fallback. |
| `GEMINI_MODEL` | `gemini-flash-latest` | Gemini model. `gemini-flash-lite-latest` allows more free requests a day. |
| `AI_PROVIDER` | `gemini` | Which AI reads prescriptions |
| `AI_FAST_TRACK_SCORE` | `80` | AI score (out of 100, with no concerns) from which a prescription is fast-tracked for the pharmacist |
| `UPLOAD_DIR` | `uploads` | Folder for prescriptions, return photos and medicine images |
| `SELLER_NAME` | `MediZon Pharmacy` | Seller name on GST invoices and in the footer |
| `SELLER_GSTIN` / `SELLER_DRUG_LICENSE` | placeholder values | GSTIN and drug licence numbers on invoices. Replace them with your real ones. |
| `SELLER_ADDRESS` / `SELLER_PHONE` / `SELLER_EMAIL` | placeholder values (Mumbai) | Seller contact details on invoices and in the footer |
| `SELLER_STATE` | `Maharashtra` | Seller's state. Customers in the same state are charged CGST + SGST, others IGST. |
| `BASE_URL` | `http://localhost:8080` | Used in links inside emails |
| `MEDIZON_TZ` | `Asia/Kolkata` | Time zone for reminders |
| `MAIL_ENABLED` | `false` | Send emails. When off, emails are only logged. |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USER` / `MAIL_PASSWORD` / `MAIL_FROM` | Gmail SMTP | SMTP settings. For Gmail, use an App Password and set `MAIL_FROM` to your Gmail address. |
| `REMEMBER_ME_KEY` | random per start | Secret for "Keep me signed in". Without it, those sign-ins end when the app restarts. |
| `MONITORING_TOKEN` | empty | Lets a Prometheus scraper read `/actuator/prometheus` with `Authorization: Bearer <token>` (the admin always can) |
| `SLOW_REQUEST_MS` / `SLOW_QUERY_MS` | `1000` / `500` | Requests and database queries slower than this are logged |
| `ERROR_ALERT_SEVERITY` | `HIGH` | Errors at or above this severity alert the admin |
| `SERVER_PORT` | `8080` | HTTP port of the web app |

Uploads are limited to 5 MB per file and 26 MB per request (a return can carry 3 photos, an upload up to 5 prescriptions).

---

## How reminders and refills work

```
 Order delivered ──► each line's units are shared by the packs each person was ordered
                     (a member without a plan for it gets one that waits for its first schedule;
                      common-shelf items just go on the shelf; "Others" isn't tracked)
                                   │
 Scheduler (every 10 s)            ▼
   dose time reached on a dose day ──► reminder sent, dose subtracted from supply
                                   │
           days left = dose days the supply still covers, walking the real schedule
                                   │
              days left ≤ the plan's "act when down to" threshold?
                 │                                     │
              MANUAL                                 AUTO
   "Running low" alert + Reorder        order placed automatically (cash on delivery) for the same
                                        member, to the reminder's chosen address or the default one.
                                        Falls back to an alert if there is no address, MediZon doesn't
                                        deliver to its PIN code, no stock, expired stock, or no approved
                                        prescription with packs of it left.
```

- A dose counts as taken by default. Tapping **Skip** adds it back to supply.
- One alert or one automatic order per low-supply cycle. It resets when the refill is delivered or the supply is edited.
- Every morning at 9 (and on startup), expired stock at home is cleared with an alert, finished courses are switched off, and every plan is re-checked for low supply. A scheduled plan keeps its reminders but its expired units are written off, which triggers the usual refill alert or order.
- **Common shelf:** only lists what's at home and its expiry, with no reminders. Adding that medicine to a person's list can move stock off the shelf.

## How prescriptions work

```
 Customer uploads 1 to 5 files ──► each is a draft (staff can't see it) ──► Gemini reads them, a few at a time:
                                                                            medicines matched to the catalogue,
                                                                            patient, doctor, date, signature ...
                                          │
 Customer checks each one: right file? doctor's name ──► removes a wrong one, or sends them
                                          │
 Pharmacist queue (fast-tracked first by AI score) ──► medicines + packs pre-filled from the AI reading and
                                                       the cart (only what approved prescriptions don't cover yet)
                                          │ approves, confirming doctor, patient and date
                                          ▼
 Medicines go into the cart under the member whose patient name matches (else "Others") ──► customer notified
                                          │
 Checkout: each Rx medicine is taken from whichever approved prescription has packs left
           (the patient's own first; split across two if needed); anything uncovered is named and blocked
                                          │
 Each order line records the prescription(s) it used ──► cancelled orders give the packs back
                                                       ──► fully ordered prescription = "Used up"
```

- **Validity:** a prescription can be used only while it's approved with its medicines recorded, not expired, not used up and not removed. Ones approved before medicines were recorded stay on record but can't be used.
- **Removing:** a prescription never used for an order is deleted with its file; one that was is hidden from the customer but kept, because the pharmacy must keep what it dispensed against.
- **AI score:** worked out in Java from concrete checks, not the AI's own confidence, so the pharmacist sees why. It only reorders the queue.

## When Groq, Gemini or the assistant is down

The shop never depends on any of them:

| What's down | Shop, cart, checkout | Prescriptions | Chat |
|---|---|---|---|
| Groq (or out of quota) | works | works | Gemini answers instead |
| Gemini (or out of quota) | works | saved and sent to the pharmacist unread | Groq answers |
| Python assistant (down or asleep) | works | works | Groq answers (Gemini if Groq is down too) |
| All three | works | sent to the pharmacist unread | says it's offline |

After a failure each is skipped for a while (Gemini 2 minutes, 15 if the quota ran out; Groq 2 minutes, 5 if the quota ran out; the assistant 30 seconds, after waiting at most 8 seconds for it), so nobody waits on a timeout twice. At most 4 calls each to Groq and Gemini run at once. `/actuator/health` shows them as DEGRADED, never DOWN, so a load balancer won't restart a healthy shop.

## How delivery and checkout work

- **Delivery areas:** every order, including automatic refills, is checked against the active PIN codes in **Admin > Delivery areas** on the server. The header's "Delivers to" picker is only a quick check for the customer.
- **Stores:** shoppers see what the stores delivering to their location sell; each product comes from the nearest store with enough in stock, with that store's price and delivery time. Checkout makes one order per store (sharing a checkout reference), paid for once; the delivery fee is worked out on the whole cart and charged on the biggest order.
- **Location picker:** the browser turns the device location, a map pin or a typed address into a PIN code with OpenStreetMap's Nominatim, then asks MediZon whether it delivers there. The choice is remembered in cookies for 180 days and cleared on sign-out.
- **Visitor cart:** kept in the browser session until sign-in, then added to the account's cart (up to the stock available; items that can no longer be sold are skipped).
- **Packs per person:** each cart line records how many packs each person gets. On delivery, the tablets are shared in the same proportion, so they always add up.
- **Addresses at checkout:** the order keeps a copy of the receiver, phone and address it ships to, plus the billing name, address and GSTIN. Later edits to the address book don't change past orders or invoices.

## How invoices and returns work

- **Invoices:** prices include GST. When an order ships it gets the next invoice number of the financial year. Each line's taxable value is worked back out of the price (`taxable = price × 100 / (100 + rate)`), split into CGST + SGST or IGST by the delivery state. Delivery is taxed at the main item's rate. The invoice prints **Bill to** (the billing address and GSTIN, or you) and **Ship to** (the receiver and delivery address).
- **Returns:** Requested → Approved (or Rejected, with a note) → Picked up → Refunded. Pickup takes the packs out of the family member's supply and can put them back into stock. The refund is the price paid per pack × packs returned.
- **Sales report:** sales count on the day an order was placed, refunds on the day they were paid out, and net revenue = sales − refunds.

---

## Deploy

The live demo runs on [Render](https://render.com) and redeploys from GitHub:

1. **Push** your changes to `main`. With auto-deploy on, Render rebuilds from `medizon/Dockerfile`.
2. **Environment** of the **web app** service: the database (`DB_URL`, `DB_USER`, `DB_PASSWORD`), `MEDIZON_ADMIN_*`, `BASE_URL`, `SELLER_*`, and `GEMINI_API_KEY` and `GROQ_API_KEY` for AI. Paste secrets in Render only, never in the repository.
3. **Assistant (optional):** deploy `medizon-assistant` as a second web service from its Dockerfile, then set `ASSISTANT_URL` on the web app to its address (and the same `ASSISTANT_TOKEN` on both). Without it, the chat uses Groq or Gemini.
4. **Database changes apply themselves** on start: new tables and columns are created and older data is upgraded (no manual SQL).
5. **Check** `/actuator/health` says UP.

Before real customers use it:
- **Uploaded files** (prescriptions, photos, images) live in `UPLOAD_DIR`. Render's disk is wiped on every deploy unless you attach a persistent disk and point `UPLOAD_DIR` at it.
- **Free plans sleep** when idle, and reminders and back-in-stock checks don't run while asleep.
- Use a **paid Gemini key** (the free tier may use what's sent for training), and set your real **seller details** for invoices.

## Project structure

```
MediZon/
├── medizon/                          Spring Boot web app
│   ├── Dockerfile
│   ├── docker-compose.yml            Postgres (+ app and assistant with --profile app)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/medizon/
│       │   │   ├── config/           Security (role-based access), login, data seeding, the one admin
│       │   │   ├── domain/           JPA entities and enums (members, plans, orders, addresses, returns, invoices ...)
│       │   │   ├── repository/       Spring Data repositories
│       │   │   ├── service/          Business logic, scheduler, notifications, addresses, delivery areas, invoices,
│       │   │   │                     returns, sales report, Excel
│       │   │   ├── ai/               Assistant client and the actions it may run; Gemini client, prescription
│       │   │   │                     reader and chat fallback
│       │   │   ├── web/              MVC controllers (customer and admin pages), "Delivers to" location cookies
│       │   │   ├── api/              JSON endpoints (notifications, cart, location, account, orders, stock alerts,
│       │   │   │                     assistant chat, monitoring)
│       │   │   ├── monitoring/       Error grouping, performance timings, health of the assistant, Groq and Gemini
│       │   │   └── dto/              Forms and view models (address, billing, invoice, report period, sales rows ...)
│       │   └── resources/
│       │       ├── application.properties
│       │       ├── templates/        Thymeleaf pages (incl. addresses, billing, account, invoice, returns,
│       │       │                     family members, delivery areas, sales report)
│       │       └── static/           CSS and JS (chat widget, notifications, location picker, forms)
│       └── test/java/com/medizon/    JUnit tests: schedules, pack shares, invoice maths, report periods, expiry sweep,
│                                     prescriptions (coverage, drafts, AI score, patient names), AI outages
└── medizon-assistant/                Python assistant service
    ├── Dockerfile
    ├── requirements.txt
    ├── assistant/
    │   ├── app.py                    FastAPI endpoints: /health, /v1/chat, /v1/insights
    │   ├── nlu.py                    Intent model (TF-IDF + logistic regression)
    │   ├── intents.py                Training phrases per intent
    │   ├── text.py                   Parses times, dates, quantities and medicine names
    │   ├── dialog.py                 Conversation flows
    │   └── insights.py               Review and feedback analysis
    └── tests/
```

For live demo of this application visit https://medizon-mze9.onrender.com/.
