# Question types, and the three shapes each one has

**Task:** T-6.3 · **Service:** `assessment` · **Code:** `question/type/`

A question version's body is one `jsonb` document (T-6.2), so the database will not catch a
malformed one. This page is what the shapes are; `QuestionTypes` is what enforces them, on every
save and on every edit.

The failure it exists for, in one sentence from the issue: **a question saved with an answer key
that does not match its options is discovered when a learner cannot score** — which is after the
exam.

## The body

```json
{
  "type":      "single-choice",
  "stem":      "Which extinguisher suits an electrical fire?",
  "media":     ["3f6b1d2e-1c4a-4a7e-9b3d-2f5c8e7a1b04"],
  "options":   { "…per type…" },
  "answerKey": { "…per type…" }
}
```

`media` is optional and holds **content item ids, never URLs**. A URL copied here is a copy of a
fact catalog owns, and it breaks the first time an asset moves — with no way to find the questions
that embedded it but a text search.

## The ten types

| type | options | answerKey | response |
|---|---|---|---|
| `single-choice` | `{"choices":[{"id","text"}]}` ≥2 | `{"correct":["b"]}` exactly one | `{"chosen":["b"]}` |
| `multiple-choice` | same | `{"correct":["a","c"]}` ≥1, not all | `{"chosen":[…]}` |
| `true-false` | — (the choices are ours) | `{"correct":["true"]}` | `{"chosen":["true"]}` |
| `matching` | `{"left":[{"id","text"}],"right":[…]}` | `{"pairs":[{"left","right"}]}`, every left | `{"pairs":[…]}` |
| `ordering` | `{"items":[{"id","text"}]}` ≥2 | `{"order":[…]}`, every item once | `{"order":[…]}` all or none |
| `fill-in` | `{"blanks":[{"id","text"}]}` | `{"accepted":{"b1":["Ankara","Angora"]}}` | `{"filled":{"b1":"ankara"}}` |
| `numeric` | `{"unit":"kg"}` optional | `{"value":6.35,"tolerance":0.05}` | `{"value":6.4}` |
| `hotspot` | `{"image":"<id>","regions":[{"id","text","shape"}]}` | `{"correct":["r1"]}` | `{"selected":["r1"]}` |
| `essay` | `{"guidance":"…"}` optional | none — refused | `{"text":"…"}` |
| `file-upload` | `{"accept":["pdf"]}` optional | none — refused | `{"uploadId":"…"}` |

**An empty response array is an unanswered question**, not an error. Refusing it would turn "I do
not know" into a failed request, mid-exam.

## Four decisions worth knowing before building a screen

**Every option carries text, not only an id.** That is the accessibility criterion enforced where
it survives. An ordering question whose items are known only by id can be presented as draggable
boxes and as nothing else; with text, a keyboard user can be offered *move "Close the valve" up*.
Same for hotspot regions — which is why a hotspot's response shape is a choice question's with a
different field name. The keyboard path and the pointer path submit the same thing, so the
accessible route cannot rot separately from the visual one.

**Wrong picks count against you.** For multiple choice and hotspot, `credited` is right picks minus
wrong ones, floored at zero. Counting only the correct ones a learner found would give full marks
for selecting everything, which is not a measure of correctness at all. Whether partial credit is
*used* — two of three as two thirds, all, or nothing — is a scoring mode and is T-6.4's
([`scoring.md`](scoring.md)).

**Ordering credits absolute positions.** A learner who shifts everything by one has every *pair*
right and every position wrong; reporting that as nearly full marks would be the arithmetic
disagreeing with the question.

**Fill-in compares trimmed and case-insensitively.** The accepted list is for genuine variants —
"Angora", "carbon dioxide" — which only an author can judge. It is not for case: an author who has
to list three capitalisations will list two and be surprised by a learner who typed the third.

## Adding a type

One bean implementing `QuestionTypeDefinition`. The registry finds it; nothing enumerates the
types, so there is no enum to extend, no switch to miss a case, and no migration. `AddingATypeTest`
demonstrates it by adding an eleventh and creating a question of it through the ordinary service.

Two things to get right, both of which this repository has paid for:

- **Do not name the `@Bean` method after its `@Configuration` class.** Component scanning already
  bound the decapitalised class name, so the same name is a `BeanDefinitionOverrideException` — a
  refusal to start, which unit tests do not catch because a test that calls the method never
  registers it.
- **Two definitions with one code fail startup deliberately.** Which validator ran would otherwise
  be decided by bean ordering: it works in development and refuses a customer's question in
  production, or accepts one it should not.

## What is not here yet

- **Scoring modes** — done, in T-6.4 (#63) and [`scoring.md`](scoring.md). `grade` still returns
  *how much was right* and nothing about what it is worth; the one thing T-6.4 added here is
  `guessable()`, because whether a wrong answer could have been a guess is the type's to know and
  it is the only thing negative marking is for.
- **The manual queue** — done, in T-6.7 (#66) and [`grading.md`](grading.md). `grade` returning
  empty is what lets the queue ask the type rather than keep its own list, so an eleventh
  human-marked type needs no change there.
- **Shuffling** is decided here and applied by T-6.5's assembler
  ([`test-assembly.md`](test-assembly.md)): `shufflableOptionFields()` says which of a type's option
  lists may be presented in any order, and the form records what each learner actually saw.
- **Submitting anything** — done, in T-6.6 (#65) and [`attempts.md`](attempts.md).
  `validateResponse` now has its caller, and it validates against the version the learner was
  *served* rather than the question as it is now: a version edited since is a different question.
  `grade` still waits for T-6.7 (#66) to call it.
- **Reporting.** T-6.3's last criterion asks for fixtures exercised "from authoring to scoring to
  reporting". Authoring and scoring are covered end to end; item analysis is T-7.7 (#75).
