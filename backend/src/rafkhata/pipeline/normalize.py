"""Bangla / Banglish text normalization.

Two levels:
- `normalize_text`: for display and storage. Fixes common encoding slips but keeps punctuation and
  joiners (ZWJ/ZWNJ change how conjuncts render).
- `normalize_for_match`: for search and accuracy scoring. Additionally drops punctuation, symbols,
  joiners, `[...]` tags and speaker labels, maps Bangla digits to ASCII and lowercases Latin text.

Note: Python's `\\w` does not match Bangla vowel signs (Unicode categories Mc/Mn), so punctuation is
removed by Unicode category, never with `\\W`.
"""

from __future__ import annotations

import re
import unicodedata

ZWJ = "‍"
ZWNJ = "‌"
DANDA = "।"
BN_DIGITS = "০১২৩৪৫৬৭৮৯"
_BN_TO_ASCII = str.maketrans(BN_DIGITS, "0123456789")

# (wrong, right) pairs applied after NFC.
_FIXES = (
    ("অা", "আ"),  # অ + া typed instead of আ
    ("ত্‍", "ৎ"),  # ত + ্ + ZWJ (legacy khanda ta) -> ৎ
    ("্্", "্"),  # doubled hasanta
)
_PIPE_AS_DANDA = re.compile(r"(?<=[ঀ-৿])\s*\|(?!\|)")
_SPACE_BEFORE_PUNCT = re.compile(r"[ \t]+([।॥,?!:;.])")
_INLINE_SPACE = re.compile(r"[ \t   ]+")
_STRAY_JOINERS = re.compile(r"(?<!\S)[‌‍]+|[‌‍]+(?!\S)")
_TAG = re.compile(r"\[[^\]\n]*\]")
_SPEAKER = re.compile(r"^\s*(?:T|S\d*|Teacher|Student)\s*:\s*", re.MULTILINE | re.IGNORECASE)


def is_bangla_char(ch: str) -> bool:
    return "ঀ" <= ch <= "৿"


def normalize_text(text: str) -> str:
    """Canonical form for display/storage: NFC, encoding-slip fixes, tidy spacing."""
    out = unicodedata.normalize("NFC", text)
    for wrong, right in _FIXES:
        out = out.replace(wrong, right)
    out = _PIPE_AS_DANDA.sub(DANDA, out)
    out = _STRAY_JOINERS.sub("", out)
    out = _SPACE_BEFORE_PUNCT.sub(r"\1", out)
    lines = [_INLINE_SPACE.sub(" ", line).strip() for line in out.splitlines()]
    out = "\n".join(lines)
    out = re.sub(r"\n{3,}", "\n\n", out)
    return unicodedata.normalize("NFC", out).strip()


def bn_digits_to_ascii(text: str) -> str:
    return text.translate(_BN_TO_ASCII)


def strip_annotations(text: str) -> str:
    """Remove `[অস্পষ্ট]`-style tags and `T:` / `S1:` speaker labels."""
    return _SPEAKER.sub("", _TAG.sub(" ", text))


def normalize_for_match(text: str) -> str:
    """Aggressive normalization for search and CER/WER scoring."""
    out = strip_annotations(normalize_text(text))
    out = out.replace(ZWJ, "").replace(ZWNJ, "")
    out = bn_digits_to_ascii(out)
    out = "".join(" " if unicodedata.category(ch)[0] in "PS" else ch for ch in out)
    return " ".join(out.lower().split())


def is_latin_token(token: str) -> bool:
    return any("a" <= ch.lower() <= "z" for ch in token) and not any(is_bangla_char(ch) for ch in token)


def latin_tokens(text: str) -> list[str]:
    return [tok for tok in normalize_for_match(text).split() if is_latin_token(tok)]
