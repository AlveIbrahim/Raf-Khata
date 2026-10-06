"""Resolve announced due dates ("আগামী রবিবার", "next Sunday", "১২ তারিখ") to calendar dates.

Claude is asked to return an ISO date when it can work one out from the lecture date. This module
checks that date and, when there is none, tries the common Bangla/English phrasings itself.
"""

from __future__ import annotations

import re
import unicodedata
from datetime import date, timedelta

from rafkhata.pipeline.normalize import bn_digits_to_ascii

# Monday = 0, as in date.weekday()
WEEKDAYS: dict[int, tuple[str, ...]] = {
    0: ("সোমবার", "monday"),
    1: ("মঙ্গলবার", "tuesday"),
    2: ("বুধবার", "wednesday"),
    3: ("বৃহস্পতিবার", "বিষ্যুদবার", "thursday"),
    4: ("শুক্রবার", "friday"),
    5: ("শনিবার", "saturday"),
    6: ("রবিবার", "রোববার", "sunday"),
}
MONTHS: dict[int, tuple[str, ...]] = {
    1: ("জানুয়ারি", "january"),
    2: ("ফেব্রুয়ারি", "february"),
    3: ("মার্চ", "march"),
    4: ("এপ্রিল", "april"),
    5: ("মে", "may"),
    6: ("জুন", "june"),
    7: ("জুলাই", "july"),
    8: ("আগস্ট", "august"),
    9: ("সেপ্টেম্বর", "september"),
    10: ("অক্টোবর", "october"),
    11: ("নভেম্বর", "november"),
    12: ("ডিসেম্বর", "december"),
}
MAX_AHEAD_DAYS = 200

_NEXT_WEEK = ("পরের সপ্তাহ", "পরের সপ্তাহের", "আগামী সপ্তাহ", "next week")


def _in_range(d: date, lecture_date: date | None) -> bool:
    if lecture_date is None:
        return True
    return lecture_date - timedelta(days=1) <= d <= lecture_date + timedelta(days=MAX_AHEAD_DAYS)


def _safe_date(year: int, month: int, day: int) -> date | None:
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _month_in(text: str) -> int | None:
    for month, names in MONTHS.items():
        if any(name in text for name in names):
            return month
    short = re.search(r"\b(jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\b", text)
    if short:
        return ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"].index(
            short.group(1)[:3]
        ) + 1
    return None


def resolve_due_date(iso: str | None, text: str | None, lecture_date: date | None) -> date | None:
    if iso:
        try:
            parsed = date.fromisoformat(iso[:10])
            if _in_range(parsed, lecture_date):
                return parsed
        except ValueError:
            pass
    if not text or lecture_date is None:
        return None

    t = bn_digits_to_ascii(unicodedata.normalize("NFC", text)).lower()
    base = lecture_date

    if "আগামীকাল" in t or "tomorrow" in t or re.search(r"(^|\s)কাল(\s|$)", t):
        return base + timedelta(days=1)
    if "পরশু" in t or "day after tomorrow" in t:
        return base + timedelta(days=2)
    if re.search(r"(^|\s)আজ(কে)?(\s|$)", t) or "today" in t:
        return base

    # dd/mm(/yyyy), dd-mm, dd.mm
    m = re.search(r"\b(\d{1,2})[/\-.](\d{1,2})(?:[/\-.](\d{2,4}))?\b", t)
    if m:
        day, month = int(m.group(1)), int(m.group(2))
        year = int(m.group(3)) if m.group(3) else base.year
        if year < 100:
            year += 2000
        candidate = _safe_date(year, month, day)
        if candidate and not m.group(3) and candidate < base - timedelta(days=1):
            candidate = _safe_date(year + 1, month, day)
        if candidate and _in_range(candidate, lecture_date):
            return candidate

    month = _month_in(t)
    day_match = re.search(r"\b(\d{1,2})\s*(?:তারিখ|th|st|nd|rd)?\b", t)
    if day_match:
        day = int(day_match.group(1))
        if month:
            candidate = _safe_date(base.year, month, day)
            if candidate and candidate < base - timedelta(days=1):
                candidate = _safe_date(base.year + 1, month, day)
            if candidate and _in_range(candidate, lecture_date):
                return candidate
        elif "তারিখ" in t or re.search(r"\b\d{1,2}(th|st|nd|rd)\b", t):
            candidate = _safe_date(base.year, base.month, day)
            if candidate is None or candidate < base:
                next_month = base.month % 12 + 1
                candidate = _safe_date(base.year + (base.month == 12), next_month, day)
            if candidate and _in_range(candidate, lecture_date):
                return candidate

    for weekday, names in WEEKDAYS.items():
        if any(name in t for name in names):
            ahead = (weekday - base.weekday()) % 7 or 7
            if any(phrase in t for phrase in _NEXT_WEEK):
                # The Bangladeshi week starts on Saturday. "Next week's Sunday" must fall after the
                # coming Saturday.
                to_saturday = (5 - base.weekday()) % 7 or 7
                if ahead < to_saturday:
                    ahead += 7
            return base + timedelta(days=ahead)
    return None
