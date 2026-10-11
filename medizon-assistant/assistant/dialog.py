"""The conversation engine.

Java sends one message per call together with the opaque `state` from the previous reply. The engine works
out the intent, fills the details it needs (asking follow-up questions when something is missing), and when
it needs store data or wants something done it returns `tool_calls`. Java runs those as the signed-in user
and calls back with `tool_results`; the engine then resumes the same step and writes the final reply.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable

from . import insights
from .nlu import model
from .text import (is_no, is_skip, is_yes, join_words, medicine_query, normalize, parse_int, parse_ordinal,
                   parse_quantity, parse_times, parse_when, pretty_time, pretty_when, symptom_category)

SWITCH_CONFIDENCE = 0.6       # how sure we must be to abandon a half-finished flow for a new request


# ====================================================================== plumbing

@dataclass
class Ctx:
    role: str
    first_name: str
    message: str
    payload: dict
    state: dict
    results: dict
    now: datetime
    categories: list[str]

    @property
    def admin(self) -> bool:
        return self.role == "admin"


@dataclass
class Reply:
    reply: str | None = None
    tool_calls: list[dict] = field(default_factory=list)
    state: dict = field(default_factory=dict)
    cards: list[dict] = field(default_factory=list)
    suggestions: list[dict] = field(default_factory=list)
    navigate: str | None = None
    intent: str | None = None

    def as_dict(self) -> dict:
        return {"reply": self.reply, "tool_calls": self.tool_calls, "state": self.state, "cards": self.cards,
                "suggestions": self.suggestions, "navigate": self.navigate, "intent": self.intent}


def chip(label: str, message: str | None = None, payload: dict | None = None, href: str | None = None) -> dict:
    c: dict[str, Any] = {"label": label}
    if payload:
        c["payload"] = payload
    elif href:
        c["href"] = href
    else:
        c["message"] = message or label
    return c


def _keep(ctx: Ctx, **extra) -> dict:
    """State carried across turns even when no flow is active (recent search results, last medicine)."""
    base = {k: ctx.state[k] for k in ("last", "last_medicine") if k in ctx.state}
    base.update(extra)
    return base


def say(ctx: Ctx, text: str, suggestions=None, cards=None, navigate=None, **keep) -> Reply:
    return Reply(reply=text, state=_keep(ctx, **keep), suggestions=suggestions or [], cards=cards or [],
                 navigate=navigate)


def ask(ctx: Ctx, flow: str, slots: dict, awaiting: str, text: str, suggestions=None, cards=None, **keep) -> Reply:
    return Reply(reply=text, state=_keep(ctx, flow=flow, slots=slots, awaiting=awaiting, **keep),
                 suggestions=(suggestions or []) + [chip("Cancel", "cancel")], cards=cards or [])


def call(ctx: Ctx, flow: str, slots: dict, step: str, *tools: tuple[str, dict]) -> Reply:
    return Reply(tool_calls=[{"name": n, "args": a} for n, a in tools],
                 state=_keep(ctx, flow=flow, slots=slots, step=step))


def money(v) -> str:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return "₹–"
    return f"₹{f:,.0f}" if f == int(f) else f"₹{f:,.2f}"


def medicine_card(m: dict, admin: bool = False, quantity: int = 1, flow: str = "add_to_cart",
                  action: str = "Add to cart", extra: dict | None = None) -> dict:
    subtitle = " · ".join(x for x in [m.get("salt"), m.get("pack")] if x)
    card = {"type": "medicine", "id": m.get("id"), "title": m.get("name"), "subtitle": subtitle,
            "price": money(m.get("price")), "rx": bool(m.get("rx")), "in_stock": bool(m.get("in_stock")),
            "image": m.get("image"), "url": m.get("url"), "actions": []}
    if m.get("mrp") and m.get("price") and float(m["mrp"]) > float(m["price"]):
        card["mrp"] = money(m["mrp"])
    if admin:
        card["badge"] = f'{m.get("stock", 0)} in stock' + ("" if m.get("active", True) else " · hidden")
        card["url"] = m.get("admin_url") or card["url"]
        card["actions"].append({"label": "Edit", "href": m.get("admin_url") or m.get("url")})
        return card
    if m.get("in_stock") or flow != "add_to_cart":
        payload = {"intent": flow, "medicine_id": m.get("id"), "medicine_name": m.get("name"), "quantity": quantity}
        payload.update(extra or {})
        card["actions"].append({"label": action, "payload": payload, "primary": True})
    card["actions"].append({"label": "View", "href": m.get("url")})
    return card


def remember(items: list[dict]) -> list[dict]:
    return [{"id": m.get("id"), "name": m.get("name"), "in_stock": m.get("in_stock")} for m in items[:6]]


def pick_from(items: list[dict], query: str | None) -> dict | None:
    """One clear match: the only result, or a result whose name starts with the query."""
    if len(items) == 1:
        return items[0]
    q = normalize(query or "")
    if not q:
        return None
    exact = [m for m in items if normalize(m.get("name", "")) == q]
    if len(exact) == 1:
        return exact[0]
    starts = [m for m in items if normalize(m.get("name", "")).startswith(q)]
    return starts[0] if len(starts) == 1 else None


# ====================================================================== shared intents

def greet(ctx: Ctx, slots, step, answer) -> Reply:
    name = f", {ctx.first_name}" if ctx.first_name else ""
    if ctx.admin:
        return say(ctx, f"Hi{name}! I'm your MediZon store assistant. I can tell you what needs attention, "
                        "read customer reviews and suggest improvements, check stock, and keep reminders for you.",
                   ADMIN_CHIPS)
    return say(ctx, f"Hi{name}! I'm the MediZon assistant. I can find medicines, add them to your cart, set dose "
                    "reminders, track orders and take you to checkout. What do you need?", CUSTOMER_CHIPS)


def help_(ctx: Ctx, slots, step, answer) -> Reply:
    if ctx.admin:
        return say(ctx, "Here's what I can do:\n• **Store tasks**: orders to confirm or ship, prescriptions to review\n"
                        "• **Review insights**: what customers like and what to improve\n"
                        "• **Stock**: low-stock list or stock for one medicine\n"
                        "• **Reminders**: 'remind me to call the supplier tomorrow at 11'\n"
                        "• **Navigation**: 'open prescriptions'", ADMIN_CHIPS)
    return say(ctx, "Here's what I can do:\n• **Find medicines** by name, salt or symptom\n"
                    "• **Add to cart** and take you to **checkout**\n"
                    "• **Dose reminders**: 'remind me to take metformin at 8 am and 8 pm'\n"
                    "• **Today's doses** and marking them taken\n"
                    "• **Refills** for medicines running low\n"
                    "• **Order tracking**, **feedback** and other reminders", CUSTOMER_CHIPS)


def thanks(ctx: Ctx, slots, step, answer) -> Reply:
    return say(ctx, "You're welcome! Anything else I can help with?",
               ADMIN_CHIPS[:2] if ctx.admin else CUSTOMER_CHIPS[:3])


def bye(ctx: Ctx, slots, step, answer) -> Reply:
    return say(ctx, f"Take care{', ' + ctx.first_name if ctx.first_name else ''}! I'm here whenever you need me.")


def cancel(ctx: Ctx, slots, step, answer) -> Reply:
    return say(ctx, "Okay, I've cancelled that. What would you like to do instead?",
               ADMIN_CHIPS if ctx.admin else CUSTOMER_CHIPS)


def emergency(ctx: Ctx, slots, step, answer) -> Reply:
    return Reply(reply="This could be a medical emergency. **Call 112 now** or go to the nearest emergency department. "
                       "Please don't wait for an online reply.\n\nIf you're having thoughts of harming yourself, you can "
                       "also call the Tele-MANAS helpline on **14416**, any time.", state={})


def _reminder_text(message: str) -> str:
    t = message.strip()
    m = re.search(r"remind(?:er)?(?: me)?(?: (?:to|about|that|of|for))?\s+(.*)", t, re.I)
    text = m.group(1) if m else t
    text = re.sub(r"^(set|create|add)\s+(a\s+)?reminder\s+(to|for|about)?\s*", "", text, flags=re.I)
    patterns = [r"\bin\s+(an?|\d+)\s*(min(ute)?s?|hours?|hrs?|days?|weeks?)\b",
                r"\b(at|by|around)\s+\d{1,2}([:.]\d{2})?\s*(a\.?m\.?|p\.?m\.?)?",
                r"\b\d{1,2}([:.]\d{2})?\s*(a\.?m\.?|p\.?m\.?)",
                r"\b(on\s+)?(next\s+)?(mon|tues|wednes|thurs|fri|satur|sun)day\b",
                r"\b(day after tomorrow|tomorrow|today|tonight|next week|this (morning|afternoon|evening))\b",
                r"\b(on\s+)?\d{1,2}(st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b",
                r"\b(on\s+)?\d{1,2}[/-]\d{1,2}([/-]\d{2,4})?\b",
                r"\b(in the )?(morning|evening|afternoon|night)\b"]
    for p in patterns:
        text = re.sub(p, " ", text, flags=re.I)
    text = re.sub(r"\s+", " ", text).strip(" ,.!?-")
    text = re.sub(r"^(to|about|for|me)\s+", "", text, flags=re.I)
    return text if len(text) >= 2 and not re.fullmatch(r"(me|a|reminder|set|please)", text, re.I) else ""


def general_reminder(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "set_general_reminder"
    if step == "done":
        r = ctx.results.get("create_reminder") or {}
        if r.get("error"):
            return say(ctx, r["error"])
        when = datetime.fromisoformat(slots["due_at"])
        return say(ctx, f"Done! I'll remind you to **{slots['text']}** {pretty_when(when, ctx.now)}. "
                        "You'll get it as a notification.",
                   [chip("My reminders", "show my reminders")])

    awaiting = ctx.state.get("awaiting")
    if answer is not None:
        if awaiting == "text":
            slots["text"] = _reminder_text(answer) or answer.strip()
            when = parse_when(answer, ctx.now)
            if when and not slots.get("due_at"):
                slots["due_at"] = when.isoformat(timespec="minutes")
        elif awaiting == "when":
            when = parse_when(answer, ctx.now)
            if not when:
                return ask(ctx, flow, slots, "when", "Sorry, I didn't get the time. Try 'tomorrow at 10 am', "
                                                       "'friday 6 pm' or 'in 2 hours'.", WHEN_CHIPS)
            slots["due_at"] = when.isoformat(timespec="minutes")

    if not slots.get("text"):
        return ask(ctx, flow, slots, "text", "Sure. What should I remind you about?")
    if not slots.get("due_at"):
        return ask(ctx, flow, slots, "when", f"When should I remind you to **{slots['text']}**?", WHEN_CHIPS)
    if datetime.fromisoformat(slots["due_at"]) <= ctx.now:
        slots.pop("due_at")
        return ask(ctx, flow, slots, "when", "That time has already passed. When should I remind you?", WHEN_CHIPS)
    return call(ctx, flow, slots, "done", ("create_reminder", {"text": slots["text"], "due_at": slots["due_at"]}))


def list_general_reminders(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "show":
        return call(ctx, "list_general_reminders", slots, "show", ("list_reminders", {}))
    items = ctx.results.get("list_reminders") or []
    if not items:
        return say(ctx, "You don't have any upcoming reminders.", [chip("Set a reminder", "set a reminder")])
    lines = [f"• **{r['text']}**: {pretty_when(datetime.fromisoformat(r['due_at']), ctx.now)}"
             + (" (overdue)" if r.get("overdue") else "") for r in items[:8]]
    chips = [chip(f"Done: {r['text'][:24]}", payload={"intent": "complete_reminder", "reminder_id": r["id"]})
             for r in items[:3]]
    return say(ctx, "Your reminders:\n" + "\n".join(lines), chips + [chip("Add another", "set a reminder")])


def complete_reminder(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "complete_reminder"
    if step == "done":
        r = ctx.results.get("complete_reminder") or {}
        if r.get("error"):
            return say(ctx, r["error"])
        return say(ctx, f"Marked **{r.get('text', 'the reminder')}** as done.", [chip("My reminders", "show my reminders")])
    if slots.get("reminder_id"):
        return call(ctx, flow, slots, "done", ("complete_reminder", {"id": slots["reminder_id"]}))
    if step != "pick":
        return call(ctx, flow, slots, "pick", ("list_reminders", {}))
    items = ctx.results.get("list_reminders") or []
    if not items:
        return say(ctx, "You don't have any open reminders.")
    if len(items) == 1:
        slots["reminder_id"] = items[0]["id"]
        return call(ctx, flow, slots, "done", ("complete_reminder", {"id": items[0]["id"]}))
    return say(ctx, "Which reminder is done?",
               [chip(r["text"][:30], payload={"intent": "complete_reminder", "reminder_id": r["id"]}) for r in items[:5]])


# ====================================================================== customer intents

CUSTOMER_CHIPS = [chip("Find a medicine", "I'm looking for a medicine"), chip("Set a dose reminder", "set medicine reminder"),
                  chip("Today's doses", "today's doses"), chip("Track my order", "where is my order"),
                  chip("Go to checkout", "checkout")]
WHEN_CHIPS = [chip("In 1 hour", "in 1 hour"), chip("Tomorrow 9 AM", "tomorrow at 9 am"),
              chip("This evening", "today at 6 pm")]


def search(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "search_medicine"
    if answer is not None:
        slots["query"] = medicine_query(answer) or answer.strip()
        slots["category"] = symptom_category(answer, ctx.categories)
    if step == "results":
        items = ctx.results.get("search_medicines") or []
        label = slots.get("query") or slots.get("category") or "that"
        if not items:
            return say(ctx, f"I couldn't find anything for **{label}**. Try the brand or salt name "
                            "(for example 'paracetamol' instead of 'fever tablet').",
                       [chip("Browse the shop", href="/medicines")])
        note = ""
        if slots.get("category") and slots.get("symptom"):
            note = ("\n\nThese are common over-the-counter options. If symptoms are severe or last more than "
                    "3 days, please see a doctor.")
        rx = " Items marked Rx need a valid prescription." if any(m.get("rx") for m in items) else ""
        head = f"Here's what I found for **{label}**:" if len(items) > 1 else "I found this:"
        return say(ctx, head + rx + note, [chip("Go to checkout", "checkout"), chip("View cart", "show my cart")],
                   cards=[medicine_card(m) for m in items[:6]], last=remember(items),
                   last_medicine=remember(items)[0] if len(items) == 1 else ctx.state.get("last_medicine"))
    if not slots.get("query") and not slots.get("category"):
        return ask(ctx, flow, slots, "query", "What are you looking for? You can type a medicine name, salt or symptom.")
    return call(ctx, flow, slots, "results",
                ("search_medicines", {"query": slots.get("query") or "", "category": slots.get("category") or ""}))


def medicine_info(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "medicine_info"
    if answer is not None:
        slots["query"] = medicine_query(answer) or answer.strip()
    if step == "info":
        m = ctx.results.get("medicine_details")
        if not m or m.get("error"):
            return say(ctx, f"I couldn't find **{slots.get('query', 'that')}** in our catalog.",
                       [chip("Search the shop", href="/medicines")])
        parts = [f"**{m['name']}**" + (f" ({m['salt']})" if m.get("salt") and m["salt"] != m["name"] else "")
                 + (f" by {m['manufacturer']}" if m.get("manufacturer") else "") + "."]
        parts.append(f"{money(m.get('price'))} for a pack of {m.get('pack', '')}."
                     + (" **Prescription required.**" if m.get("rx") else " No prescription needed."))
        if m.get("description"):
            parts.append(m["description"])
        parts.append("_This is general product information, not medical advice. Ask your doctor or pharmacist "
                     "before starting, stopping or changing a medicine._")
        return say(ctx, "\n\n".join(parts),
                   [chip("Set a reminder for it", payload={"intent": "set_reminder", "medicine_id": m["id"],
                                                            "medicine_name": m["name"]})],
                   cards=[medicine_card(m)], last_medicine={"id": m["id"], "name": m["name"], "in_stock": m.get("in_stock")})
    if not slots.get("query") and not slots.get("medicine_id"):
        last = ctx.state.get("last_medicine")
        if last:
            slots["medicine_id"] = last["id"]
        else:
            return ask(ctx, flow, slots, "query", "Which medicine would you like to know about?")
    args = {"id": slots["medicine_id"]} if slots.get("medicine_id") else {"query": slots["query"]}
    return call(ctx, flow, slots, "info", ("medicine_details", args))


def add_to_cart(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "add_to_cart"
    qty = int(slots.get("quantity") or 1)

    if step == "added":
        r = ctx.results.get("add_to_cart") or {}
        if r.get("error"):
            return say(ctx, r["error"], [chip("View cart", "show my cart")])
        note = " It needs an approved prescription, which you can upload at checkout." if r.get("rx") else ""
        return say(ctx, f"Added **{r.get('added')}** to your cart.{note} Your cart has {r.get('cart_count')} item(s).",
                   [chip("Go to checkout", "checkout"), chip("View cart", "show my cart"),
                    chip("Find another medicine", "I'm looking for a medicine")],
                   last_medicine={"id": slots.get("medicine_id"), "name": slots.get("medicine_name")})

    last = ctx.state.get("last") or []
    if answer is not None:
        idx = parse_ordinal(answer)
        if idx is not None and last and -len(last) <= idx < len(last):
            slots["medicine_id"], slots["medicine_name"] = last[idx]["id"], last[idx]["name"]
        else:
            slots["query"] = medicine_query(answer) or answer.strip()
            slots.pop("medicine_id", None)
        if parse_quantity(answer):
            slots["quantity"] = qty = parse_quantity(answer)

    if step == "pick":
        items = ctx.results.get("search_medicines") or []
        in_stock = [m for m in items if m.get("in_stock")]
        if not items:
            return ask(ctx, flow, slots, "query", f"I couldn't find **{slots.get('query')}**. What's the medicine called?")
        if not in_stock:
            return say(ctx, f"Sorry, **{items[0]['name']}** is out of stock right now.",
                       cards=[medicine_card(m) for m in items[:3]], last=remember(items))
        choice = pick_from(in_stock, slots.get("query"))
        if choice:
            slots["medicine_id"], slots["medicine_name"] = choice["id"], choice["name"]
        else:
            return ask(ctx, flow, slots, "pick", "I found a few. Which one should I add?",
                       cards=[medicine_card(m, quantity=qty) for m in in_stock[:5]], last=remember(in_stock))

    if slots.get("medicine_id"):
        return call(ctx, flow, slots, "added", ("add_to_cart", {"medicine_id": slots["medicine_id"], "quantity": qty}))
    if slots.get("query"):
        return call(ctx, flow, slots, "pick", ("search_medicines", {"query": slots["query"], "category": ""}))
    if last:
        return ask(ctx, flow, slots, "pick", "Which one should I add?",
                   cards=[], suggestions=[chip(m["name"], payload={"intent": flow, "medicine_id": m["id"],
                                                                   "medicine_name": m["name"], "quantity": qty})
                                          for m in last[:4] if m.get("in_stock", True)])
    return ask(ctx, flow, slots, "query", "Which medicine should I add to your cart?")


def view_cart(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "show":
        return call(ctx, "view_cart", slots, "show", ("cart_summary", {}))
    c = ctx.results.get("cart_summary") or {}
    if not c.get("items"):
        return say(ctx, "Your cart is empty. Tell me what you're looking for and I'll find it.",
                   [chip("Find a medicine", "I'm looking for a medicine"), chip("Refill my medicines", "refill")])
    lines = [f"• {i['quantity']} × {i['name']}: {money(i['line_total'])}" for i in c["items"]]
    delivery = "free delivery" if not c.get("delivery") else f"{money(c['delivery'])} delivery"
    rx = "\n\nSome items need a prescription; you can upload it at checkout." if c.get("needs_prescription") else ""
    return say(ctx, "Your cart:\n" + "\n".join(lines) + f"\n\n**Total {money(c.get('total'))}** ({delivery}).{rx}",
               [chip("Go to checkout", "checkout"), chip("Open cart", href="/cart")])


def checkout(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "go":
        return call(ctx, "checkout", slots, "go", ("cart_summary", {}))
    c = ctx.results.get("cart_summary") or {}
    if not c.get("items"):
        return say(ctx, "Your cart is empty, so there's nothing to check out yet. What would you like to order?",
                   [chip("Find a medicine", "I'm looking for a medicine"), chip("Refill my medicines", "refill")])
    rx = " You'll need to upload or pick a prescription there for the Rx items." if c.get("needs_prescription") else ""
    return say(ctx, f"Taking you to checkout. Your total is **{money(c.get('total'))}** for "
                    f"{len(c['items'])} item(s).{rx}", navigate="/checkout")


def track_order(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "show":
        return call(ctx, "track_order", slots, "show", ("my_orders", {}))
    orders = ctx.results.get("my_orders") or []
    if not orders:
        return say(ctx, "You haven't placed any orders yet.", [chip("Find a medicine", "I'm looking for a medicine")])
    wanted = re.search(r"\bmz[- ]?\w+", normalize(ctx.message or ""))
    if wanted:
        key = re.sub(r"[- ]", "", wanted.group(0))
        match = [o for o in orders if normalize(o["order_number"]).replace("-", "") == key]
        orders = match or orders
    cards = [{"type": "info", "title": o["order_number"], "badge": o["status"],
              "subtitle": f'{money(o["total"])} · {len(o["items"])} item(s) · placed {o["placed"]}',
              "url": o["url"]} for o in orders[:3]]
    o = orders[0]
    text = {
        "Placed": "is placed and waiting for a pharmacist to confirm it.",
        "Confirmed": "is confirmed and being packed.",
        "Shipped": "has shipped and is on its way.",
        "Delivered": "has been delivered.",
        "Cancelled": "was cancelled.",
    }.get(o["status"], f"is {o['status'].lower()}.")
    return say(ctx, f"Your latest order **{o['order_number']}** {text}", [chip("All orders", href="/orders")], cards=cards)


def set_reminder(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "set_reminder"
    if step == "created":
        r = ctx.results.get("create_plan") or {}
        if r.get("error"):
            return say(ctx, r["error"], [chip("Open my medicines", href="/plans")])
        times = join_words([pretty_time(t) for t in r.get("times", slots.get("times", []))])
        supply = f" You have about {r['days_left']} day(s) of supply." if r.get("days_left") is not None else ""
        return say(ctx, f"Done! I'll remind you to take **{r.get('dose', '1')} of {r.get('medicine')}** every day at "
                        f"{times}.{supply} I'll also tell you when it's running low.",
                   [chip("Today's doses", "today's doses"), chip("Edit in My medicines", href="/plans")])

    awaiting = ctx.state.get("awaiting")
    if answer is not None:
        if awaiting == "query":
            slots["query"] = medicine_query(answer) or answer.strip()
            slots.pop("medicine_id", None)
            slots["times"] = slots.get("times") or parse_times(answer, use_frequency=True) or None
        elif awaiting == "pick":
            idx = parse_ordinal(answer)
            last = ctx.state.get("last") or []
            if idx is not None and last and -len(last) <= idx < len(last):
                slots["medicine_id"], slots["medicine_name"] = last[idx]["id"], last[idx]["name"]
            else:
                slots["query"] = medicine_query(answer) or answer.strip()
                slots.pop("medicine_id", None)
        elif awaiting == "times":
            times = parse_times(answer)
            if not times:
                return ask(ctx, flow, slots, "times", "Sorry, I didn't get the time. Try '8 am', '8 am and 9 pm' or "
                                                       "'twice a day'.", TIME_CHIPS)
            slots["times"] = times
        elif awaiting == "units":
            if is_skip(answer) or is_no(answer):
                slots["units"] = -1
            else:
                n = parse_int(answer)
                if n is None:
                    return ask(ctx, flow, slots, "units", "Just the number, please, for example '20'. Or say 'skip'.",
                               [chip("Skip", "skip")])
                slots["units"] = n

    if step == "pick":
        items = ctx.results.get("search_medicines") or []
        if not items:
            missing = slots.pop("query", None)
            offer = [chip("Set a one-off reminder instead",
                          payload={"intent": "set_general_reminder", "text": missing})] if missing else []
            return ask(ctx, flow, slots, "query", f"I couldn't find **{missing or 'that'}** in our catalog. "
                                                  "What's the medicine called?", offer)
        choice = pick_from(items, slots.get("query"))
        if choice:
            slots["medicine_id"], slots["medicine_name"] = choice["id"], choice["name"]
        else:
            return ask(ctx, flow, slots, "pick", "Which one is it?",
                       cards=[medicine_card(m, flow=flow, action="Choose") for m in items[:5]], last=remember(items))

    if step == "check":
        same = [p for p in (ctx.results.get("my_plans") or [])
                if p.get("active") and p.get("medicine_id") == slots.get("medicine_id")]
        if same:
            p = same[0]
            return say(ctx, f"You already get reminders for **{p['medicine']}** at "
                            f"{join_words([pretty_time(t) for t in p['times']])}. You can change the times in My medicines.",
                       [chip("Open My medicines", href="/plans"), chip("Today's doses", "today's doses")])
        slots["checked"] = True

    if not slots.get("medicine_id"):
        if slots.get("query"):
            return call(ctx, flow, slots, "pick", ("search_medicines", {"query": slots["query"], "category": ""}))
        return ask(ctx, flow, slots, "query", "Which medicine should I remind you about?")
    if not slots.get("checked"):
        return call(ctx, flow, slots, "check", ("my_plans", {}))
    if not slots.get("times"):
        return ask(ctx, flow, slots, "times", f"What time(s) do you take **{slots['medicine_name']}**? "
                                              "For example '8 am and 8 pm' or 'twice a day'.", TIME_CHIPS)
    if slots.get("units") is None:
        return ask(ctx, flow, slots, "units", f"How many units of {slots['medicine_name']} do you have at home right now? "
                                              "I use it to warn you before you run out.", [chip("Skip", "skip")])
    return call(ctx, flow, slots, "created", ("create_plan", {
        "medicine_id": slots["medicine_id"], "times": slots["times"], "dose": int(slots.get("dose") or 1),
        "units_remaining": int(slots["units"])}))


TIME_CHIPS = [chip("8 AM", "8 am"), chip("8 AM and 8 PM", "8 am and 8 pm"), chip("At bedtime", "at night"),
              chip("3 times a day", "three times a day")]


def my_reminders(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "show":
        return call(ctx, "my_reminders", slots, "show", ("my_plans", {}), ("list_reminders", {}))
    plans = [p for p in (ctx.results.get("my_plans") or []) if p.get("active")]
    others = ctx.results.get("list_reminders") or []
    if not plans and not others:
        return say(ctx, "You don't have any medicine reminders yet. Want me to set one up?",
                   [chip("Set a dose reminder", "set medicine reminder")])
    lines = []
    for p in plans:
        low = " ⚠ **running low**" if p.get("running_low") else ""
        lines.append(f"• **{p['medicine']}**: {p['dose']} at {join_words([pretty_time(t) for t in p['times']])} · "
                     f"{p['days_left']} day(s) left{low}")
    for r in others[:5]:
        lines.append(f"• {r['text']}: {pretty_when(datetime.fromisoformat(r['due_at']), ctx.now)}")
    chips = [chip(f"Refill {p['medicine']}", payload={"intent": "refill", "plan_id": p["id"]})
             for p in plans if p.get("running_low")][:2]
    return say(ctx, "Your reminders:\n" + "\n".join(lines),
               chips + [chip("Add a reminder", "set medicine reminder"), chip("Open My medicines", href="/plans")])


STATUS_LABEL = {"taken": "✓ taken", "skipped": "skipped", "pending": "waiting for you", "due": "due now",
                "upcoming": "upcoming"}


def todays_doses(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "show":
        return call(ctx, "todays_doses", slots, "show", ("todays_doses", {}))
    doses = ctx.results.get("todays_doses") or []
    if not doses:
        return say(ctx, "No doses are scheduled for today.", [chip("Set a dose reminder", "set medicine reminder")])
    lines = [f"• {pretty_time(d['time'])}: **{d['medicine']}** ({d['dose']}), {STATUS_LABEL.get(d['status'], d['status'])}"
             for d in doses]
    waiting = [d for d in doses if d["status"] in ("due", "pending")]
    chips = [chip(f"Took {d['medicine']} ({pretty_time(d['time'])})",
                  payload={"intent": "mark_dose", "plan_id": d["plan_id"], "time": d["time"], "status": "TAKEN"})
             for d in waiting[:3]]
    upcoming = [d for d in doses if d["status"] == "upcoming"]
    tail = f"\n\nNext: **{upcoming[0]['medicine']}** at {pretty_time(upcoming[0]['time'])}." if upcoming else ""
    return say(ctx, "Today's doses:\n" + "\n".join(lines) + tail, chips)


def mark_dose(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "mark_dose"
    status = slots.get("status") or "TAKEN"
    if step == "done":
        r = ctx.results.get("mark_dose") or {}
        if r.get("error"):
            return say(ctx, r["error"])
        verb = "taken" if status == "TAKEN" else "skipped"
        left = f" {r['units_left']} unit(s) left at home." if r.get("units_left") is not None else ""
        return say(ctx, f"Marked **{r.get('medicine')}** ({pretty_time(r.get('time', slots.get('time', '00:00')))}) "
                        f"as {verb}.{left}", [chip("Today's doses", "today's doses")])
    if slots.get("plan_id") and slots.get("time"):
        return call(ctx, flow, slots, "done", ("mark_dose", {"plan_id": slots["plan_id"], "time": slots["time"],
                                                             "status": status}))
    if step != "pick":
        return call(ctx, flow, slots, "pick", ("todays_doses", {}))
    waiting = [d for d in (ctx.results.get("todays_doses") or []) if d["status"] in ("due", "pending")]
    q = normalize(slots.get("query") or "")
    if q:
        named = [d for d in waiting if any(w in normalize(d["medicine"]) for w in q.split() if len(w) > 2)]
        waiting = named or waiting
    if not waiting:
        return say(ctx, "There's no dose waiting for an answer right now. Doses show up here once their time comes.",
                   [chip("Today's doses", "today's doses")])
    if len(waiting) == 1:
        d = waiting[0]
        slots.update(plan_id=d["plan_id"], time=d["time"])
        return call(ctx, flow, slots, "done", ("mark_dose", {"plan_id": d["plan_id"], "time": d["time"], "status": status}))
    verb = "Took" if status == "TAKEN" else "Skip"
    return say(ctx, "Which dose?", [chip(f"{verb} {d['medicine']} ({pretty_time(d['time'])})",
                                         payload={"intent": flow, "plan_id": d["plan_id"], "time": d["time"],
                                                  "status": status}) for d in waiting[:4]])


def refill(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "refill"
    if step == "done":
        r = ctx.results.get("refill_plan") or {}
        if r.get("error"):
            return say(ctx, r["error"])
        return say(ctx, f"Added **{r.get('added')}** to your cart for the refill.",
                   [chip("Go to checkout", "checkout"), chip("View cart", "show my cart")])
    if slots.get("plan_id"):
        return call(ctx, flow, slots, "done", ("refill_plan", {"plan_id": slots["plan_id"]}))
    if step != "pick":
        return call(ctx, flow, slots, "pick", ("my_plans", {}))
    plans = [p for p in (ctx.results.get("my_plans") or []) if p.get("active")]
    if not plans:
        return say(ctx, "You don't have any medicine plans to refill. Want to search for a medicine instead?",
                   [chip("Find a medicine", "I'm looking for a medicine")])
    q = normalize(slots.get("query") or "")
    if q:
        candidates = [p for p in plans if any(w in normalize(p["medicine"]) for w in q.split() if len(w) > 2)]
    else:
        candidates = [p for p in plans if p.get("running_low")] or (plans if len(plans) == 1 else [])
    if len(candidates) == 1:
        slots["plan_id"] = candidates[0]["id"]
        return call(ctx, flow, slots, "done", ("refill_plan", {"plan_id": candidates[0]["id"]}))
    options = candidates or plans
    return say(ctx, "Which medicine should I refill?",
               [chip(f"{p['medicine']} ({p['days_left']}d left)", payload={"intent": flow, "plan_id": p["id"]})
                for p in options[:4]])


def upload_prescription(ctx: Ctx, slots, step, answer) -> Reply:
    return say(ctx, "Opening your prescriptions page. Upload a clear photo or PDF of your prescription there; a "
                    "MediZon pharmacist will verify it, usually within a few hours.", navigate="/prescriptions")


_FEEDBACK_TRIGGER = re.compile(
    r"^(hi|hello|hey)?[ ,]*(i\s*(want|would like|'d like|wanna)\s+to\s+)?(give|share|leave|send|write|make|have|report|"
    r"raise|file|submit)?\s*(a|an|some|my)?\s*(feedback|complaint|complain|suggestion|review|problem|issue)s?"
    r"\s*(about|on|for|regarding)?\s*[:,.\-]?\s*", re.I)


def feedback(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "feedback"
    if step == "done":
        r = ctx.results.get("submit_feedback") or {}
        if r.get("error"):
            return say(ctx, r["error"])
        sorry = ""
        score = insights._text_sentiment(slots.get("text", ""))
        if score < 0 or (slots.get("rating") and int(slots["rating"]) <= 2):
            sorry = "I'm sorry about that. "
        return say(ctx, f"{sorry}Thank you, I've passed your {slots.get('kind', 'feedback')} to the MediZon team. "
                        "They read every message.")
    if answer is not None:
        slots["text"] = answer.strip()
    if not slots.get("text"):
        return ask(ctx, flow, slots, "text", "I'm listening. What would you like to tell the MediZon team?")
    rating = re.search(r"\b([1-5])\s*(?:stars?|/\s*5)\b", normalize(slots["text"]))
    if rating:
        slots["rating"] = int(rating.group(1))
    category = slots.get("category") or insights.feedback_category(slots["text"])
    return call(ctx, flow, slots, "done", ("submit_feedback", {"message": slots["text"][:1000], "category": category,
                                                                "rating": slots.get("rating")}))


CUSTOMER_PAGES = [
    (r"profile|address|phone|account", "/profile", "your profile"),
    (r"notification", "/notifications", "your notifications"),
    (r"prescription", "/prescriptions", "your prescriptions"),
    (r"my medicines|plans|reminders|schedule", "/plans", "My medicines"),
    (r"cart|basket", "/cart", "your cart"),
    (r"orders?", "/orders", "your orders"),
    (r"shop|store|catalog|browse", "/medicines", "the shop"),
    (r"home", "/", "the home page"),
]
ADMIN_PAGES = [
    (r"prescription", "/admin/prescriptions", "prescriptions"),
    (r"new medicine|add (a )?(new )?medicine", "/admin/medicines/new", "a new medicine form"),
    (r"catalog|medicines|inventory|stock", "/admin/medicines", "the catalog"),
    (r"users?|customers?|admins?", "/admin/users", "users"),
    (r"reviews?|feedback|ratings?", "/admin/reviews", "reviews"),
    (r"orders?", "/admin/orders", "customer orders"),
    (r"dashboard|overview|home", "/admin", "the dashboard"),
]


def navigate(ctx: Ctx, slots, step, answer) -> Reply:
    t = normalize(ctx.message)
    for pattern, url, label in (ADMIN_PAGES if ctx.admin else CUSTOMER_PAGES):
        if re.search(pattern, t):
            return say(ctx, f"Opening {label}.", navigate=url)
    return help_(ctx, slots, step, answer)


def pharmacist(ctx: Ctx, slots, step, answer) -> Reply:
    return say(ctx, "For advice about your health, please talk to your doctor or a pharmacist. I can pass a question "
                    "to the MediZon pharmacist team and they'll get back to you.",
               [chip("Send a question to the pharmacist",
                     payload={"intent": "feedback", "category": "Pharmacist question", "kind": "question"})])


# ====================================================================== admin intents

ADMIN_CHIPS = [chip("What needs attention?", "what needs my attention"), chip("Analyze reviews", "analyze reviews"),
               chip("Low stock", "low stock"), chip("Set a reminder", "set a reminder")]


def review_insights(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "show":
        return call(ctx, "review_insights", slots, "show", ("review_data", {}))
    data = ctx.results.get("review_data") or {}
    a = insights.analyze(data.get("reviews") or [], data.get("feedback") or [], ctx.now.date())
    if a.get("empty"):
        return say(ctx, "There are no customer reviews or feedback yet. Reviews appear after orders are delivered, "
                        "and customers can send feedback through me.", [chip("Open reviews", href="/admin/reviews")])

    head = f"**Customer feedback summary** ({a['reviews']} review(s), {a['feedback']} feedback message(s))"
    lines = [head]
    if a["average"] is not None:
        trend = ""
        if a["trend"]:
            trend = f", {'up' if a['trend'] > 0 else 'down'} {abs(a['trend']):.1f} on the previous 30 days"
        lines.append(f"Average rating: **{a['average']:.1f} ★**{trend}. "
                     f"{a['positive']} positive, {a['negative']} negative.")
    if a["suggestions"]:
        lines.append("\n**What to improve**")
        for n, s in enumerate(a["suggestions"], 1):
            ev = f" ({s['evidence']})" if s["evidence"] else ""
            lines.append(f"{n}. **{s['topic']}**{ev}: {s['text']}")
            for q in s["quotes"][:1]:
                lines.append(f"   “{q}”")
    else:
        lines.append("\nNo recurring complaints. Customers are broadly happy.")
    if a["strengths"]:
        lines.append("\n**What customers like**")
        lines.extend(f"• **{s['topic']}** ({s['mentions']} mention(s)): {s['text']}" for s in a["strengths"])
    if a["frequent_complaint_words"]:
        lines.append("\nWords that come up in complaints: " + ", ".join(a["frequent_complaint_words"]) + ".")
    return say(ctx, "\n".join(lines), [chip("Open reviews", href="/admin/reviews"),
                                       chip("What needs attention?", "what needs my attention"),
                                       chip("Remind me to follow up", payload={"intent": "set_general_reminder",
                                                                               "text": "follow up on review suggestions"})])


def store_tasks(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "show":
        return call(ctx, "store_tasks", slots, "show", ("store_tasks", {}), ("list_reminders", {}))
    s = ctx.results.get("store_tasks") or {}
    reminders = [r for r in (ctx.results.get("list_reminders") or [])
                 if datetime.fromisoformat(r["due_at"]).date() <= ctx.now.date()]
    items, chips = [], []
    if s.get("orders_to_confirm"):
        items.append(f"• **{s['orders_to_confirm']}** order(s) to confirm")
        chips.append(chip("Confirm orders", href="/admin/orders?status=PLACED"))
    if s.get("orders_to_ship"):
        items.append(f"• **{s['orders_to_ship']}** confirmed order(s) to ship")
        chips.append(chip("Ship orders", href="/admin/orders?status=CONFIRMED"))
    if s.get("orders_in_transit"):
        items.append(f"• {s['orders_in_transit']} order(s) out for delivery; mark them delivered when they arrive")
    if s.get("prescriptions_pending"):
        items.append(f"• **{s['prescriptions_pending']}** prescription(s) to review")
        chips.append(chip("Review prescriptions", href="/admin/prescriptions"))
    low = s.get("low_stock") or []
    if low:
        names = join_words([f"{m['name']} ({m['stock']})" for m in low[:3]])
        more = f" and {len(low) - 3} more" if len(low) > 3 else ""
        items.append(f"• **{len(low)}** medicine(s) low on stock: {names}{more}")
        chips.append(chip("Low stock", "low stock"))
    if s.get("low_reviews_7d"):
        items.append(f"• {s['low_reviews_7d']} review(s) of 1-2 stars this week")
        chips.append(chip("Analyze reviews", "analyze reviews"))
    if s.get("feedback_7d"):
        items.append(f"• {s['feedback_7d']} new feedback message(s) this week")
        chips.append(chip("Read feedback", href="/admin/reviews#feedback"))
    for r in reminders[:3]:
        items.append(f"• Reminder: **{r['text']}**" + (" (overdue)" if r.get("overdue") else ""))
    if not items:
        return say(ctx, "All clear! No orders, prescriptions or stock alerts need you right now.",
                   [chip("Analyze reviews", "analyze reviews"), chip("Set a reminder", "set a reminder")])
    stats = f"\n\nToday: {s.get('orders_today', 0)} order(s), {money(s.get('revenue_today', 0))} in sales."
    return say(ctx, "Here's what needs your attention:\n" + "\n".join(items) + stats, chips[:4])


def low_stock(ctx: Ctx, slots, step, answer) -> Reply:
    if step != "show":
        return call(ctx, "low_stock", slots, "show", ("store_tasks", {}))
    low = (ctx.results.get("store_tasks") or {}).get("low_stock") or []
    if not low:
        return say(ctx, "Nothing is low on stock right now.")
    lines = [f"• **{m['name']}**: {m['stock']} pack(s) left" for m in low[:12]]
    return say(ctx, f"{len(low)} medicine(s) are below the low-stock line:\n" + "\n".join(lines),
               [chip("Open catalog", href="/admin/medicines"),
                chip("Remind me to restock", payload={"intent": "set_general_reminder", "text": "restock low-stock medicines"})])


def stock_check(ctx: Ctx, slots, step, answer) -> Reply:
    flow = "stock_check"
    if answer is not None:
        slots["query"] = medicine_query(answer) or answer.strip()
    if step == "show":
        items = ctx.results.get("search_medicines") or []
        if not items:
            return say(ctx, f"Nothing in the catalog matches **{slots.get('query')}**.",
                       [chip("Add a medicine", href="/admin/medicines/new")])
        lines = [f"• **{m['name']}**: {m.get('stock', 0)} pack(s), {money(m.get('price'))}"
                 + ("" if m.get("active", True) else " (hidden from shop)") for m in items[:8]]
        return say(ctx, "Stock levels:\n" + "\n".join(lines), cards=[medicine_card(m, admin=True) for m in items[:4]])
    if not slots.get("query"):
        return ask(ctx, flow, slots, "query", "Which medicine should I check?")
    return call(ctx, flow, slots, "show", ("search_medicines", {"query": slots["query"], "category": "",
                                                                "include_hidden": True}))


# ====================================================================== routing

HANDLERS: dict[str, Callable[..., Reply]] = {
    "greet": greet, "help": help_, "thanks": thanks, "bye": bye, "cancel": cancel, "emergency": emergency,
    "set_general_reminder": general_reminder, "list_general_reminders": list_general_reminders,
    "complete_reminder": complete_reminder, "navigate": navigate,
    # customer
    "search_medicine": search, "medicine_info": medicine_info, "add_to_cart": add_to_cart, "view_cart": view_cart,
    "checkout": checkout, "track_order": track_order, "set_reminder": set_reminder, "my_reminders": my_reminders,
    "todays_doses": todays_doses, "mark_dose": mark_dose, "refill": refill,
    "upload_prescription": upload_prescription, "feedback": feedback, "pharmacist": pharmacist,
    # admin
    "review_insights": review_insights, "store_tasks": store_tasks, "low_stock": low_stock, "stock_check": stock_check,
}
CUSTOMER_ONLY = {"search_medicine", "medicine_info", "add_to_cart", "view_cart", "checkout", "track_order",
                 "set_reminder", "my_reminders", "todays_doses", "mark_dose", "refill", "upload_prescription",
                 "feedback", "pharmacist"}
ADMIN_ONLY = {"review_insights", "store_tasks", "low_stock", "stock_check"}


def extract(intent: str, ctx: Ctx) -> dict:
    """Details found in the first message of a request."""
    msg = ctx.message
    t = normalize(msg)
    slots: dict[str, Any] = {}
    if intent in ("search_medicine", "medicine_info", "add_to_cart", "set_reminder", "refill", "mark_dose", "stock_check"):
        q = medicine_query(msg)
        if intent == "search_medicine":
            cat = symptom_category(msg, ctx.categories)
            if cat:
                slots["category"], slots["symptom"] = cat, True
        if q:
            slots["query"] = q
    if intent == "add_to_cart":
        if parse_quantity(msg):
            slots["quantity"] = parse_quantity(msg)
        last = ctx.state.get("last") or []
        idx = parse_ordinal(msg)
        if idx is not None and last and -len(last) <= idx < len(last):
            slots.update(medicine_id=last[idx]["id"], medicine_name=last[idx]["name"])
            slots.pop("query", None)
        elif re.search(r"\b(it|this|that|this one|that one)\b", t) and not slots.get("query"):
            lm = ctx.state.get("last_medicine") or (last[0] if len(last) == 1 else None)
            if lm and lm.get("id"):
                slots.update(medicine_id=lm["id"], medicine_name=lm.get("name"))
    if intent == "set_reminder":
        times = parse_times(msg)
        if times:
            slots["times"] = times
        dose = re.search(r"\b(\d)\s*(tablets?|tabs?|capsules?|caps?|pills?|sachets?|spoons?)\b", t)
        if dose:
            slots["dose"] = int(dose.group(1))
    if intent == "mark_dose":
        slots["status"] = "SKIPPED" if re.search(r"\bskip|missed|miss\b|didn'?t take|not take", t) else "TAKEN"
    if intent == "set_general_reminder":
        text = _reminder_text(msg)
        if text:
            slots["text"] = text
        when = parse_when(msg, ctx.now)
        if when:
            slots["due_at"] = when.isoformat(timespec="minutes")
    if intent == "feedback":
        rest = _FEEDBACK_TRIGGER.sub("", msg.strip()).strip()
        if len(rest.split()) >= 3:
            slots["text"] = msg.strip()
        if re.search(r"complain|problem|issue|bad|late|damaged|wrong|rude|slow", t):
            slots["kind"] = "complaint"
        elif re.search(r"suggest", t):
            slots["kind"] = "suggestion"
    return slots


def _allowed(intent: str, role: str) -> bool:
    if role == "admin":
        return intent not in CUSTOMER_ONLY
    return intent not in ADMIN_ONLY


def _fallback(ctx: Ctx) -> Reply:
    q = medicine_query(ctx.message)
    if q and len(ctx.message.split()) <= 4:
        intent = "stock_check" if ctx.admin else "search_medicine"
        slots = {"query": q}
        if not ctx.admin:
            cat = symptom_category(ctx.message, ctx.categories)
            if cat:
                slots.update(category=cat, symptom=True)
        r = HANDLERS[intent](ctx, slots, None, None)
        r.intent = intent
        return r
    return say(ctx, "Sorry, I didn't quite get that. Here are some things I can do:",
               ADMIN_CHIPS if ctx.admin else CUSTOMER_CHIPS)


def respond(req: dict) -> dict:
    role = "admin" if req.get("role") == "admin" else "customer"
    now = datetime.fromisoformat(req["now"]) if req.get("now") else datetime.now()
    ctx = Ctx(role=role, first_name=(req.get("user") or {}).get("first_name") or "",
              message=(req.get("message") or "").strip()[:1000], payload=req.get("payload") or {},
              state=req.get("state") or {}, results=req.get("tool_results") or {}, now=now.replace(second=0, microsecond=0),
              categories=req.get("categories") or [])
    return _respond(ctx).as_dict()


def _respond(ctx: Ctx) -> Reply:
    state = ctx.state
    flow, slots = state.get("flow"), dict(state.get("slots") or {})

    # 1. Java is returning tool results for a step we asked for.
    if ctx.results and flow and state.get("step") and flow in HANDLERS:
        r = HANDLERS[flow](ctx, slots, state["step"], None)
        r.intent = flow
        return r

    # 2. A button was pressed.
    if ctx.payload.get("intent") in HANDLERS:
        intent = ctx.payload["intent"]
        if not _allowed(intent, ctx.role):
            return _fallback(ctx)
        base = slots if intent == flow else {}
        base.update({k: v for k, v in ctx.payload.items() if k != "intent"})
        ctx.state = {k: v for k, v in state.items() if k in ("last", "last_medicine")}
        r = HANDLERS[intent](ctx, base, None, None)
        r.intent = intent
        return r

    if not ctx.message:
        return greet(ctx, {}, None, None)

    prediction = model(ctx.role).predict(ctx.message)
    intent = prediction.name if _allowed(prediction.name, ctx.role) else None

    # 3. We asked a question; treat the message as the answer unless it's clearly something else.
    if flow and state.get("awaiting") and flow in HANDLERS:
        if intent in ("cancel", "emergency") and prediction.confidence >= 0.99:
            r = HANDLERS[intent](ctx, {}, None, None)
            r.intent = intent
            return r
        switching = (intent and intent != flow and prediction.confidence >= SWITCH_CONFIDENCE
                     and intent not in ("thanks", "greet") and not _looks_like_answer(ctx, state["awaiting"]))
        if not switching:
            if is_no(ctx.message) and state["awaiting"] not in ("units",):
                return cancel(ctx, {}, None, None)
            r = HANDLERS[flow](ctx, slots, None, ctx.message)
            r.intent = flow
            return r

    # 4. A fresh request.
    ctx.state = {k: v for k, v in state.items() if k in ("last", "last_medicine")}
    if not intent or prediction.confidence < 0.30 or (prediction.confidence < 0.6 and _bare_name(ctx.message)):
        return _fallback(ctx)
    r = HANDLERS[intent](ctx, extract(intent, ctx), None, None)
    r.intent = intent
    return r


def _bare_name(message: str) -> bool:
    """A short message that is only a product name, like 'azithromycin' or 'crocin advance'."""
    q = medicine_query(message)
    return bool(q) and len(message.split()) <= 3 and len(q.split()) == len(normalize(message).split())


def _looks_like_answer(ctx: Ctx, awaiting: str) -> bool:
    msg = ctx.message
    if awaiting == "times":
        return bool(parse_times(msg))
    if awaiting == "when":
        return parse_when(msg, ctx.now) is not None
    if awaiting == "units":
        return parse_int(msg) is not None or is_skip(msg)
    if awaiting == "pick":
        return parse_ordinal(msg) is not None
    if awaiting in ("text",):
        return True                      # free text: anything goes
    if awaiting == "query":
        return bool(medicine_query(msg)) and len(msg.split()) <= 4
    return is_yes(msg) or is_no(msg)
