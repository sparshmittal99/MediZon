# MediZon

An online pharmacy for the whole family. Customers order medicines for themselves, their family members or a shared household shelf, get dose reminders, track how much supply they have at home and when it expires, and get refills manually or automatically. Pharmacists and admins manage orders, prescriptions, returns and stock, issue GST tax invoices, and run sales reports with Excel export. Both get a built-in chat assistant.

The repository has two services:

| Folder | What it is |
|---|---|
| [`medizon/`](medizon) | The main web app: Spring Boot, server-rendered with Thymeleaf, PostgreSQL |
| [`medizon-assistant/`](medizon-assistant) | The chat assistant: a small Python FastAPI service with its own intent model. It needs no external AI service or API key. |

---

## Features

### For customers
- **Shopping:** browse by category, search by name or salt, view product details (including expiry), add to cart, check out.
- **Family members:** add up to 12 people (mother, son ...). Each has their own medicines, reminders, supply and refill settings. There is also a **Common (OTC)** shelf for household medicines.
- **Who it's for:** in the cart, tick who each medicine is for: one or more people, or the common shelf. Prescription medicines must be for a named person.
- **Payments:** cash on delivery, or simulated UPI and card payments.
- **Orders:** track status (Placed → Confirmed → Shipped → Delivered), cancel before shipping, buy again, and review delivered orders.
- **GST tax invoices:** available once an order ships. Print it or save it as PDF from the browser.
- **Returns:** return part or all of a delivered order within 7 days, with a reason and 1 to 3 photos. Track it through approval, pickup and refund.
- **Prescriptions:** upload a JPG, PNG or PDF. A pharmacist approves it with a validity date before prescription-only medicines can be ordered.
- **Medication plans**, per family member:
  - **Schedules:** dose times, plus every day, certain days of the week, or specific dates; optional course end date.
  - **Dose reminders:** in-app (bell), as a desktop notification, and by email if mail is set up.
  - **Mark doses:** each dose can be marked taken or skipped.
  - **Supply tracking:** delivered orders add to that person's supply at home and each dose subtracts from it. Days left follow the real schedule, so weekly medicines aren't counted as daily.
  - **Expiry tracking:** the earliest expiry still at home is shown. Expired stock is cleared automatically with an alert.
- **Refills:** when supply runs low you get either:
  - **Manual:** a "Running low" alert with a one-click reorder.
  - **Auto:** an order is placed automatically to your saved address, for the same family member.
- **Alerts on each device:** choose whether a browser pops up everyone's reminders or only some members' (for example, a parent's tablet shows only their own).
- **One-off reminders**, e.g. "book a blood test on Friday at 9".
- **Notification centre**, profile and saved address, and feedback to the pharmacist.

### For pharmacists and admins
- **Dashboard:** today's sales, net revenue after refunds, what needs attention (orders, prescriptions, returns, low stock) and daily site traffic.
- **Order pipeline:** confirm, ship, deliver or cancel orders. Shipping issues the GST invoice number.
- **GST invoices:** CGST + SGST for customers in the seller's state, IGST otherwise; HSN code and GST rate per medicine; consecutive numbers per financial year (`MZ/2026-27/00001`).
- **Returns:** review the photos, approve or reject with a note, mark picked up (choose whether the packs go back into stock), then refund.
- **Sales and returns report:** a date range, a month or a financial year, shown day-wise or month-wise. Export to **Excel** (summary with GST totals, plus optional month-wise, day-wise, orders, medicines sold, HSN summary and returns sheets) or **CSV**.
- **Prescription review:** approve or reject, and set a validity date.
- **Catalogue management:** add and edit medicines, images, stock, HSN code, GST rate, batch expiry and active/inactive status, plus a low-stock list. Expired and soon-to-expire stock is highlighted, and expired stock can't be sold.
- **Users:** enable or disable customers, force sign-out, and create other admin accounts. Admins can't be self-registered.
- **Reviews and feedback**, with a **"Get improvement ideas"** button. It groups complaints by topic and sentiment and ranks suggestions.

### Chat assistant
It's available on every page after sign-in.
- **Customers** can find medicines by name, salt or symptom, add to cart, set reminders in plain language ("remind me to take metformin at 8 am and 8 pm"), see today's doses, refill, track orders and send feedback.
- **Admins** can ask what needs attention, check stock and the low-stock list, get review insights, and set their own reminders.
- **Emergencies** (chest pain, overdose, self-harm and so on) always get a "call 112" reply first.
- **Limits:** the assistant can only do what the signed-in user is allowed to do. If the assistant service is down, the rest of the app keeps working.

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
| PostgreSQL JDBC driver | 42.7.4 |
| Lombok | 1.18.46 |
| JUnit 5 + Mockito (tests) | from `spring-boot-starter-test` |
| Maven | 3.9+ |

### Frontend
| Technology | Version |
|---|---|
| Bootstrap | 5.3.3 |
| Bootstrap Icons | 1.11.3 |
| Plain JavaScript | chat widget, cart steppers and member pickers, schedule form, return photo previews, notification polling and desktop alerts |

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
Set the login for the first admin account. It is only used while no admin exists yet.

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

Optionally, set the seller details printed on GST invoices (see [Configuration](#configuration)), for example `SELLER_STATE="Maharashtra"` and `SELLER_GSTIN=...`.

Then run:
```bash
cd medizon
mvn spring-boot:run
```

Open **http://localhost:8080**:
- Sign in with the admin account above to manage the store.
- Use **Create account** to sign up as a customer and shop.

### Run everything in Docker instead
```bash
cd medizon
docker compose --profile app up --build
```
This starts PostgreSQL, the assistant and the web app together. Put the `MEDIZON_ADMIN_*` values in a `medizon/.env` file first. That file is gitignored.

`docker-compose.yml` doesn't pass the `MAIL_*` or `SELLER_*` settings to the app yet. To use them in Docker, add them to the `app` service's `environment` list.

### Run the tests
```bash
# Web app: schedule maths, GST invoice maths, report periods, expiry sweep (JUnit 5 + Mockito)
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
| `MEDIZON_ADMIN_EMAIL` / `MEDIZON_ADMIN_PASSWORD` / `MEDIZON_ADMIN_NAME` | — | First admin account, created only if no admin exists |
| `ASSISTANT_URL` | `http://localhost:8001` | Where the assistant service runs |
| `ASSISTANT_TOKEN` | empty | Shared secret. Set the same value on both services. |
| `UPLOAD_DIR` | `uploads` | Folder for prescriptions, return photos and medicine images |
| `SELLER_NAME` | `MediZon Pharmacy` | Seller name on GST invoices |
| `SELLER_GSTIN` / `SELLER_DRUG_LICENSE` | empty | GSTIN and drug licence number on invoices |
| `SELLER_ADDRESS` / `SELLER_PHONE` / `SELLER_EMAIL` | empty | Seller contact details on invoices |
| `SELLER_STATE` | empty | Seller's state. Customers in the same state are charged CGST + SGST, others IGST. |
| `BASE_URL` | `http://localhost:8080` | Used in links inside emails |
| `MEDIZON_TZ` | `Asia/Kolkata` | Time zone for reminders |
| `MAIL_ENABLED` | `false` | Send emails. When off, emails are only logged. |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USER` / `MAIL_PASSWORD` / `MAIL_FROM` | Gmail SMTP | SMTP settings. For Gmail, use an App Password and set `MAIL_FROM` to your Gmail address. |
| `SERVER_PORT` | `8080` | HTTP port of the web app |

Uploads are limited to 5 MB per file and 16 MB per request (a return can carry 3 photos).

---

## How reminders and refills work

```
 Order delivered ──► each line's units are split between the members it was for
                     (a member without a plan for it gets one that waits for its first schedule;
                      common-shelf items just go on the shelf)
                                   │
 Scheduler (every 10 s)            ▼
   dose time reached on a dose day ──► reminder sent, dose subtracted from supply
                                   │
           days left = dose days the supply still covers, walking the real schedule
                                   │
              days left ≤ the plan's "act when down to" threshold?
                 │                                     │
              MANUAL                                 AUTO
   "Running low" alert + Reorder        order placed automatically (cash on delivery) to the
                                        saved address, for the same member. Falls back to an
                                        alert if there is no address, no stock, expired stock,
                                        or no valid prescription.
```

- A dose counts as taken by default. Tapping **Skip** adds it back to supply.
- One alert or one automatic order per low-supply cycle. It resets when the refill is delivered or the supply is edited.
- Every morning at 9 (and on startup), expired stock at home is cleared with an alert, finished courses are switched off, and every plan is re-checked for low supply. A scheduled plan keeps its reminders but its expired units are written off, which triggers the usual refill alert or order.
- **Common shelf:** only lists what's at home and its expiry, with no reminders. Adding that medicine to a person's list can move stock off the shelf.

## How invoices and returns work

- **Invoices:** prices include GST. When an order ships it gets the next invoice number of the financial year. Each line's taxable value is worked back out of the price (`taxable = price × 100 / (100 + rate)`), split into CGST + SGST or IGST by the customer's state. Delivery is taxed at the main item's rate.
- **Returns:** Requested → Approved (or Rejected, with a note) → Picked up → Refunded. Pickup takes the packs out of the family member's supply and can put them back into stock. The refund is the price paid per pack × packs returned.
- **Sales report:** sales count on the day an order was placed, refunds on the day they were paid out, and net revenue = sales − refunds.

---

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
│       │   │   ├── config/           Security, login, data seeding, first admin
│       │   │   ├── domain/           JPA entities and enums (members, plans, orders, returns, invoices ...)
│       │   │   ├── repository/       Spring Data repositories
│       │   │   ├── service/          Business logic, scheduler, notifications, invoices, returns, sales report, Excel
│       │   │   ├── ai/               Assistant client and the actions it may run
│       │   │   ├── web/              MVC controllers (customer and admin pages)
│       │   │   ├── api/              JSON endpoints (notifications, cart, assistant chat)
│       │   │   └── dto/              Forms and view models (invoice, report period, sales rows ...)
│       │   └── resources/
│       │       ├── application.properties
│       │       ├── templates/        Thymeleaf pages (incl. invoice, returns, family members, sales report)
│       │       └── static/           CSS and JS (chat widget, notifications, forms)
│       └── test/java/com/medizon/    JUnit tests: schedules, invoice maths, report periods, expiry sweep
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

For more on how the assistant, the scheduler, invoices and returns work inside, see [`medizon/README.md`](medizon/README.md).
For live demo of application visit https://medizon-mze9.onrender.com/
