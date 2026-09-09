# Review: what a learner may see after submitting, and when

**Task:** T-6.9 · **Service:** `assessment` (`review/`)

> For a formative quiz, showing the correct answers immediately is the entire point. For a
> certification exam drawn from a bank, it hands the bank to anyone willing to sit the test once and
> screenshot it. Both are legitimate; the product has to express both, and the default has to be the
> safe one.

## Two axes

| visibility | shows |
|---|---|
| `SCORE_ONLY` **(default)** | the score, and nothing about the questions |
| `SCORE_AND_WHICH_WRONG` | which questions were wrong, without saying what was right |
| `FULL` | the correct answers and the author's explanation |

| timing | opens |
|---|---|
| `IMMEDIATELY` **(default)** | as soon as the attempt is marked |
| `AFTER_ALL_ATTEMPTS` | once the learner has used every attempt |
| `AFTER_DATE` | after a date the author chose |

**The defaults are restrictive where it counts.** Visibility is the axis that carries the risk, and
it defaults to the most restrictive value. Timing defaults to `IMMEDIATELY`, which is not a
relaxation: under `SCORE_ONLY` there is nothing to gate, and a learner seeing their own score the
moment they finish discloses nothing. Defaulting to "you may not see your score until you have used
every attempt" would protect nothing and would be turned off by every author — which is how a safe
default becomes a habit of overriding defaults.

**Timing lowers visibility; it does not refuse the request.** A learner whose review has not opened
yet still sees their score, because the score was never what the timing was protecting.

### `AFTER_ALL_ATTEMPTS` cannot be set on a test with no attempt limit

It would mean *never*, and the person who finds that out is the learner who can never see their
paper. Refused at authoring with that sentence.

## The redaction is a construction, not a filter

The review view is **built** from the policy. A field the policy does not permit is never put into
the response object at all — there is no parameter, header or body shape a client can send that
reaches a different branch. That is what *"regardless of what the client asks for"* has to mean to be
worth anything.

The alternative — fetch everything, then remove what is not allowed — is the version that leaks the
day somebody adds a field to the DTO and forgets the filter.

A key that travels and is hidden by a renderer is a key in the learner's browser, in their network
tab, and in whatever cached it on the way.

## Reconstructed from the form, not from the test

Including the option order this learner was shown (T-6.5). A review that rendered the test's order
would be showing them somebody else's paper. The form is the record of what was served, and it is
frozen.

**"Which were wrong" is answered from the mark, not from the key.** At
`SCORE_AND_WHICH_WRONG` the answer key never goes near the response — the mark already knows.

## Feedback lives in the question version

The criterion asks for it *"authored on the question version, so it stays correct across edits"*, and
that is satisfied by putting it **inside the versioned body** (T-6.2) rather than in a table keyed by
question. The body is frozen once served (ADR-0106), so an author who improves the wording writes a
new version and the learner who already read the old one still sees what they were shown.

Feedback in a table would be feedback that could be rewritten after somebody read it — precisely
what the criterion is guarding against. It needed no migration at all.

## An unmarked attempt reviews as `SCORE_ONLY`

A provisional score is not a result (T-6.7). While an essay is still waiting for a person there is
nothing settled to review.

## The last criterion, honestly

> *A test asserting a learner cannot obtain the key by calling the question endpoint directly.*

**Partially met, and the remainder is not this card's to close.**

What changed: the ordinary read of a question **no longer carries the answer key or the feedback**.
Until now every read of `GET /questions/{id}` and `GET /questions/{id}/versions/{versionId}` returned
the correct answer in the payload — to any client, without anybody asking for it. The full body now
lives on one explicit path, `…/versions/{versionId}/authoring`.

What that is: the key stops travelling **by accident**, and there is now exactly **one** endpoint for
`content:author` to be enforced on when it can be, instead of four.

What that is **not**: an authorization check. Nothing in this service can tell an author from a
learner. Identity owns the grants and does not expose them to another process — `/api/v1/me` returns
an id, an email, a display name and a status, and no permissions (T-9.11, ADR-0109). A local "is this
person an author" shortcut is exactly the special case ADR-0103 refuses, and it would be the thing an
endpoint later trusts.

So a determined learner can still call the authoring path. The review policy governs the review path
completely; the authoring surface waits on grants travelling between services.
