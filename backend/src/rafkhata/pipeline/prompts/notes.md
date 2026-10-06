Task: write study notes for this lecture.

Language: {language_instructions}

What to produce:
- **title**: a short title for the lecture.
- **summary**: 3–7 bullets covering the whole lecture.
- **sections**: detailed notes, one section per topic, in the order taught. Use short bullets students can revise from. Give each section the line id where its topic starts.
- **definitions**: terms the teacher defined or explained, with the term exactly as said.
- **formulas**: in LaTeX without $ signs, with what each symbol means. Only formulas the teacher stated.
- **examples**: worked examples or problems solved in class, step by step.
- **exam_alerts**: everything the teacher flagged as important or likely to be examined. Look for phrases like "এটা পরীক্ষায় আসবে", "মনে রাখবে", "important", "CT তে দিব", "exam এ আসবে", "এটা লিখে রাখো". Include the teacher's own words as the quote.
- **announcements**: CTs, quizzes, assignments, exams, presentations, lab work or class changes. Work out `due_date` (YYYY-MM-DD) from the lecture date when the date is certain, e.g. "আগামী রবিবার" after a Tuesday lecture means the coming Sunday. Otherwise set it to null and keep the words in `due_text`.
- **class_questions**: questions students asked, with the teacher's answer.
- **unclear_parts**: places too garbled to take notes from. Leave empty if there are none.

Every bullet and item cites the line ids it came from in `source_segment_ids`. Use empty lists for kinds of content the lecture doesn't have. Never invent content to fill a list.
