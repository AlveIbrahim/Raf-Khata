Task: make a study pack students can practise with before a class test or exam.

Language: {language_instructions}

Produce:
- **flashcards** (8–20): a term or question on the front, a short answer on the back.
- **mcqs** (5–10): exactly 4 options each, one correct (`answer_index` is 0-based), plus a one-line explanation. Wrong options must be plausible but clearly wrong according to the lecture.
- **questions**:
  - 3–6 `short` questions (2–5 marks).
  - 1–3 `broad` questions (10 marks).
  - Write them the way Bangladeshi university exams ask, e.g. "সংজ্ঞা দাও", "পার্থক্য লেখ", "ব্যাখ্যা কর", "উদাহরণসহ আলোচনা কর" or their English equivalents ("Define", "Differentiate", "Explain with an example").
  - List the key points a full-mark answer needs.

Base every item on what was taught in this lecture, and give the line ids in `source_segment_ids`. If the lecture is too short for the minimum counts, make fewer items rather than inventing content.
