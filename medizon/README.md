# MediZon

Online medicine ordering with dose reminders, supply tracking, manual or automatic refills,
prescription verification and a built-in chat assistant.

**Stack:** Java 17, Spring Boot 3.3, Spring Security, Spring Data JPA, PostgreSQL, Thymeleaf + Bootstrap 5.
The assistant is a separate Python service (FastAPI + scikit-learn) in [`../medizon-assistant`](../medizon-assistant); it needs no external AI service or API key.

## Run it

```bash
# 1. Start PostgreSQL
docker compose up -d db

# 2. Credentials for the first admin account (only used while no admin exists)
export MEDIZON_ADMIN_EMAIL=...
export MEDIZON_ADMIN_PASSWORD=...

# 3. Start the assistant (in another terminal)
cd ../medizon-assistant
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scriptsctivate
pip install -r requirements.txt
uvicorn assistant.app:app --port 8001

# 4. Run MediZon
mvn spring-boot:run
```

Open http://localhost:8080. Tables are created and the starter catalog is seeded automatically on first start.
Customers sign up from **Create account**. Admin accounts can't be registered or promoted from customer accounts;
the first one comes from the variables above and every other one is created by an existing admin under **Admin > Users**.

Everything runs in `Asia/Kolkata` by default; change it with `MEDIZON_TZ`.
To run the app and the assistant in Docker as well: `docker compose --profile app up --build`.
MediZon works without the assistant running; the chat just says it's offline.

## Configuration

All settings are in `application.properties` and can be overridden with environment variables:

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection |
| `ASSISTANT_URL` | Where the assistant service runs (default `http://localhost:8001`) |
| `ASSISTANT_TOKEN` | Optional shared secret; set the same value on both services |
| `MAIL_ENABLED`, `MAIL_HOST`, `MAIL_USER`, `MAIL_PASSWORD` | Email copies of reminders and alerts (logged only when disabled) |
| `UPLOAD_DIR` | Where prescription files are stored |
| `BASE_URL` | Used for links inside emails |
| `MEDIZON_ADMIN_EMAIL`, `MEDIZON_ADMIN_PASSWORD`, `MEDIZON_ADMIN_NAME` | First admin account, created on startup when no admin exists |

## Features

**Customers**: search and browse by category, cart, checkout (COD, simulated UPI/card), order tracking,
cancel before shipping, buy again, prescription upload, profile and address, notification centre,
desktop notifications, chat assistant.

**Pharmacist / admin**: dashboard, customer order pipeline (Placed → Confirmed → Shipped → Delivered / Cancelled),
prescription review with validity date, catalog management with stock, low-stock list, customer accounts,
creating other admin accounts, customer feedback, and a chat assistant for store tasks and review insights. Admin accounts are for running the store only; they can't shop, order or set reminders.

## How reminders and refills work

```
 Order delivered ──► supply at home += packs × units per pack ──► refill alert re-armed
                                                        │
 Every minute (ReminderScheduler)                       ▼
   dose time reached ──► DoseLog created, reminder sent, dose deducted from supply
                                                        │
                    days left = units left / (dose × times per day)
                                                        │
                       days left ≤ "act when down to" threshold?
                          │                                   │
                       MANUAL                               AUTO
          "Running low" alert + Reorder now        OrderService.placeAutoReorder()
          (adds the usual packs to cart)           COD order to saved address, customer notified.
                                                   Falls back to an alert if there's no address,
                                                   no stock, or no valid prescription for an Rx medicine.
```

- A reminder counts the dose as taken. Tapping **Skip** adds it back, so the count stays right even if the customer never taps anything.
- `reorderPending` makes sure there is one alert or one auto order per low-supply cycle. It clears when the refill is delivered or the customer edits the plan's supply.
- A daily 9 AM sweep re-checks every plan and switches off courses whose end date has passed.
- Each reminder becomes an in-app notification (bell), a desktop notification (the browser polls `/api/notifications/poll` every 30 s), and an email if mail is enabled.

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
├── config/      Security, login, catalog seed and first admin
├── domain/      JPA entities and enums
├── repository/  Spring Data repositories
├── service/     Business logic, scheduler, notifications, email, file storage
├── ai/          Assistant client, actions it may run, request loop
├── web/         MVC controllers (Thymeleaf pages), customer and admin
├── api/         JSON endpoints: notification polling, assistant chat
└── dto/         Forms and view models
src/main/resources
├── templates/   Bootstrap 5 pages (fragments/layout.html holds navbar, footer, cards)
└── static/      medizon.css theme, medizon.js (polling, desktop alerts), assistant.js (chat widget)
```

## Before production

- Replace `ddl-auto=update` with Flyway migrations.
- Connect a real payment gateway (Razorpay or similar) in `OrderService.createOrder`.
- Move prescription files to object storage (S3) and keep them encrypted.
- Add SMS / WhatsApp / mobile push in `NotificationService.notify` for reminders, since browsers only show notifications while a tab is open.
- Run one scheduler instance (or add ShedLock) if you scale horizontally.
- Rate-limit `/api/assistant/chat`, log assistant actions for audit, and set `ASSISTANT_TOKEN`.
- Have a licensed pharmacist confirm the dispensing flow meets local regulations for online pharmacies.
