Task: find and fix clear speech-recognition mistakes in the transcript above. Return them as patches. Do not rewrite the transcript.

Fix only these:
1. **Misheard technical terms or names.** Use the course glossary and the lecture's context. Example: "ব্যাংকার্স অ্যালগরিদম" → "banker's algorithm".
2. **English words written in Bengali script** that the teacher clearly said as English terms, e.g. "অ্যালগরিদম" → "algorithm", "ডেটাবেস" → "database". Leave everyday loanwords that belong to Bangla speech (ক্লাস, টেবিল, স্যার, টিচার) in Bengali script.
3. **Obvious number or unit mistakes,** when the context makes the right value certain.

Do not:
- paraphrase, summarize, translate, or fix grammar or style;
- change anything you are not confident about;
- touch speaker labels, timestamps or line ids.

Each patch:
- `segment_id`: the line id.
- `find`: text copied exactly from that line. Keep it short, just the wrong words.
- `replace`: the corrected text.
- `reason`: the category from the list above.

If the transcript needs no fixes, return an empty list.
