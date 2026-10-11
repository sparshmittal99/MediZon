"""Reads customer reviews and feedback and turns them into findings and improvement suggestions.

Each text gets a sentiment (star rating when there is one, otherwise a word lexicon with negation handling)
and the store topics it mentions (delivery, packaging, price ...). Topics that keep coming up in negative
texts become ranked suggestions; topics praised often are reported as strengths.
"""
from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from .text import normalize

POSITIVE = set("""
good great excellent amazing awesome fast quick quickly prompt timely helpful friendly polite easy smooth
love loved like liked best nice perfect fine happy satisfied genuine reliable recommend recommended
affordable cheap reasonable convenient efficient wonderful fantastic superb useful accurate safe neat
""".split())
NEGATIVE = set("""
bad poor worst terrible horrible awful slow late delay delayed delays never damaged broken leaking leaked
torn wrong missing expired expensive costly overpriced rude unhelpful useless difficult confusing confused
problem problems issue issues complaint complain disappointed disappointing unhappy fake cancelled
canceled refund rejected stuck error failed failing crash crashes crashed bug buggy waiting waited hate
dirty unprofessional incorrect lost nobody worse annoying
""".split())
NEGATORS = {"not", "no", "never", "didn't", "didnt", "wasn't", "wasnt", "isn't", "isnt", "don't", "dont",
            "hardly", "barely", "without", "nothing", "nobody"}

# topic -> (label, keyword regex, suggestion when customers are unhappy, note when they are happy)
TOPICS = {
    "delivery": (
        "Delivery speed",
        r"deliver|late\b|delay|slow|courier|shipping|shipped|dispatch|arriv|on time|days to|took \d+ days|waiting",
        "Speed up delivery: confirm and dispatch new orders the same day, show an expected delivery date at "
        "checkout, and send a notification when an order ships.",
        "Customers are happy with delivery speed; mention fast delivery on the home page."),
    "packaging": (
        "Packaging",
        r"packag|damaged|broken|leak|seal|torn|crushed|box\b|wrapp",
        "Improve packaging: use sealed, padded packs for syrups and glass bottles, and add a quick packing "
        "check before dispatch.",
        "Packaging gets positive mentions."),
    "accuracy": (
        "Order accuracy",
        r"wrong|missing|incorrect|different (medicine|item|brand)|not what i ordered|short ?shipped|less quantity",
        "Reduce wrong or missing items: have a second person check each order against the invoice before "
        "it ships, and make replacements easy to request.",
        "Customers trust that orders arrive complete and correct."),
    "quality": (
        "Product quality",
        r"expir|quality|genuine|fake|original|authentic|counterfeit|near expiry",
        "Show expiry dates on product pages and invoices, and stop dispatching stock that is close to expiry.",
        "Customers trust the products are genuine and fresh."),
    "price": (
        "Pricing",
        r"price|expensive|costly|cheap|discount|offer|overpriced|delivery (fee|charge)|charges|mrp|afford",
        "Review pricing: compare prices for the most-viewed medicines, lower the free-delivery threshold, or "
        "run offers on monthly refills.",
        "Pricing is seen as fair; highlight discounts against MRP."),
    "availability": (
        "Stock availability",
        r"out of stock|not available|unavailable|no stock|stock\b|availability|couldn'?t find|could not find",
        "Keep popular medicines in stock: restock items below the low-stock line weekly and let customers ask "
        "to be notified when an item is back.",
        "Customers find what they need in stock."),
    "prescription": (
        "Prescription process",
        r"prescription|\brx\b|verif|approv|reject|upload",
        "Speed up prescription checks: review uploads within a few hours and tell customers clearly why one was "
        "rejected and what to upload instead.",
        "The prescription process is working well for customers."),
    "support": (
        "Customer support",
        r"support|customer care|service|response|respond|rude|help(ful|less)?|call(ed)?\b|reply|pharmacist|staff",
        "Improve support: reply to complaints within a day, and follow up personally on every 1 or 2 star review.",
        "Support and pharmacist help get praise."),
    "app": (
        "Website and checkout",
        r"\bapp\b|website|site\b|checkout|payment|upi|card|login|sign ?in|page|loading|bug|crash|error|ui\b|interface",
        "Fix website friction: check the checkout and payment steps on mobile, and look at errors around "
        "sign-in and payment.",
        "Customers find the website easy to use."),
    "reminders": (
        "Reminders and refills",
        r"remind|notification|alert|refill|reorder|auto ?order",
        "Tune reminders and refills: make sure alerts arrive on time and let customers adjust refill timing "
        "from the reminder itself.",
        "Dose reminders and refills are valued; promote them to new customers."),
}

TOKEN = re.compile(r"[a-z']+")


@dataclass
class Item:
    text: str
    rating: int | None
    when: date | None
    source: str                     # review | feedback
    sentiment: float = 0.0
    topics: list[str] = field(default_factory=list)


def _text_sentiment(text: str) -> float:
    words = TOKEN.findall(normalize(text))
    score = 0
    for i, w in enumerate(words):
        polarity = 1 if w in POSITIVE else -1 if w in NEGATIVE else 0
        if polarity and any(p in NEGATORS for p in words[max(0, i - 3):i]):
            polarity = -polarity
        score += polarity
    if not words:
        return 0.0
    return max(-1.0, min(1.0, score / 3))


def _sentiment(item: Item) -> float:
    text_score = _text_sentiment(item.text) if item.text else 0.0
    if item.rating is None:
        return text_score
    rating_score = (item.rating - 3) / 2        # 1 -> -1, 3 -> 0, 5 -> 1
    return 0.7 * rating_score + 0.3 * text_score if item.text else rating_score


def _parse_date(value) -> date | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value)[:19]).date()
    except ValueError:
        return None


def _items(reviews: list[dict], feedback: list[dict]) -> list[Item]:
    out = []
    for r in reviews or []:
        rating = r.get("rating")
        out.append(Item(text=(r.get("comment") or "").strip(), rating=int(rating) if rating else None,
                        when=_parse_date(r.get("date")), source="review"))
    for f in feedback or []:
        rating = f.get("rating")
        out.append(Item(text=(f.get("message") or "").strip(), rating=int(rating) if rating else None,
                        when=_parse_date(f.get("date")), source="feedback"))
    for it in out:
        t = normalize(it.text)
        it.topics = [key for key, (_, pattern, _, _) in TOPICS.items() if t and re.search(pattern, t)]
        it.sentiment = _sentiment(it)
    return out


def _short(text: str, limit: int = 110) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit - 1].rsplit(" ", 1)[0] + "…"


def _frequent_words(texts: list[str], top: int = 6) -> list[str]:
    """Most common meaningful words and word pairs in a set of complaints."""
    stop = POSITIVE | NEGATORS | {"the", "and", "was", "were", "for", "with", "this", "that", "very", "but", "have",
                                  "had", "has", "you", "your", "they", "them", "are", "not", "too", "its", "it's",
                                  "from", "got", "get", "my", "our", "all", "even", "after", "again", "one", "order",
                                  "medizon", "i", "a", "an", "to", "of", "in", "on", "is", "it", "me", "we", "be",
                                  "so", "at", "by", "or", "as", "if", "no", "do", "did", "just", "also", "only",
                                  "time", "really", "much", "will", "would", "could", "can", "when", "than", "then"}
    counts: Counter[str] = Counter()
    for t in texts:
        words = [w for w in TOKEN.findall(normalize(t)) if len(w) > 2 and w not in stop]
        counts.update(set(words))
        counts.update({f"{a} {b}" for a, b in zip(words, words[1:])})
    return [w for w, n in counts.most_common(top * 2) if n >= 2][:top]


def analyze(reviews: list[dict], feedback: list[dict], today: date | None = None) -> dict:
    today = today or date.today()
    items = _items(reviews, feedback)
    rated = [i for i in items if i.rating]
    if not items:
        return {"empty": True}

    avg = round(sum(i.rating for i in rated) / len(rated), 2) if rated else None
    recent = [i.rating for i in rated if i.when and i.when > today - timedelta(days=30)]
    earlier = [i.rating for i in rated if i.when and today - timedelta(days=60) < i.when <= today - timedelta(days=30)]
    trend = None
    if len(recent) >= 3 and len(earlier) >= 3:
        trend = round(sum(recent) / len(recent) - sum(earlier) / len(earlier), 2)

    negative = [i for i in items if i.sentiment <= -0.2]
    positive = [i for i in items if i.sentiment >= 0.3]
    silent_low = [i for i in rated if i.rating <= 2 and not i.text]

    topics = []
    for key, (label, _, fix, praise) in TOPICS.items():
        neg = [i for i in negative if key in i.topics]
        pos = [i for i in positive if key in i.topics]
        if not neg and not pos:
            continue
        recent_neg = sum(1 for i in neg if i.when and i.when > today - timedelta(days=30))
        topics.append({
            "key": key, "label": label, "negative": len(neg), "positive": len(pos), "recent_negative": recent_neg,
            "score": len(neg) * 2 + recent_neg - len(pos) * 0.5,
            "fix": fix, "praise": praise,
            "quotes": [_short(i.text) for i in sorted(neg, key=lambda x: x.sentiment)[:2] if i.text],
        })

    problems = sorted([t for t in topics if t["negative"]], key=lambda t: -t["score"])
    strengths = sorted([t for t in topics if t["positive"] >= max(2, t["negative"] * 2)], key=lambda t: -t["positive"])

    suggestions = []
    for t in problems[:4]:
        mentions = f'{t["negative"]} unhappy mention' + ("s" if t["negative"] != 1 else "")
        suggestions.append({"topic": t["label"], "mentions": t["negative"], "text": t["fix"],
                            "evidence": mentions, "quotes": t["quotes"]})
    if silent_low:
        suggestions.append({"topic": "Silent low ratings", "mentions": len(silent_low),
                            "text": f"{len(silent_low)} low rating(s) came without a comment. Reach out to those "
                                    "customers to learn what went wrong.", "evidence": "", "quotes": []})
    if not suggestions and avg is not None and avg < 4:
        suggestions.append({"topic": "Ratings", "mentions": len(rated),
                            "text": "Ratings are below 4 stars but comments don't point to one cause. Ask customers "
                                    "a short follow-up question after delivery to find out.", "evidence": "",
                            "quotes": []})

    return {
        "empty": False,
        "reviews": sum(1 for i in items if i.source == "review"),
        "feedback": sum(1 for i in items if i.source == "feedback"),
        "average": avg,
        "trend": trend,
        "negative": len(negative),
        "positive": len(positive),
        "suggestions": suggestions,
        "strengths": [{"topic": t["label"], "mentions": t["positive"], "text": t["praise"]} for t in strengths[:3]],
        "frequent_complaint_words": _frequent_words([i.text for i in negative if i.text]),
    }


def feedback_category(text: str) -> str:
    """Best-matching topic label for a single piece of customer feedback."""
    t = normalize(text)
    for key, (label, pattern, _, _) in TOPICS.items():
        if re.search(pattern, t):
            return label
    return "General"
