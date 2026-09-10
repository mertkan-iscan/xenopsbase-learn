# The API surface, in one page

**Generated** by `scripts/api_surface.py` from the OpenAPI descriptions in `web/api/`, which `npm run api:check` proves match the running services. Regenerate it rather than editing it: a hand-kept inventory of 153 endpoints is one that stops matching the code, and the first person to notice is whoever designed a screen around a call that does not exist.

## What a browser talks to

**One origin.** The browser calls the **gateway** and nothing else. It holds the session — the access and refresh tokens live server-side in Valkey, and no page ever sees one (T-10.2). Everything under `/api` is relayed inward by path.

- **Credential:** an opaque session cookie. Writes must echo the CSRF value (`X-XSRF-TOKEN`, from the `XSRF-TOKEN` cookie).
- **401 means the session ended** and is never a redirect — it is a `problem+json` with `code: SESSION_ENDED`. Sign in again; do not retry.
- **403** means signed in but not permitted. **404** may mean "not yours to know about" (T-2.4's disclosure rule), so never render it as "it was deleted".
- **Every refusal is RFC 9457** `application/problem+json` with a `code` a client switches on. The `Problem` schema is in every description.

### Sign-in, which is not under `/api`

| path | what it does |
|---|---|
| `GET /auth/session` | who is signed in, or 401. Sets the CSRF cookie. |
| `GET /oauth2/authorization/oidc` | starts sign-in (a redirect to the issuer) |
| `POST /auth/logout` | ends the session |

## The five services, and how many endpoints each owns

| service | endpoints | owns |
|---|---|---|
| **Identity** | 48 | People, companies, groups, roles and sign-in. |
| **Catalog** | 38 | What training exists, who it reaches, and what is pinned inside it. |
| **Streaming** | 9 | Playback tokens and watched-interval progress. |
| **Assessment** | 56 | Banks, questions, tests, attempts and marking. |
| **Reporting** | 2 | Telemetry ingest. |
| | **153** | |

## Learner-facing endpoints, all of them

Everything under `/me/` answers **only about the caller** and takes no learner id. There is deliberately no version of any of these that answers about somebody else.

| method | path | service | what it returns |
|---|---|---|---|
| `GET` | `/api/v1/me` | Identity | me |
| `GET` | `/api/v1/me/reach/{resource}/{action}` | Identity | reach |
| `GET` | `/api/v1/users/me/preferences` | Identity | get |
| `PUT` | `/api/v1/users/me/preferences` | Identity | The preferences as they now stand |
| `PUT` | `/api/v1/users/me/timezone` | Identity | move to |
| `GET` | `/api/v1/me/home` | Catalog | home |
| `GET` | `/api/v1/me/nodes/{nodeId}/interstitials` | Catalog | for me |
| `POST` | `/api/v1/me/nodes/{id}/playback-token` | Streaming | A token, where to play it from, and when to come back for the next one. |
| `GET` | `/api/v1/me/nodes/{id}/progress` | Streaming | Where this learner is, and whether this item allows skipping ahead. |
| `POST` | `/api/v1/me/nodes/{id}/progress` | Streaming | The merged coverage after this batch, which is what the player renders. |
| `GET` | `/api/v1/me/attempts/{id}` | Assessment | The attempt, its form and what is saved. |
| `PUT` | `/api/v1/me/attempts/{id}/answers/{formItemId}` | Assessment | Saved. Repeating it is free. |
| `GET` | `/api/v1/me/attempts/{id}/review` | Assessment | The result, and as much of the paper as the policy permits. `visibility` says which — a screen renders the shape it names rather than inferring one from which fields happen to be null. Answer keys and feedback are absent from the payload unless the policy is FULL and its timing has opened. |
| `POST` | `/api/v1/me/attempts/{id}/signals` | Assessment | Taken. Also the answer when it was dropped, which the client does not need to distinguish. |
| `POST` | `/api/v1/me/attempts/{id}/submit` | Assessment | The attempt, ended. Submitting again returns the same thing and changes nothing. A submit after the deadline is still accepted and the attempt is marked EXPIRED — nothing in it was written late, because the save path refuses that. |
| `GET` | `/api/v1/me/monitoring` | Assessment | The integrity signals this platform records during an attempt, what a person may do with them, what nothing does with them, and how long they are kept. Generated from the same values the recorder accepts, so a signal cannot be collected without appearing here. |
| `GET` | `/api/v1/me/tests/{testId}/attempts` | Assessment | history |
| `POST` | `/api/v1/me/tests/{testId}/attempts` | Assessment | The attempt and the form assembled for it. A second call while one is open resumes it rather than starting another, and does not move the deadline. |

## Identity

_People, companies, groups, roles and sign-in._

| method | path | what it returns | refusals |
|---|---|---|---|
| `GET` | `/api/v1/assignments` | all | 403 |
| `POST` | `/api/v1/assignments` | grant | 403 |
| `DELETE` | `/api/v1/assignments/{id}` | revoke | 403 |
| `GET` | `/api/v1/auth-info` | auth info | 403 |
| `POST` | `/api/v1/auth/discovery` | discover | 403 |
| `GET` | `/api/v1/groups` | roots | 403 |
| `POST` | `/api/v1/groups` | create | 403 |
| `DELETE` | `/api/v1/groups/{id}` | delete | 403 |
| `GET` | `/api/v1/groups/{id}/children` | children | 403 |
| `POST` | `/api/v1/groups/{id}/members/{userId}` | add member | 403 |
| `DELETE` | `/api/v1/groups/{id}/members/{userId}` | remove member | 403 |
| `PUT` | `/api/v1/groups/{id}/parent` | move | 403 |
| `GET` | `/api/v1/groups/{id}/reach` | reach | 403 |
| `GET` | `/api/v1/impersonations` | all | 403 |
| `GET` | `/api/v1/internal/whoami` | whoami | 403 |
| `GET` | `/api/v1/me` | me | 403 |
| `GET` | `/api/v1/me/reach/{resource}/{action}` | reach | 403 |
| `GET` | `/api/v1/platform/impersonations` | mine | 403 |
| `POST` | `/api/v1/platform/impersonations` | start | 403 |
| `DELETE` | `/api/v1/platform/impersonations/{sessionId}` | end | 403 |
| `GET` | `/api/v1/platform/tenants` | all | 403 |
| `POST` | `/api/v1/platform/tenants` | provision | 403 |
| `PUT` | `/api/v1/platform/tenants/{tenantId}/status` | set status | 403 |
| `GET` | `/api/v1/roles` | all | 403 |
| `POST` | `/api/v1/roles` | create | 403 |
| `GET` | `/api/v1/roles/{id}` | get | 403 |
| `PUT` | `/api/v1/roles/{id}` | rename | 403 |
| `DELETE` | `/api/v1/roles/{id}` | delete | 403 |
| `POST` | `/api/v1/roles/{id}/clone` | clone | 403 |
| `PUT` | `/api/v1/roles/{id}/permissions` | set permissions | 403 |
| `GET` | `/api/v1/roles/{roleId}/assignments` | of role | 403 |
| `GET` | `/api/v1/sso/domains` | domains | 403 |
| `POST` | `/api/v1/sso/domains` | claim | 403 |
| `POST` | `/api/v1/sso/domains/{id}/verify` | verify | 403 |
| `GET` | `/api/v1/sso/providers` | providers | 403 |
| `POST` | `/api/v1/sso/providers` | register | 403 |
| `DELETE` | `/api/v1/sso/providers/{alias}` | unregister | 403 |
| `POST` | `/api/v1/users/import` | import users | 403 |
| `POST` | `/api/v1/users/invitations` | invite | 403 |
| `POST` | `/api/v1/users/invitations/accept` | accept | 403 |
| `GET` | `/api/v1/users/me/preferences` | get | 403 |
| `PUT` | `/api/v1/users/me/preferences` | The preferences as they now stand | 400, 403 |
| `PUT` | `/api/v1/users/me/timezone` | move to | 403 |
| `GET` | `/api/v1/users/{id}` | user | 403 |
| `PUT` | `/api/v1/users/{id}` | update | 403 |
| `POST` | `/api/v1/users/{id}/deactivate` | deactivate | 403 |
| `POST` | `/api/v1/users/{id}/reactivate` | reactivate | 403 |
| `GET` | `/api/v1/users/{id}/status` | status | 403 |

## Catalog

_What training exists, who it reaches, and what is pinned inside it._

| method | path | what it returns | refusals |
|---|---|---|---|
| `GET` | `/api/v1/assignments` | all | 403 |
| `POST` | `/api/v1/assignments` | assign | 403 |
| `POST` | `/api/v1/assignments/bulk` | assign all | 403 |
| `GET` | `/api/v1/assignments/of/{learnerId}` | obligations | 403 |
| `DELETE` | `/api/v1/assignments/{assignmentId}` | revoke | 403 |
| `GET` | `/api/v1/assignments/{assignmentId}/cycles` | cycles | 403 |
| `GET` | `/api/v1/content-items` | search | 403 |
| `POST` | `/api/v1/content-items` | create | 403 |
| `GET` | `/api/v1/content-items/types` | types | 403 |
| `GET` | `/api/v1/content-items/{id}` | item | 403 |
| `PUT` | `/api/v1/content-items/{id}` | update | 403 |
| `PUT` | `/api/v1/content-items/{id}/state` | state | 403 |
| `GET` | `/api/v1/courses` | all | 403 |
| `POST` | `/api/v1/courses` | create | 403 |
| `POST` | `/api/v1/courses/modules/{moduleId}/nodes` | add node | 403 |
| `PUT` | `/api/v1/courses/modules/{moduleId}/position` | move module | 403 |
| `POST` | `/api/v1/courses/modules/{moduleId}/rebalance` | rebalance | 403 |
| `PUT` | `/api/v1/courses/nodes/{nodeId}/position` | move node | 403 |
| `PUT` | `/api/v1/courses/nodes/{nodeId}/required` | set required | 403 |
| `GET` | `/api/v1/courses/{courseId}` | tree | 403 |
| `GET` | `/api/v1/courses/{courseId}/gates/{targetPart}/{targetId}` | rule | 403 |
| `PUT` | `/api/v1/courses/{courseId}/gates/{targetPart}/{targetId}` | save | 403 |
| `DELETE` | `/api/v1/courses/{courseId}/gates/{targetPart}/{targetId}` | remove | 403 |
| `POST` | `/api/v1/courses/{courseId}/modules` | add module | 403 |
| `GET` | `/api/v1/courses/{courseId}/reachability` | reachability | 403 |
| `GET` | `/api/v1/courses/{courseId}/versions` | all | 403 |
| `POST` | `/api/v1/courses/{courseId}/versions` | publish | 403 |
| `GET` | `/api/v1/courses/{courseId}/versions/diff` | diff | 403 |
| `POST` | `/api/v1/courses/{courseId}/versions/migrate` | migrate | 403 |
| `GET` | `/api/v1/courses/{courseId}/versions/migration-cost` | migration cost | 403 |
| `GET` | `/api/v1/internal/whoami` | whoami | 403 |
| `PATCH` | `/api/v1/interstitials/{id}` | edit | 403 |
| `DELETE` | `/api/v1/interstitials/{id}` | remove | 403 |
| `GET` | `/api/v1/me/home` | home | 403 |
| `GET` | `/api/v1/me/nodes/{nodeId}/interstitials` | for me | 403 |
| `GET` | `/api/v1/nodes/{nodeId}/interstitials` | on | 403 |
| `POST` | `/api/v1/nodes/{nodeId}/interstitials` | add | 403 |
| `GET` | `/api/v1/reminders/unsent` | unsent | 403 |

## Streaming

_Playback tokens and watched-interval progress._

| method | path | what it returns | refusals |
|---|---|---|---|
| `GET` | `/api/v1/internal/whoami` | whoami | 403 |
| `POST` | `/api/v1/me/nodes/{id}/playback-token` | A token, where to play it from, and when to come back for the next one. | 403, 404, 409, 429 |
| `GET` | `/api/v1/me/nodes/{id}/progress` | Where this learner is, and whether this item allows skipping ahead. | 403, 503 |
| `POST` | `/api/v1/me/nodes/{id}/progress` | The merged coverage after this batch, which is what the player renders. | 400, 403, 409, 413, 503 |
| `POST` | `/api/v1/videos` | create | 403 |
| `GET` | `/api/v1/videos/{id}` | video | 403 |
| `DELETE` | `/api/v1/videos/{id}` | The deletion was accepted; the video no longer plays | 400, 403, 404 |
| `POST` | `/api/v1/videos/{id}/upload-target` | reissue | 403 |
| `POST` | `/webhooks/media` | receive | — |

## Assessment

_Banks, questions, tests, attempts and marking._

| method | path | what it returns | refusals |
|---|---|---|---|
| `GET` | `/api/v1/banks` | list | 403 |
| `POST` | `/api/v1/banks` | The bank that was created | 403, 409 |
| `GET` | `/api/v1/banks/{bankId}/questions` | The bank's questions, retired ones excluded | 403, 404 |
| `POST` | `/api/v1/banks/{bankId}/questions` | The question, on its first version | 403, 404 |
| `GET` | `/api/v1/banks/{id}` | The bank | 403, 404 |
| `PUT` | `/api/v1/banks/{id}` | The bank as it now is | 403, 404, 409 |
| `POST` | `/api/v1/grading/attempts/{attemptId}/answers/{responseId}` | The attempt as it now stands. It settles when nothing is left outstanding. | 400, 403, 404, 409 |
| `GET` | `/api/v1/grading/attempts/{attemptId}/history` | history | 403 |
| `GET` | `/api/v1/grading/attempts/{attemptId}/marks` | marks | 403 |
| `GET` | `/api/v1/grading/attempts/{attemptId}/signals` | What the learner's browser reported during the attempt, in the order it reached us. Self-reported telemetry from a page the learner controls: it corroborates a human's suspicion and is not evidence, and an ABSENCE of signals means nothing at all. | 403 |
| `GET` | `/api/v1/grading/questions/{questionId}/rubric` | rubric | 403 |
| `POST` | `/api/v1/grading/questions/{questionId}/rubric` | add criterion | 403 |
| `GET` | `/api/v1/grading/queue` | Attempts waiting for a person, oldest first, with how long each has waited. | 403 |
| `GET` | `/api/v1/grading/queue/depth` | depth | 403 |
| `GET` | `/api/v1/internal/whoami` | whoami | 403 |
| `GET` | `/api/v1/me/attempts/{id}` | The attempt, its form and what is saved. | 403, 404 |
| `PUT` | `/api/v1/me/attempts/{id}/answers/{formItemId}` | Saved. Repeating it is free. | 400, 403, 404, 409 |
| `GET` | `/api/v1/me/attempts/{id}/review` | The result, and as much of the paper as the policy permits. `visibility` says which — a screen renders the shape it names rather than inferring one from which fields happen to be null. Answer keys and feedback are absent from the payload unless the policy is FULL and its timing has opened. | 403, 404 |
| `POST` | `/api/v1/me/attempts/{id}/signals` | Taken. Also the answer when it was dropped, which the client does not need to distinguish. | 400, 403, 404 |
| `POST` | `/api/v1/me/attempts/{id}/submit` | The attempt, ended. Submitting again returns the same thing and changes nothing. A submit after the deadline is still accepted and the attempt is marked EXPIRED — nothing in it was written late, because the save path refuses that. | 403, 404 |
| `GET` | `/api/v1/me/monitoring` | The integrity signals this platform records during an attempt, what a person may do with them, what nothing does with them, and how long they are kept. Generated from the same values the recorder accepts, so a signal cannot be collected without appearing here. | 403 |
| `GET` | `/api/v1/me/tests/{testId}/attempts` | history | 403 |
| `POST` | `/api/v1/me/tests/{testId}/attempts` | The attempt and the form assembled for it. A second call while one is open resumes it rather than starting another, and does not move the deadline. | 403, 404, 409 |
| `GET` | `/api/v1/questions/{id}` | The question and its current version | 403, 404 |
| `PUT` | `/api/v1/questions/{id}` | The question as it now is | 403, 404 |
| `DELETE` | `/api/v1/questions/{id}` | Whether the question was retired or deleted | 403, 404 |
| `PUT` | `/api/v1/questions/{id}/bank` | The question, now in the other bank | 403, 404 |
| `GET` | `/api/v1/questions/{id}/description` | What a draw filters this question on | 403, 404 |
| `PUT` | `/api/v1/questions/{id}/description` | The question's draw attributes as they now are | 400, 403, 404 |
| `GET` | `/api/v1/questions/{id}/versions` | Every version, newest first | 403, 404 |
| `GET` | `/api/v1/questions/{id}/versions/{versionId}` | The version, as it was served | 403, 404 |
| `GET` | `/api/v1/questions/{id}/versions/{versionId}/authoring` | The version in full, including the answer key. The endpoint a permission goes on; until grants travel between services, nothing checks one. | 403, 404 |
| `DELETE` | `/api/v1/sections/{id}` | Removed. | 403, 409 |
| `GET` | `/api/v1/sections/{id}/pool` | How many questions this section could draw from right now, against what it asks for. The same predicate the draw uses. | 400, 403 |
| `PUT` | `/api/v1/sections/{id}/pool` | The section as it now stands. | 403, 409 |
| `PUT` | `/api/v1/sections/{id}/position` | move | 403 |
| `PUT` | `/api/v1/sections/{id}/questions` | The section as it now stands. | 400, 403 |
| `PUT` | `/api/v1/sections/{id}/scoring` | scoring | 403 |
| `PUT` | `/api/v1/sections/{id}/shuffle` | shuffle | 403 |
| `PUT` | `/api/v1/sections/{id}/weight` | weight | 403 |
| `GET` | `/api/v1/shared-banks` | shared | 403 |
| `POST` | `/api/v1/shared-banks/{id}/copies` | The independent copy, owned by this company | 403, 404, 409 |
| `GET` | `/api/v1/tests` | all | 403 |
| `POST` | `/api/v1/tests` | Created. | 400, 403 |
| `GET` | `/api/v1/tests/{id}` | The test and its scoring policy. | 403, 404 |
| `PUT` | `/api/v1/tests/{id}` | rename | 403 |
| `DELETE` | `/api/v1/tests/{id}` | delete | 403 |
| `PUT` | `/api/v1/tests/{id}/review` | The policy as it now stands. | 400, 403, 404 |
| `PUT` | `/api/v1/tests/{id}/scoring` | The policy as it now stands. | 400, 403, 404 |
| `PUT` | `/api/v1/tests/{id}/sitting` | The policy as it now stands. | 400, 403, 404 |
| `GET` | `/api/v1/tests/{testId}/sections` | of | 403 |
| `POST` | `/api/v1/tests/{testId}/sections` | Created, at the end of the test. | 400, 403 |
| `GET` | `/api/v1/vocabulary/difficulties` | difficulties | 403 |
| `POST` | `/api/v1/vocabulary/difficulties` | The difficulty level that was added | 403, 409 |
| `GET` | `/api/v1/vocabulary/tags` | tags | 403 |
| `POST` | `/api/v1/vocabulary/tags` | The tag that was added | 403, 409 |

## Reporting

_Telemetry ingest._

| method | path | what it returns | refusals |
|---|---|---|---|
| `GET` | `/api/v1/internal/whoami` | whoami | 403 |
| `POST` | `/api/v1/telemetry/playback` | playback | 400, 403, 413 |

