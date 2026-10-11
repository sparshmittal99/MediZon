# MediZon

An online pharmacy where customers order medicines, get dose reminders, track how much supply they have at home, and get refills manually or automatically. Pharmacists and admins manage orders, prescriptions and stock. Both get a built-in chat assistant.

The repository has two services:

| Folder | What it is |
|---|---|
| [`medizon/`](medizon) | The main web app: Spring Boot, server-rendered with Thymeleaf, PostgreSQL |
| [`medizon-assistant/`](medizon-assistant) | The chat assistant: a small Python FastAPI service with its own intent model. It needs no external AI service or API key. |

---

## Features

### For customers
- **Shopping:** browse by category, search by name or salt, view product details, add to cart, check out.
- **Payments:** cash on delivery, or simulated UPI and card payments.
- **Orders:** track status (Placed → Confirmed → Shipped → Delivered), cancel before shipping, buy again, and review delivered orders.
- **Prescriptions:** upload a JPG, PNG or PDF. A pharmacist approves it with a validity date before prescription-only medicines can be ordered.
- **Medication plans:** set dose times, days and course length for each medicine.
  - **Dose reminders:** in-app (bell), as a desktop notification, and by email if mail is set up.
  - **Mark doses:** each dose can be marked taken or skipped.
  - **Supply tracking:** delivered orders add to your supply at home and each dose subtracts from it.
- **Refills:** when supply runs low you get either:
  - **Manual:** a "Running low" alert with a one-click reorder.
  - **Auto:** an order is placed automatically to your saved address.
- **One-off reminders**, e.g. "book a blood test on Friday at 9".
- **Notification centre**, profile and saved address, and feedback to the pharmacist.

### For pharmacists and admins
- **Dashboard** with what needs attention and daily site traffic.
- **Order pipeline:** confirm, ship, deliver or cancel orders.
- **Prescription review:** approve or reject, and set a validity date.
- **Catalogue management:** add and edit medicines, images, stock and active/inactive status, plus a low-stock list.
- **Users:** enable or disable customers, force sign-out, and create other admin accounts. Admins can't be self-registered.
- **Reviews and feedback**, with an **"Get improvement ideas"** button. It groups complaints by topic and sentiment and ranks suggestions.

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
| Embedded Tomcat | 10.1.31 |
| Thymeleaf (+ Spring Security extras) | 3.1.2 |
| Spring Validation, Spring Mail, Spring Scheduling | from Spring Boot 3.3.5 |
| PostgreSQL JDBC driver | 42.7.4 |
| Lombok | 1.18.46 |
| Maven | 3.9+ |

### Frontend
| Technology | Version |
|---|---|
| Bootstrap | 5.3.3 |
| Bootstrap Icons | 1.11.3 |
| Plain JavaScript | for the chat widget, notification polling and desktop alerts |

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

You don't need to create any tables. They are created automatically on the first start, and a starter medicine catalogue is seeded.

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

### Run the assistant tests
```bash
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
| `UPLOAD_DIR` | `uploads` | Folder for prescriptions and medicine images |
| `BASE_URL` | `http://localhost:8080` | Used in links inside emails |
| `MEDIZON_TZ` | `Asia/Kolkata` | Time zone for reminders |
| `MAIL_ENABLED` | `false` | Send emails. When off, emails are only logged. |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USER` / `MAIL_PASSWORD` / `MAIL_FROM` | Gmail SMTP | SMTP settings |
| `SERVER_PORT` | `8080` | HTTP port of the web app |

---

## How reminders and refills work

```
 Order delivered ──► supply at home += packs × units per pack
                                   │
 Scheduler (every 10 s)            ▼
   dose time reached ──► reminder sent, dose subtracted from supply
                                   │
           days left = units left ÷ (dose × times per day)
                                   │
              days left ≤ the plan's "act when down to" threshold?
                 │                                     │
              MANUAL                                 AUTO
   "Running low" alert + Reorder        order placed automatically (cash on delivery) to
                                        the saved address. Falls back to an alert if there
                                        is no address, no stock, or no valid prescription.
```

A dose counts as taken by default. Tapping **Skip** adds it back to supply.

---

## Project structure

```
MediZon/
├── medizon/                          Spring Boot web app
│   ├── Dockerfile
│   ├── docker-compose.yml            Postgres (+ app and assistant with --profile app)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/medizon/
│       │   ├── config/               Security, login, data seeding, first admin
│       │   ├── domain/               JPA entities and enums
│       │   ├── repository/           Spring Data repositories
│       │   ├── service/              Business logic, scheduler, notifications, email, files
│       │   ├── ai/                   Assistant client and the actions it may run
│       │   ├── web/                  MVC controllers (customer and admin pages)
│       │   ├── api/                  JSON endpoints (notifications, cart, assistant chat)
│       │   └── dto/                  Forms and view models
│       └── resources/
│           ├── application.properties
│           ├── templates/            Thymeleaf pages
│           └── static/               CSS and JS (chat widget, notifications)
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

For more on how the assistant and the scheduler work inside, see [`medizon/README.md`](medizon/README.md).
