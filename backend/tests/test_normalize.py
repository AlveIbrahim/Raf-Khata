from __future__ import annotations

from rafkhata.pipeline.normalize import (
    is_latin_token,
    latin_tokens,
    normalize_for_match,
    normalize_text,
)


def test_nukta_forms_are_equivalent() -> None:
    precomposed = "য়া"  # য়া with the single code point for য়
    decomposed = "য়া"  # য + nukta + া
    assert normalize_text(precomposed) == normalize_text(decomposed)
    assert normalize_for_match("বিষয়") == normalize_for_match("বিষয়")


def test_common_slips_are_fixed() -> None:
    assert normalize_text("অামি") == "আমি"  # অ + া -> আ
    assert normalize_text("উৎস") == normalize_text("উত্‍স")  # legacy khanda ta
    assert normalize_text("ক্্ষ") == "ক্ষ"


def test_spacing_and_danda() -> None:
    assert normalize_text("আজ   ক্লাস হবে |") == "আজ ক্লাস হবে।"
    assert normalize_text("এটা কী ?  হ্যাঁ ।") == "এটা কী? হ্যাঁ।"
    assert normalize_text("a | b") == "a | b"  # Latin pipes are left alone


def test_vowel_signs_survive_match_normalization() -> None:
    # Vowel signs are combining marks; they must not be treated as punctuation.
    assert normalize_for_match("পরীক্ষা।") == "পরীক্ষা"
    assert normalize_for_match("কোড, ডেটা!") == "কোড ডেটা"


def test_match_normalization() -> None:
    assert normalize_for_match("T: Deadlock এর ৪টা Condition আছে। [অস্পষ্ট]") == "deadlock এর 4টা condition আছে"
    assert normalize_for_match("S1: র‍্যাব") == normalize_for_match("র্যাব")
    assert normalize_for_match("O(n log n)") == "o n log n"


def test_latin_tokens() -> None:
    assert latin_tokens("এই algorithm টা O(n) time এ run করে") == ["algorithm", "o", "n", "time", "run"]
    assert is_latin_token("CT")
    assert not is_latin_token("সিটি")
