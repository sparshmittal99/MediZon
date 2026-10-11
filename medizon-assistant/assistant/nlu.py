"""Intent detection: a small TF-IDF + logistic regression model per role, trained at startup, plus a few
high-precision rules that run first (emergencies, explicit 'remind me', checkout)."""
from __future__ import annotations

import re
from dataclasses import dataclass

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import FeatureUnion, Pipeline

from .intents import training_data
from .text import normalize, parse_times

MIN_CONFIDENCE = 0.30

EMERGENCY = re.compile(
    r"chest pain|can'?t breathe|cannot breathe|difficulty breathing|trouble breathing|short(ness)? of breath|"
    r"heart attack|stroke|face drooping|slurred speech|unconscious|fainted|passed out|seizure|fits\b|"
    r"severe (allergic|bleeding|pain)|anaphyla|swollen (throat|tongue)|overdose|took too many|"
    r"poison|suicid|kill myself|end my life|self[- ]?harm|want to die")


@dataclass
class Intent:
    name: str
    confidence: float


class IntentModel:
    def __init__(self, role: str):
        self.role = role
        texts, labels = training_data(role)
        features = FeatureUnion([
            ("words", TfidfVectorizer(analyzer="word", ngram_range=(1, 2), sublinear_tf=True,
                                      token_pattern=r"(?u)\b\w+\b")),
            ("chars", TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), sublinear_tf=True)),
        ])
        self.pipeline = Pipeline([
            ("features", features),
            ("clf", LogisticRegression(C=12, max_iter=3000, class_weight="balanced")),
        ])
        self.pipeline.fit([normalize(t) for t in texts], labels)

    def predict(self, text: str) -> Intent:
        t = normalize(text)
        rule = self._rules(t)
        if rule:
            return Intent(rule, 1.0)
        probs = self.pipeline.predict_proba([t])[0]
        best = probs.argmax()
        return Intent(self.pipeline.classes_[best], float(probs[best]))

    def _rules(self, t: str) -> str | None:
        if EMERGENCY.search(t):
            return "emergency"
        if re.fullmatch(r"(cancel|stop|reset|start over|never ?mind)[.! ]*", t):
            return "cancel"
        if re.search(r"\b(check ?out|place (my )?order|proceed to pay|pay now)\b", t) and self.role == "customer":
            return "checkout"
        if re.search(r"\bremind me\b|\breminder\b|\bremember to\b", t):
            if re.search(r"\b(show|list|what|which|my|pending|upcoming)\b.*\breminders\b", t) and not parse_times(t, False):
                if self.role == "customer" and re.search(r"\bmedicine|tablet|dose", t):
                    return "my_reminders"
                return "list_general_reminders"
            if re.search(r"\b(done|complete|finish|dismiss|clear)\b", t) and self.role == "admin":
                return "complete_reminder"
            if self.role == "customer" and re.search(r"\b(take|taking|dose|tablet|pill|capsule|medicine|"
                                                     r"twice|thrice|daily|every day|after (food|dinner|meal))\b", t):
                return "set_reminder"
            # "reminder for my vitamin d" names a medicine; "reminder for my doctor appointment" doesn't
            after = t.split("reminder", 1)[1] if "reminder" in t else ""
            if self.role == "customer" and re.match(r"s? (for|of)\b", after) and not re.search(
                    r"\b(to|appointment|doctor|dr|test|call|meeting|check ?up|visit|pay|bill|scan|lab)\b", after):
                return "set_reminder"
            return "set_general_reminder"
        return None


_models: dict[str, IntentModel] = {}


def model(role: str) -> IntentModel:
    role = "admin" if role == "admin" else "customer"
    if role not in _models:
        _models[role] = IntentModel(role)
    return _models[role]


def warm_up() -> None:
    model("customer")
    model("admin")
