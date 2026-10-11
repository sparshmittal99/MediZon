"""Small, dependency-free parsers for chat text: times, dates, quantities, ordinals and medicine names."""
from __future__ import annotations

import re
from datetime import date, datetime, time, timedelta

_SPACES = re.compile(r"\s+")


def normalize(text: str | None) -> str:
    if not text:
        return ""
    text = text.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return _SPACES.sub(" ", text).strip().lower()


# ---------------------------------------------------------------- times of day

_NAMED_TIMES = [
    (r"\b(early morning|breakfast|morning|wake ?up)\b", "08:00"),
    (r"\b(noon|midday|lunch ?time|lunch)\b", "13:00"),
    (r"\bafternoon\b", "14:00"),
    (r"\b(evening|tea ?time)\b", "18:00"),
    (r"\b(dinner|supper)\b", "20:00"),
    (r"\b(tonight|night|bed ?time|before bed|before sleep(ing)?)\b", "21:00"),
]

_FREQUENCIES = [
    (r"\b(four times|4 times|qid)\b", ["08:00", "12:00", "16:00", "20:00"]),
    (r"\b(three times|3 times|thrice|tid|tds)\b", ["08:00", "14:00", "20:00"]),
    (r"\b(twice|two times|2 times|bid|bd)\b", ["08:00", "20:00"]),
    (r"\b(once a day|once daily|once|daily|every day|everyday|od)\b", ["08:00"]),
]

_CLOCK = re.compile(r"\b(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?![a-z])")
_CLOCK_24 = re.compile(r"\b([01]?\d|2[0-3])[:.]([0-5]\d)\b")
_BARE_AT = re.compile(r"\bat\s+(\d{1,2})\b(?!\s*[:.]?\d)(?!\s*(?:a\.?m|p\.?m|%|mg|ml|packs?|units?|tablets?))")


def _fmt(h: int, m: int) -> str | None:
    if 0 <= h <= 23 and 0 <= m <= 59:
        return f"{h:02d}:{m:02d}"
    return None


def parse_times(text: str, use_frequency: bool = True) -> list[str]:
    """Every clock time mentioned, as sorted 'HH:MM'. Falls back to named times, then to frequency words."""
    t = normalize(text)
    found: list[str] = []
    taken: list[tuple[int, int]] = []

    for m in _CLOCK.finditer(t):
        h, mi, ampm = int(m.group(1)), int(m.group(2) or 0), m.group(3).replace(".", "")
        if not 1 <= h <= 12:
            continue
        if ampm == "pm" and h != 12:
            h += 12
        if ampm == "am" and h == 12:
            h = 0
        v = _fmt(h, mi)
        if v:
            found.append(v)
            taken.append(m.span())

    for m in _CLOCK_24.finditer(t):
        if any(a <= m.start() < b for a, b in taken):
            continue
        v = _fmt(int(m.group(1)), int(m.group(2)))
        if v:
            found.append(v)
            taken.append(m.span())

    for m in _BARE_AT.finditer(t):
        if any(a <= m.start() < b for a, b in taken):
            continue
        h = int(m.group(1))
        if 1 <= h <= 6:
            h += 12          # "at 5" in a reminder almost always means 5 pm
        elif h > 23:
            continue
        v = _fmt(h, 0)
        if v:
            found.append(v)

    for pattern, value in _NAMED_TIMES:
        if re.search(pattern, t):
            found.append(value)

    if not found and use_frequency:
        for pattern, values in _FREQUENCIES:
            if re.search(pattern, t):
                found.extend(values)
                break

    return sorted(set(found))


def pretty_time(hhmm: str) -> str:
    h, m = (int(x) for x in hhmm.split(":"))
    suffix = "AM" if h < 12 else "PM"
    h12 = h % 12 or 12
    return f"{h12}:{m:02d} {suffix}"


def join_words(items: list[str]) -> str:
    items = [i for i in items if i]
    if len(items) <= 1:
        return "".join(items)
    return ", ".join(items[:-1]) + " and " + items[-1]


# ---------------------------------------------------------------- dates for one-off reminders

_WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
_MONTHS = {m: i + 1 for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"])}
_IN_DELTA = re.compile(r"\bin\s+(an?|\d{1,3})\s*(min(?:ute)?s?|hours?|hrs?|days?|weeks?)\b")
_DAY_MONTH = re.compile(r"\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b")
_MONTH_DAY = re.compile(r"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})(?:st|nd|rd|th)?\b")
_SLASH_DATE = re.compile(r"\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b")
_ISO_DATE = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")


def _next_date(now: datetime, month: int, day: int, year: int | None = None) -> date | None:
    try:
        d = date(year or now.year, month, day)
    except ValueError:
        return None
    if year is None and d < now.date():
        try:
            d = date(now.year + 1, month, day)
        except ValueError:
            return None
    return d


def parse_when(text: str, now: datetime) -> datetime | None:
    """A single future moment for 'remind me ...' requests, or None when no date or time is mentioned."""
    t = normalize(text)

    m = _IN_DELTA.search(t)
    if m:
        n = 1 if m.group(1) in ("a", "an") else int(m.group(1))
        unit = m.group(2)
        delta = (timedelta(minutes=n) if unit.startswith("min") else
                 timedelta(hours=n) if unit.startswith("h") else
                 timedelta(weeks=n) if unit.startswith("week") else timedelta(days=n))
        return (now + delta).replace(second=0, microsecond=0)

    day: date | None = None
    if m := _ISO_DATE.search(t):
        day = _next_date(now, int(m.group(2)), int(m.group(3)), int(m.group(1)))
    elif m := _DAY_MONTH.search(t):
        day = _next_date(now, _MONTHS[m.group(2)], int(m.group(1)))
    elif m := _MONTH_DAY.search(t):
        day = _next_date(now, _MONTHS[m.group(1)], int(m.group(2)))
    elif m := _SLASH_DATE.search(t):
        year = m.group(3)
        y = None if not year else (int(year) + 2000 if len(year) == 2 else int(year))
        day = _next_date(now, int(m.group(2)), int(m.group(1)), y)   # Indian order: day/month
    elif "day after tomorrow" in t:
        day = now.date() + timedelta(days=2)
    elif re.search(r"\btomorrow\b|\btmrw\b|\btmr\b", t):
        day = now.date() + timedelta(days=1)
    elif re.search(r"\btoday\b|\btonight\b|\bthis (morning|afternoon|evening)\b", t):
        day = now.date()
    elif re.search(r"\bnext week\b", t):
        day = now.date() + timedelta(days=7)
    else:
        for i, name in enumerate(_WEEKDAYS):
            if re.search(rf"\b{name[:3]}({name[3:]})?\b", t):
                ahead = (i - now.weekday()) % 7 or 7
                day = now.date() + timedelta(days=ahead)
                break

    times = parse_times(t, use_frequency=False)
    if day is None and not times:
        return None
    hhmm = times[0] if times else "09:00"
    h, mi = (int(x) for x in hhmm.split(":"))
    when = datetime.combine(day or now.date(), time(h, mi))
    if day is None and when <= now:
        when += timedelta(days=1)          # "at 5 pm" after 5 pm means tomorrow
    return when


def pretty_when(when: datetime, now: datetime) -> str:
    if when.date() == now.date():
        d = "today"
    elif when.date() == now.date() + timedelta(days=1):
        d = "tomorrow"
    else:
        d = when.strftime("%a, %d %b").replace(" 0", " ")
    return f"{d} at {pretty_time(when.strftime('%H:%M'))}"


# ---------------------------------------------------------------- numbers

_WORD_NUMBERS = {"one": 1, "a": 1, "an": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
                 "seven": 7, "eight": 8, "nine": 9, "ten": 10, "a couple of": 2, "couple of": 2}
_QTY = re.compile(r"\b(\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|a couple of|couple of)\s*"
                  r"(x\b|packs?|packets?|strips?|box(?:es)?|bottles?|units?|of)\b")
_LEADING_QTY = re.compile(r"\b(?:add|buy|order|get)\s+(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten)\b(?!\s*(?:mg|ml|mcg|iu|%))")


def parse_quantity(text: str) -> int | None:
    t = normalize(text)
    m = _QTY.search(t) or _LEADING_QTY.search(t)
    if not m:
        return None
    raw = m.group(1)
    n = int(raw) if raw.isdigit() else _WORD_NUMBERS.get(raw)
    return n if n and 0 < n <= 50 else None


def parse_int(text: str) -> int | None:
    t = normalize(text)
    m = re.search(r"\b(\d{1,4})\b", t)
    if m:
        return int(m.group(1))
    for word, n in _WORD_NUMBERS.items():
        if word not in ("a", "an") and re.search(rf"\b{word}\b", t):
            return n
    return None


_ORDINALS = {"first": 0, "1st": 0, "second": 1, "2nd": 1, "third": 2, "3rd": 2, "fourth": 3, "4th": 3,
             "fifth": 4, "5th": 4, "last": -1}


def parse_ordinal(text: str) -> int | None:
    """'the second one' -> 1, 'number 3' -> 2, 'last' -> -1."""
    t = normalize(text)
    for word, idx in _ORDINALS.items():
        if re.search(rf"\b{word}\b", t):
            return idx
    m = re.fullmatch(r"(?:no\.?|number|#|option)?\s*(\d)\.?", t)
    if m:
        return int(m.group(1)) - 1
    return None


# ---------------------------------------------------------------- yes / no / skip

def is_yes(text: str) -> bool:
    return bool(re.fullmatch(r"(yes|yeah|yep|yup|sure|ok|okay|ok sure|please|yes please|go ahead|do it|confirm|"
                             r"correct|right|haan|ha|han|ji|y)[.! ]*", normalize(text)))


def is_no(text: str) -> bool:
    return bool(re.fullmatch(r"(no|nope|nah|not now|no thanks|no thank you|nahi|n)[.! ]*", normalize(text)))


def is_skip(text: str) -> bool:
    return bool(re.search(r"\b(skip|don'?t know|dont know|not sure|no idea|later|leave it)\b", normalize(text)))


# ---------------------------------------------------------------- medicine names

_FILLER = set("""
a an the i me my mine we our you your it its this that these those is are was were be been am do does did
can could would will shall should may might must please pls plz kindly want wanna need needs needed looking look
find search show get give tell know see check have has having got any some something anything for of to in on
at with about from by and or but if so also just only now today again more much many what which who where when
how why there here than then up out over under off all very really pharmacy store medizon available availability
stock stocked sell sells selling sold buy buying purchase order ordering add adding put cart basket bag into
medicine medicines medication medications drug drugs tablet tablets tab tabs pill pills capsule capsules strip
strips pack packs packet packets box boxes bottle bottles unit units piece pieces x info information details
detail used use uses usage side effects effect dose dosage price cost costs rate mrp let lets alright okay ok
hi hello hey thanks thank yes no reminder reminders remind set setup schedule alarm alert daily every day times
time twice thrice once morning night evening afternoon bedtime am pm take taking took refill reorder
another one ones same item items
""".split())

# Symptom words -> catalog category (only used when that category exists in the store).
SYMPTOM_CATEGORIES = [
    (r"fever|temperature|headache|head ache|body ?ache|pain|migraine|toothache|cramps?", "Pain & fever"),
    (r"cough|cold|sore throat|throat|congestion|blocked nose|flu\b", "Cold & cough"),
    (r"allerg|sneez|itch|runny nose|hives|rash", "Allergy"),
    (r"acidity|heartburn|gas\b|bloat|stomach|diarrh|loose motion|vomit|nausea|dehydrat|indigestion", "Stomach care"),
    (r"sugar|diabet", "Diabetes"),
    (r"\bbp\b|blood pressure|hypertension|cholesterol|heart", "Heart & blood pressure"),
    (r"thyroid", "Thyroid"),
    (r"vitamin|supplement|immunity|weakness|fatigue|tired|omega|fish oil|calcium", "Vitamins & supplements"),
    (r"thermometer|glucometer|test strips?|device|bp machine|monitor", "Devices"),
    (r"infection|antibiotic", "Antibiotics"),
]


def symptom_category(text: str, categories: list[str]) -> str | None:
    t = normalize(text)
    known = {c.lower(): c for c in categories or []}
    for pattern, category in SYMPTOM_CATEGORIES:
        if re.search(pattern, t) and category.lower() in known:
            return known[category.lower()]
    return None


def medicine_query(text: str) -> str:
    """The words in a message that look like a medicine name, e.g. 'do you have paracetamol 650?' -> 'paracetamol 650'."""
    t = normalize(text)
    t = re.sub(r"[^a-z0-9.+\- ]", " ", t)
    t = _QTY.sub(" ", t)
    words = []
    for w in t.split():
        w = w.strip(".-+")
        if not w or w in _FILLER or w in _WORD_NUMBERS:
            continue
        if re.fullmatch(r"\d{1,2}(am|pm)?", w) and not words:
            continue
        words.append(w)
    return " ".join(words).strip()
