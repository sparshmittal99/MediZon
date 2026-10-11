"""Run with: python -m unittest discover -s tests"""
import unittest
from datetime import datetime

from assistant import dialog, insights
from assistant.nlu import model
from assistant.text import medicine_query, parse_quantity, parse_times, parse_when, symptom_category

NOW = datetime(2026, 10, 11, 12, 0)
CATEGORIES = ["Pain & fever", "Cold & cough", "Allergy", "Diabetes", "Stomach care"]
CATALOG = [
    {"id": 1, "name": "Paracetamol 650 mg", "salt": "Paracetamol", "price": 33.5, "pack": "15 tablets", "in_stock": True,
     "rx": False, "url": "/medicines/1", "category": "Pain & fever", "stock": 400},
    {"id": 2, "name": "Ibuprofen 400 mg", "salt": "Ibuprofen", "price": 28, "pack": "15 tablets", "in_stock": True,
     "rx": False, "url": "/medicines/2", "category": "Pain & fever", "stock": 20},
    {"id": 3, "name": "Metformin 500 mg", "salt": "Metformin", "price": 42, "pack": "20 tablets", "in_stock": True,
     "rx": True, "url": "/medicines/3", "category": "Diabetes", "stock": 300},
    {"id": 4, "name": "Cetirizine 10 mg", "salt": "Cetirizine", "price": 18, "pack": "10 tablets", "in_stock": True,
     "rx": False, "url": "/medicines/4", "category": "Allergy", "stock": 500},
]


class FakeStore:
    """Plays the Java side: runs tool calls against a tiny in-memory store."""

    def __init__(self):
        self.cart, self.plans, self.reminders, self.feedback, self.calls = [], [], [], [], []

    def run(self, name, args):
        self.calls.append(name)
        if name == "search_medicines":
            q, cat = args.get("query", "").lower(), args.get("category", "")
            hits = [m for m in CATALOG if (not q or q in m["name"].lower()) and (not cat or m["category"] == cat)]
            if not hits and q:
                for word in sorted(q.split(), key=len, reverse=True):
                    hits = [m for m in CATALOG if word in m["name"].lower()]
                    if hits:
                        break
            if not hits and cat:
                hits = [m for m in CATALOG if m["category"] == cat]
            return hits
        if name == "medicine_details":
            hits = [m for m in CATALOG if m["id"] == args.get("id") or
                    (args.get("query") and args["query"].split()[0] in m["name"].lower())]
            return dict(hits[0], description="Used for pain.") if hits else {"error": "not found"}
        if name == "add_to_cart":
            m = next(m for m in CATALOG if m["id"] == args["medicine_id"])
            self.cart.append((m["name"], args["quantity"]))
            return {"ok": True, "added": f'{args["quantity"]} × {m["name"]}', "rx": m["rx"], "cart_count": len(self.cart)}
        if name == "cart_summary":
            items = [{"name": n, "quantity": q, "line_total": 10 * q} for n, q in self.cart]
            return {"items": items, "total": sum(i["line_total"] for i in items), "delivery": 0, "needs_prescription": False}
        if name == "create_plan":
            m = next(m for m in CATALOG if m["id"] == args["medicine_id"])
            self.plans.append(args)
            return {"ok": True, "medicine": m["name"], "times": args["times"], "dose": "1 tablet", "days_left": 7}
        if name == "create_reminder":
            self.reminders.append(args)
            return {"ok": True, "id": len(self.reminders)}
        if name == "list_reminders":
            return [{"id": i + 1, "text": r["text"], "due_at": r["due_at"]} for i, r in enumerate(self.reminders)]
        if name == "submit_feedback":
            self.feedback.append(args)
            return {"ok": True}
        if name == "my_plans":
            return [{"id": 9, "medicine": "Metformin 500 mg", "medicine_id": 3, "active": True, "running_low": True,
                     "dose": "1 tablet", "times": ["08:30", "20:30"], "days_left": 3}]
        if name == "refill_plan":
            return {"ok": True, "added": "1 pack of Metformin 500 mg"}
        if name == "todays_doses":
            return [{"plan_id": 9, "time": "08:30", "medicine": "Metformin 500 mg", "dose": "1 tablet", "status": "due"}]
        if name == "mark_dose":
            return {"ok": True, "medicine": "Metformin 500 mg", "time": args["time"], "units_left": 7}
        if name == "store_tasks":
            return {"orders_to_confirm": 2, "orders_to_ship": 1, "prescriptions_pending": 1,
                    "low_stock": [{"name": "Ibuprofen 400 mg", "stock": 20}], "orders_today": 3, "revenue_today": 450}
        if name == "review_data":
            return {"reviews": [
                {"rating": 1, "comment": "Delivery was very late, took 6 days", "date": "2026-10-01"},
                {"rating": 2, "comment": "Late delivery and the box was damaged", "date": "2026-10-05"},
                {"rating": 5, "comment": "Great prices and fast checkout", "date": "2026-10-06"},
                {"rating": 2, "comment": None, "date": "2026-10-07"},
            ], "feedback": [{"message": "delivery is too slow", "date": "2026-10-08"}]}
        return {"error": f"unknown tool {name}"}


class Chat:
    def __init__(self, role="customer"):
        self.role, self.state, self.store = role, {}, FakeStore()

    def send(self, message="", payload=None):
        req = {"role": self.role, "user": {"first_name": "Asha"}, "message": message, "payload": payload or {},
               "state": self.state, "categories": CATEGORIES, "now": NOW.isoformat()}
        for _ in range(5):
            out = dialog.respond(req)
            if not out["tool_calls"]:
                self.state = out["state"]
                return out
            results = {c["name"]: self.store.run(c["name"], c["args"]) for c in out["tool_calls"]}
            req = dict(req, state=out["state"], tool_results=results, payload={})
        raise AssertionError("too many tool rounds")


class TextTests(unittest.TestCase):
    def test_times(self):
        self.assertEqual(parse_times("8 am and 9:30 pm"), ["08:00", "21:30"])
        self.assertEqual(parse_times("twice a day"), ["08:00", "20:00"])
        self.assertEqual(parse_times("at night"), ["21:00"])
        self.assertEqual(parse_times("20:15"), ["20:15"])
        self.assertEqual(parse_times("paracetamol 650"), [])

    def test_when(self):
        self.assertEqual(parse_when("tomorrow at 10 am", NOW), datetime(2026, 10, 12, 10, 0))
        self.assertEqual(parse_when("in 2 hours", NOW), datetime(2026, 10, 11, 14, 0))
        self.assertEqual(parse_when("at 9 am", NOW), datetime(2026, 10, 12, 9, 0))   # already past today
        self.assertEqual(parse_when("15 oct 6 pm", NOW), datetime(2026, 10, 15, 18, 0))
        self.assertIsNone(parse_when("call the doctor", NOW))

    def test_quantity_and_query(self):
        self.assertEqual(parse_quantity("add 2 packs of cetirizine"), 2)
        self.assertEqual(parse_quantity("buy three strips"), 3)
        self.assertIsNone(parse_quantity("paracetamol 650 mg"))
        self.assertEqual(medicine_query("do you have paracetamol 650?"), "paracetamol 650")
        self.assertEqual(medicine_query("add 2 packs of cetirizine to my cart"), "cetirizine")

    def test_symptoms(self):
        self.assertEqual(symptom_category("something for a headache", CATEGORIES), "Pain & fever")
        self.assertIsNone(symptom_category("thyroid tablets", CATEGORIES))   # category not in this store


class IntentTests(unittest.TestCase):
    def check(self, role, cases):
        m = model(role)
        for text, expected in cases:
            with self.subTest(text=text):
                self.assertEqual(m.predict(text).name, expected)

    def test_customer(self):
        self.check("customer", [
            ("hello", "greet"), ("do you have cetirizine", "search_medicine"),
            ("add 2 packs of paracetamol to my cart", "add_to_cart"), ("take me to checkout", "checkout"),
            ("where is my order", "track_order"), ("remind me to take metformin at 8 am", "set_reminder"),
            ("what should i take today", "todays_doses"), ("refill my metformin", "refill"),
            ("i have chest pain", "emergency"), ("remind me to call the doctor tomorrow", "set_general_reminder"),
            ("the delivery was late", "feedback"), ("show my cart", "view_cart"),
        ])

    def test_admin(self):
        self.check("admin", [
            ("analyze reviews", "review_insights"), ("what needs my attention", "store_tasks"),
            ("low stock", "low_stock"), ("remind me to call the supplier at 4 pm", "set_general_reminder"),
            ("how much paracetamol stock", "stock_check"), ("open prescriptions", "navigate"),
        ])


class CustomerFlowTests(unittest.TestCase):
    def test_search_then_add_first(self):
        c = Chat()
        out = c.send("something for fever")
        self.assertGreaterEqual(len(out["cards"]), 2)
        self.assertIn("doctor", out["reply"])
        out = c.send("add the first one")
        self.assertIn("Added", out["reply"])
        self.assertEqual(c.store.cart, [("Paracetamol 650 mg", 1)])

    def test_add_by_name_with_quantity(self):
        c = Chat()
        out = c.send("add 2 packs of cetirizine")
        self.assertIn("2 × Cetirizine", out["reply"])

    def test_card_button_payload(self):
        c = Chat()
        out = c.send(payload={"intent": "add_to_cart", "medicine_id": 3, "medicine_name": "Metformin 500 mg", "quantity": 1})
        self.assertIn("prescription", out["reply"])

    def test_checkout_navigates(self):
        c = Chat()
        c.send("add paracetamol")
        out = c.send("checkout")
        self.assertEqual(out["navigate"], "/checkout")

    def test_checkout_empty_cart(self):
        out = Chat().send("checkout")
        self.assertIsNone(out["navigate"])
        self.assertIn("empty", out["reply"])

    def test_dose_reminder_slot_filling(self):
        c = Chat()
        out = c.send("set a medicine reminder")
        self.assertIn("Which medicine", out["reply"])
        out = c.send("ibuprofen")
        self.assertIn("What time", out["reply"])
        out = c.send("8 am and 8 pm")
        self.assertIn("How many", out["reply"])
        out = c.send("30")
        self.assertIn("Done", out["reply"])
        self.assertEqual(c.store.plans[0], {"medicine_id": 2, "times": ["08:00", "20:00"], "dose": 1, "units_remaining": 30})

    def test_dose_reminder_already_exists(self):
        c = Chat()
        out = c.send("remind me to take metformin at 9 pm")
        self.assertIn("already", out["reply"])
        self.assertEqual(c.store.plans, [])

    def test_reminder_for_medicine_is_a_dose_reminder(self):
        c = Chat()
        out = c.send("set a reminder for my cetirizine")
        self.assertIn("What time", out["reply"])

    def test_unknown_medicine_offers_general_reminder(self):
        c = Chat()
        c.send("set medicine reminder")
        out = c.send("zzqx")
        self.assertIn("couldn't find", out["reply"])
        self.assertTrue(any(s.get("payload", {}).get("intent") == "set_general_reminder" for s in out["suggestions"]))

    def test_dose_reminder_one_shot(self):
        c = Chat()
        out = c.send("remind me to take cetirizine at night")
        self.assertIn("How many", out["reply"])
        out = c.send("skip")
        self.assertEqual(c.store.plans[0]["units_remaining"], -1)
        self.assertEqual(c.store.plans[0]["times"], ["21:00"])

    def test_switching_request_mid_flow(self):
        c = Chat()
        c.send("set a medicine reminder")
        out = c.send("show my cart")
        self.assertIn("cart", out["reply"].lower())
        self.assertEqual(c.store.plans, [])

    def test_cancel_mid_flow(self):
        c = Chat()
        c.send("set a medicine reminder")
        out = c.send("cancel")
        self.assertIn("cancelled", out["reply"])
        self.assertEqual(c.state.get("flow"), None)

    def test_general_reminder(self):
        c = Chat()
        out = c.send("remind me to call the doctor tomorrow at 5 pm")
        self.assertIn("call the doctor", out["reply"])
        self.assertEqual(c.store.reminders[0], {"text": "call the doctor", "due_at": "2026-10-12T17:00"})

    def test_general_reminder_asks_for_time(self):
        c = Chat()
        out = c.send("remind me to book a blood test")
        self.assertIn("When", out["reply"])
        c.send("friday 9 am")
        self.assertEqual(c.store.reminders[0]["due_at"], "2026-10-16T09:00")

    def test_feedback(self):
        c = Chat()
        out = c.send("I want to give feedback")
        self.assertIn("listening", out["reply"])
        out = c.send("the delivery was 3 days late")
        self.assertIn("Thank you", out["reply"])
        self.assertEqual(c.store.feedback[0]["category"], "Delivery speed")

    def test_refill_and_mark_dose(self):
        c = Chat()
        self.assertIn("Metformin", c.send("refill my medicines")["reply"])
        out = c.send("i took my metformin")
        self.assertIn("as taken", out["reply"])

    def test_emergency(self):
        out = Chat().send("my father has chest pain and can't breathe")
        self.assertIn("112", out["reply"])

    def test_admin_only_intent_blocked_for_customer(self):
        out = Chat().send(payload={"intent": "store_tasks"})
        self.assertNotIn("attention", out["reply"])


class AdminFlowTests(unittest.TestCase):
    def test_insights(self):
        out = Chat("admin").send("analyze reviews")
        self.assertIn("Delivery speed", out["reply"])
        self.assertIn("What to improve", out["reply"])

    def test_store_tasks(self):
        out = Chat("admin").send("what needs my attention")
        self.assertIn("2", out["reply"])
        self.assertIn("prescription", out["reply"])

    def test_customer_intent_blocked_for_admin(self):
        c = Chat("admin")
        c.send(payload={"intent": "add_to_cart", "medicine_id": 1})
        self.assertEqual(c.store.cart, [])


class InsightTests(unittest.TestCase):
    def test_ranking(self):
        a = insights.analyze(FakeStore().run("review_data", {})["reviews"], [{"message": "delivery too slow"}],
                             NOW.date())
        self.assertEqual(a["suggestions"][0]["topic"], "Delivery speed")
        self.assertTrue(any(s["topic"] == "Silent low ratings" for s in a["suggestions"]))

    def test_empty(self):
        self.assertTrue(insights.analyze([], [])["empty"])


if __name__ == "__main__":
    unittest.main()
