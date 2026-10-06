# Transcription style guide (Bangla / Banglish lectures)

These rules define both what Raf-Khata's transcripts should look like and how human annotators write the
**gold (reference) transcripts** used to measure accuracy (`rk score`). When the speech-recognition
output and the reference follow the same rules, the error rates mean something.

## 1. Scripts
| Spoken | Written as | Example |
|---|---|---|
| Bangla word | Bengali script, Bangla Academy spelling | পরীক্ষা, বোঝানো |
| English word said as English | Latin script, as normally spelled | algorithm, database, deadline |
| English word fully absorbed into Bangla (everyday loanwords) | Bengali script | টেবিল, চেয়ার, ক্লাস (when said as Bangla) |
| Bangla suffix on an English word | English word, a space, then the Bangla suffix | `algorithm টা`, `function এর`, `node গুলো` |
| Abbreviation spoken letter by letter | Capital letters, no dots | CT, CGPA, DBMS, BFS |

Rule of thumb: if the teacher clearly switched into English for a technical term, write it in Latin
script. If you hesitate, write the term in Latin script and add it to the course glossary.

Example: `আজকে আমরা deadlock নিয়ে কথা বলব। Deadlock এর চারটা condition আছে।`

## 2. Numbers, maths and symbols
- Write numbers as digits: Bangla digits in Bangla sentences (৩টা, ২০২৬), ASCII digits in English phrases
  or formulas (`O(n log n)`, `x = 5`).
- Write formulas in plain text as spoken: `x square plus two x` stays words. **Notes**, not transcripts,
  convert maths to LaTeX.
- Write dates as said (১২ তারিখ, next Sunday).

## 3. Punctuation
- End a Bangla sentence with দাঁড়ি `।` and an English-only sentence with `.`. Use `?` for questions.
- Use commas sparingly, only where the speaker pauses within a sentence.
- Never put a space before `।`, `?` or `,`.

## 4. What to leave out or mark
- Leave out filled pauses (উম, আহ, uh) unless they carry meaning.
- Keep repetitions and self-corrections only once, as finally said: "এটা হলো, মানে এটা হচ্ছে" →
  `এটা হচ্ছে`.
- Mark inaudible speech as `[অস্পষ্ট]`. Never guess.
- Mark non-speech events only when they matter: `[হাসি]`, `[বোর্ডে লিখছেন]`.

## 5. Speakers
- Start each speaker turn on a new line with `T:` (teacher) or `S:` (student). Number several students
  `S1:`, `S2:` when it matters.
- Overlapping speech: write the main speaker and add `[একসাথে কথা]`.

## 6. Unicode
- Save files as UTF-8, NFC.
- Use য়/ড়/ঢ় as typed by a standard Bangla keyboard (Avro/Bijoy Unicode). The scorer normalizes
  equivalent forms.
- Use ৎ, never ত্‍.
- Use the real দাঁড়ি `।`, not `|`.

## 7. Gold-set process (Phase 0)
1. Cut 5–10-minute windows from full recordings, spread over subjects, teachers and audio quality.
2. Annotator A transcribes from scratch, never by correcting a machine transcript, to avoid anchoring.
3. Annotator B reviews against the audio. Disagreements go to a third person.
4. Keep a per-course glossary file (one term per line) next to the references. It feeds term-recall
   scoring and the pipeline's glossary.
5. Save as `<id>.txt` (reference) and `<id>.glossary.txt`, with the audio as `<id>.m4a` / `.wav`.

Scoring (`rk score hyp.txt ref.txt --glossary ref.glossary.txt`) strips speaker labels and `[...]` tags,
removes punctuation and joiners, maps digits, lowercases Latin text, and then reports CER, WER and
term recall.
