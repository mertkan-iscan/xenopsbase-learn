/**
 * Every sentence this application says, in English.
 *
 * <p>THIS FILE IS THE SOURCE, and the other catalogue is typed against it: `messages.tr.ts`
 * declares `Record<MessageKey, string>`, so adding a key here without adding it there is a
 * COMPILE ERROR rather than a screen that quietly falls back to English for one label. That is the
 * same property `IntegritySignal` relies on in the backend — a thing cannot be shipped without the
 * words that explain it.
 *
 * <h2>Whole sentences, never fragments</h2>
 *
 * There is no `loading.prefix` + a noun, no verb + a title, no "and" joined between clauses. A
 * sentence assembled from parts is a sentence assembled in English word order: Turkish puts the
 * verb last and inflects the noun before it, so `"Loading " + "your training"` cannot be
 * translated at all — only rewritten. Every entry below is therefore something a person could read
 * aloud, with `{placeholders}` only where a NUMBER or a NAME goes.
 *
 * <h2>Plurals</h2>
 *
 * Keys ending `.one` / `.other` are selected by `Intl.PluralRules` (see `t.ts`). English uses both
 * categories; Turkish has only `other` in CLDR, so its two entries are deliberately identical —
 * which states that fact rather than hiding it behind machinery.
 */
export const en = {
  // --------------------------------------------------------------------------------- the shell
  'shell.skip': 'Skip to content',
  'shell.nav': 'Main',
  'shell.tenant.console': 'Console',
  'shell.tenant.learner': 'Your training',
  'shell.nav.authoring': 'Authoring',
  'shell.nav.assign': 'Assign',
  'shell.nav.grading': 'Marking',
  'shell.nav.people': 'Users',
  'shell.nav.roles': 'Roles',
  'shell.nav.compliance': 'Reports',
  'shell.to-console': 'Console',
  'shell.to-learner': 'Your training',
  'shell.sign-out': 'Sign out',
  'shell.sign-in': 'Sign in',
  'shell.tab.training': 'Training',
  'shell.tab.progress': 'Progress',
  'shell.tab.discover': 'Discover',
  'shell.product': 'XenOpsBase Learn',
  'shell.menu': 'Menu',
  'shell.notifications': 'Notifications',
  'shell.notifications.none': 'You have no notifications yet.',

  // What a signed-out person is told, and it is never the same sentence twice.
  'signed-out.parked.title': 'Your work is saved',
  'signed-out.parked.body':
    'Your session ended before this could be sent. Nothing was lost — sign in again and it will be submitted for you.',
  'signed-out.deliberate.title': 'You are signed out',
  'signed-out.deliberate.body': 'You have been signed out. Sign in again whenever you need to.',
  'signed-out.failed.title': 'You are signed out',
  'signed-out.failed.body':
    'Signing in did not complete. Try again, and if it keeps happening tell whoever administers your training.',

  // ------------------------------------------------------------------------------ the loading
  // One whole sentence per thing that can be waited for. See the header: this is the entry that
  // most obviously cannot be a prefix plus a noun.
  'loading.label': 'Loading',
  'loading.session': 'Loading your session…',
  'loading.sign-in': 'Opening the sign-in page…',
  'loading.training': 'Loading your training…',
  'loading.progress': 'Loading your progress…',
  'loading.video': 'Loading the video…',
  'loading.test': 'Loading your test…',
  'loading.result': 'Loading your result…',
  'loading.console': 'Loading the console…',
  'loading.roles': 'Loading roles…',
  'loading.role-editor': 'Loading the role editor…',
  'loading.course': 'Loading the course…',
  'loading.courses': 'Loading your courses…',
  'loading.assignments': 'Loading assignments…',
  'loading.marking': 'Loading the marking queue…',
  'loading.report': 'Loading the report…',

  // ------------------------------------------------------------------------- failed and empty
  'error.label': 'Something went wrong',
  'error.reassurance': 'Your progress is saved. Nothing was lost.',
  'error.retry': 'Try again',
  'empty.label': 'Nothing here',

  // The API's own failures, said once, in `shared/api/client.ts`.
  'api.unreachable': 'Could not reach the service.',
  'api.unreachable.detail': 'Could not reach the service: {reason}',
  'api.session-ended': 'Your session has ended. Sign in again to continue.',
  'api.forbidden': 'You are signed in, but this is not yours to do.',
  // A 404 may mean "not yours to know about" — never "it was deleted" (docs/design-prompt.md, 2).
  'api.not-found': 'Not found, or not visible to you.',
  'api.status': 'The service answered {status}.',

  // ----------------------------------------------------------------------- the eight states
  'state.due': 'Due',
  'state.overdue': 'Overdue',
  'state.in-progress': 'In progress',
  'state.locked': 'Locked',
  'state.awaiting': 'Awaiting grading',
  'state.passed': 'Passed',
  'state.not-passed': 'Not passed',
  'state.draft': 'Draft',
  'state.published': 'Published',

  // ------------------------------------------------------------------------------------ home
  'home.title': 'Your training',
  'home.welcome': 'Welcome back',
  'home.welcome.named': 'Welcome back, {name}',
  'home.continue': 'Continue learning',
  'home.at-a-glance': 'At a glance',
  'home.stat.assigned': 'Assigned',
  'home.stat.in-progress': 'In progress',
  'home.stat.completed': 'Completed',
  'home.stat.due-soon': 'Due soon',
  'home.stat.overdue': 'Overdue',
  'home.see-all': 'See all',
  'home.done.title': 'You are up to date.',
  'home.done.body':
    'Everything assigned to you is finished. New training appears here when somebody assigns it.',
  'discover.title': 'Discover',
  'discover.search': 'Search your training',
  'discover.search.hint': 'Searches the courses assigned to you.',
  'discover.filter': 'Show',
  'discover.filter.all': 'Everything',
  'discover.filter.in-progress': 'In progress',
  'discover.filter.due-soon': 'Due soon',
  'discover.filter.overdue': 'Overdue',
  'discover.filter.complete': 'Completed',
  'discover.filter.locked': 'Has locked items',
  'discover.count.one': '{count} course',
  'discover.count.other': '{count} courses',
  'discover.modules.one': '{count} module',
  'discover.modules.other': '{count} modules',
  'discover.empty.title': 'No course matches that.',
  'discover.empty.body': 'Try a different word, or clear the filter.',
  'discover.clear': 'Clear the filter',
  'discover.open': 'Open',
  'discover.nothing-open': 'Nothing in this course is open yet',
  'home.due': 'Due',
  'home.in-progress': 'In progress',
  'home.next': 'Next',
  'home.courses': 'Your courses',
  'home.start': 'Start',
  'home.resume': 'Resume',
  'home.resume-at': 'Resume at {at}',
  'home.stopped-at': 'You stopped at {at}',
  'home.progress-label': '{course}, {percent} per cent complete',
  'home.this-course': 'This course',
  'home.complete': 'complete',
  'home.empty.title': 'Nothing is assigned to you.',
  'home.empty.body': 'When your manager assigns training it appears here. Nothing to do today.',
  'home.locked.fallback-title': 'The next item',
  'home.locked.fallback-reason': 'It unlocks when the item before it is finished.',
  'home.summary': '{assigned} assigned · {completed} completed · {overdue} overdue',

  // -------------------------------------------------------------------------------- progress
  'progress.title': 'Your progress',
  'progress.empty.title': 'Nothing has been assigned to you yet.',
  'progress.empty.body': 'There is nothing to show progress against. Nothing to do today.',
  'progress.assigned': 'Assigned',
  'progress.completed': 'Completed',
  'progress.in-progress': 'In progress',
  'progress.due-soon': 'Due soon',
  'progress.overdue': 'Overdue',
  'progress.per-course': 'By course',
  'progress.course': 'Course',
  'progress.done': 'Done',
  'review.your-test': 'Your result',
  'review.question-number': 'Q{n}',
  'review.submitted': 'Submitted {at}.',
  'review.attempt-number': 'Attempt {n}.',
  'review.just-now': 'a moment ago',
  'review.outcome.correct': 'Correct',
  'review.outcome.not-correct': 'Not correct',
  'review.outcome.scored': '{awarded} of {points}',

  // ------------------------------------------------------------------------------- questions
  'question.your-answer': 'Your answer',
  // true-false has no choices in the body — these two are ours, so they are translated here
  // rather than arriving from the server with the rest of the question.
  'question.true': 'True',
  'question.false': 'False',
  'question.unsupported':
    'This question is a {type}, which this screen cannot show yet. Leave it and tell whoever set the test.',
  'question.move-earlier': 'Move {item} earlier',
  'question.move-later': 'Move {item} later',

  // ------------------------------------------------------------------ what is not enforced yet
  'not-enforced.label': 'Not restricted yet',
  'not-enforced.body':
    'Catalog and assessment do not check permissions (T-9.11). Anyone signed in to this company can author, assign and grade here, whatever roles they hold.',

  // ------------------------------------------------------------------------------ compliance
  'compliance.empty.title': 'There is no reporting to show.',
  'compliance.empty.body':
    'The {service} service accepts telemetry and cannot answer a query yet. The rollups this screen is built on are T-7.1 to T-7.7.',
  'compliance.empty.note':
    'Nothing is hidden here and nothing is loading. This screen had sample figures for a company of {count} people; they were invented, so they are gone.',

  // ------------------------------------------------------------------------- the item shell
  'item.back': 'Back',
  'item.position': '{position} of {of}',
  'item.is-a': 'This item is a {type}',
  'item.type.video': 'Video',
  'item.type.scorm': 'SCORM',
  'item.type.slides': 'Slides',
  'item.type.test': 'Test',
  'item.one-shell': 'one shell',
  'item.type.cmi5': 'cmi5',
  'item.type.unknown': 'Item',
  'item.tabs': 'This item',
  'item.tab.overview': 'Overview',
  'item.tab.questions': 'Questions',
  'item.tab.syllabus': 'Contents',
  'item.syllabus': 'Course contents',
  'item.syllabus.hide': 'Hide',
  'item.syllabus.show': 'Show',
  'item.overview.progress': 'Watched',
  'item.overview.state': 'State',
  'item.overview.required': 'Obligation',
  'item.overview.is-required': 'This item is required to complete the course.',
  'item.overview.is-optional': 'This item is optional.',
  'item.not-in-your-training': 'This item is not in the training assigned to you.',
  'item.not-in-your-training.body':
    'It may have been unassigned, or the link may be out of date. Nothing has been lost.',
  'watch.questions.none': 'No questions are pinned in this video.',
  'watch.questions.none.body': 'It plays straight through.',
  'watch.questions.lead': 'These questions appear while the video plays.',
  'watch.answered': 'answered',

  'interstitial.bar': 'Question in the video · 1 of 1',
  'interstitial.foot':
    'Answer to carry on. The video resumes at {at} — your place is held on the server, not in this tab.',
  'interstitial.answer': 'Answer and continue',
  // Leaving it blank is an option and it is a button: an empty response is an unanswered
  // question, not an error (docs/design-prompt.md, 6).
  'answer.leave-blank': 'I don’t know — leave it blank',

  // ----------------------------------------------------------------------------------- watch
  'watch.no-node': 'No video was named in the address.',
  'watch.pinned-title': 'Questions in this video',
  'watch.frontier': 'The video plays to {at} and waits there for the question.',
  'watch.blocking': 'you must answer this to carry on',

  // ---------------------------------------------------------------------------------- review
  'review.pass-mark': 'Pass mark {mark}. Submitted {at}.',
  'review.score-only':
    'This test shows your score only. The questions and answers are not released — that is this course’s policy, not a fault.',
  'review.attempts': 'Attempts',
  'review.attempts-used': '{used} of {allowed} used.',
  'review.no-more-needed': 'You do not need another.',
  // A plural family, and the reason one exists at all. This used to be
  // `n === 1 ? 'One answer is' : `${n} answers are`` + ' with a person' — an English agreement
  // rule, applied by hand, with the verb spliced in front of a fragment. Turkish has no plural
  // agreement after a numeral and puts the verb last, so the whole sentence is the message.
  'review.with-a-person.one': 'One answer is with a person',
  'review.with-a-person.other': '{count} answers are with a person',
  'review.awaiting-body':
    'Everything marked automatically is right so far. There is no result yet — not a pass, not a fail. You do not need to do anything.',
  'review.with-a-marker': 'With a marker',
  'review.marking-time': 'Marking usually finishes within two working days. We will let you know here.',

  // ----------------------------------------------------------------------- sitting a test
  'sit.submitted.title': 'Submitted',
  'sit.submitted.body': 'Your answers are in. Nothing else is needed from you.',
  'sit.see-result': 'See your result',
  'sit.where': 'Question {position} of {of}',
  'sit.not-answered': '{count} not answered',
  'sit.all-answered': 'all answered',
  'sit.back': 'Back',
  'sit.next': 'Next',
  'sit.all-answered-note': 'Every question has an answer.',
  // "N of M is/are still blank" used to pick the verb with `n === 1 ? 'is' : 'are'` and splice it
  // between two numbers. Turkish has neither the agreement nor the word order, so the whole
  // sentence is the message and the two Turkish forms are deliberately identical.
  'sit.blank-note.one': '{count} of {of} is still blank. You can submit anyway.',
  'sit.blank-note.other': '{count} of {of} are still blank. You can submit anyway.',
  'sit.submit': 'Submit',
  'sit.no-time-limit': 'No time limit',
  'sit.time-up': 'Time is up',
  'sit.time-left': '{at} left',
  'sit.disclosure.title': 'Before you start',
  'sit.disclosure.we-record': 'While you sit this test we record:',
  'sit.disclosure.nothing': 'Nothing about how you sit this test is recorded.',
  'sit.disclosure.kept.one': 'Kept for {count} day, then deleted.',
  'sit.disclosure.kept.other': 'Kept for {count} days, then deleted.',
  'sit.disclosure.start': 'Start the test',
  'sit.disclosure.not-now': 'Not now',

  // ---------------------------------------------------------------------------------- people
  'people.title': 'People',
  'people.email': 'Email address',
  'people.name': 'Display name',
  'people.inviting': 'Inviting…',
  'people.invite': 'Invite',
  'people.invited': 'Invited {name} ({email}). The invitation expires {at}.',
  'people.once.title': 'This link is shown once.',
  'people.once.body':
    'We keep only a hash of it, so it cannot be looked up again — send it now, or invite them again to issue a new one.',

  // --------------------------------------------------------------------------------- marking
  'grading.empty.title': 'Nothing is waiting to be marked.',
  'grading.empty.body':
    'Written answers and uploaded files arrive here when a learner submits. Everything a machine can mark is already marked.',
  'grading.queue.title': 'Waiting on a person',
  'grading.queue.note':
    'Nothing here is claimed — two markers can open the same attempt, so check before you start a long one.',
  'grading.col.test': 'Test',
  'grading.col.attempt': 'Attempt',
  'grading.col.outstanding': 'Outstanding',
  'grading.col.waiting': 'Waiting',
  'grading.mark': 'Mark',
  'grading.attempt-heading': '{test} · attempt {number}',
  'grading.still-waiting': 'Still waiting on the answers below.',
  // Two keys rather than one with a `{outcome}` hole in it. "passed" and "not passed" are the
  // verdict, and a verdict spliced into a sentence is a verdict that cannot be reordered — Turkish
  // ends the clause with it either way, but a language that does not would be stuck.
  'grading.verdict.passed': '{percent} — passed',
  'grading.verdict.not-passed': '{percent} — not passed',
  'grading.marked': 'Marked',
  'grading.not-marked': 'Not marked',
  'grading.out-of': 'out of {available}',
  'grading.awarded': '{awarded} of {available}',
  'grading.award': 'Award',
  'grading.comment': 'Comment for the learner',
  'grading.range': '0 – {available}',

  // ------------------------------------------------------------------------------ assignment
  'assign.heading': 'Assign a course',
  'assign.course': 'Course',
  'assign.choose': 'Choose…',
  'assign.to': 'To',
  'assign.target.user': 'One person',
  'assign.target.group': 'A group',
  'assign.target.tenant': 'The whole company',
  'assign.learner-id': 'Learner id',
  'assign.group-id': 'Group id',
  'assign.id-note':
    'An id, because identity has no endpoint that lists people or group members yet.',
  'assign.due-on': 'Due on',
  'assign.due-note':
    'A due date cannot be changed afterwards — catalog has no update for an assignment. Changing it means revoking this one and assigning again.',
  'assign.no-identity':
    'Your account has no identity in this company, so nothing can be assigned as you.',
  // The reach, said before the confirm. Assigning to a company is thousands of obligations with no
  // undo beyond revoking each one, so these three are the most load-bearing phrases on the screen.
  'assign.reach.tenant': 'every active learner in this company',
  'assign.reach.group': 'everyone in that group, including every group beneath it',
  'assign.reach.user': 'one person',
  'assign.confirm': 'This assigns {course} to {reach}.',
  'assign.confirm.yes': 'Yes, assign it',
  'assign.confirm.cancel': 'Cancel',
  'assign.open': 'Assign…',
  'assign.empty.title': 'Nothing is assigned in this company.',
  'assign.empty.body': 'Until something is, every learner’s home screen is empty.',
  'assign.existing': 'Assigned',
  'assign.col.pinned': 'Pinned version',
  'assign.to.tenant': 'the whole company',
  // The same word in both languages, and it goes through the catalogue anyway: a placeholder
  // left as a literal is indistinguishable from one nobody got to.
  'assign.uuid': 'uuid',
  'assign.to.user': 'user {id}',
  'assign.to.group': 'group {id}',
  'assign.revoke': 'Revoke',
  'assign.behind': 'behind',

  // ------------------------------------------------------------------------------- authoring
  'authoring.no-course.title': 'No course open.',
  'authoring.no-course.body': 'Choose one on the left, or create the first.',
  'authoring.courses': 'Courses',
  'authoring.none-yet': 'None yet. The first one is below.',
  'authoring.new-course': 'New course',
  'authoring.new-course.placeholder': 'Fire Safety Refresher',
  'authoring.no-rename': 'A course cannot be renamed once created.',
  'authoring.create': 'Create',
  'authoring.publish-version': 'Publish a version',
  'authoring.version': 'Version {version}',
  'authoring.published': 'Published',
  'authoring.no-modules.title': 'This course has no modules yet.',
  'authoring.no-modules.body': 'A module holds the ordered nodes a learner walks through.',
  'authoring.required': 'required',
  'authoring.optional': 'optional',
  'authoring.drafts.title': 'Not published yet',
  'authoring.drafts.body':
    'A course will not reference a draft. Publish it here and it becomes available as a node.',
  'authoring.publish': 'Publish',
  'authoring.add-module': 'Add a module',
  'authoring.add-module.placeholder': 'Module 1 · Getting started',
  'authoring.add': 'Add',
  'authoring.add-node': 'Add a node',
  'authoring.choose-content': 'Choose content…',
  'authoring.new-item': 'New content item',
  'authoring.type': 'Type',
  'authoring.choose': 'Choose…',
  'authoring.title': 'Title',
  'authoring.reference': 'reference',
  'authoring.reference.placeholder': 'the id this item points at',
  'authoring.points-at':
    'Content points at something rather than holding it: a video’s bytes live in streaming, a test lives in assessment.',

  // ----------------------------------------------------------------------------------- roles
  'roles.half-missing.label': 'Half of this is missing',
  'roles.half-missing.body':
    'Roles are real. The list of permissions to choose from is not published by any endpoint — it lives in identity’s {enum} enum and reaches integrators only as a table in the API description. Codes are typed here and validated by the server.',
  'roles.title': 'Roles',
  'roles.empty.title': 'This company has no roles.',
  'roles.empty.body': 'The seeded ones arrive with the tenant.',
  'roles.system': 'system',
  'roles.permission-count.one': '{count} permission',
  'roles.permission-count.other': '{count} permissions',
  'roles.holds-nothing': 'This role holds nothing, so it grants nothing.',
  'roles.remove': 'Remove',
  'roles.seeded': 'A seeded role. Clone it rather than changing what every tenant gets.',
  'roles.add-permission': 'Add a permission',
  'roles.add': 'Add',
  // The FORMAT of a permission code, not a sentence: a person types `user:read` here, and the
  // words either side of the colon are the API's. Identical in both catalogues on purpose.
  'roles.code-placeholder': 'resource:action',

  // ---------------------------------------------------------------------------- the player
  // The player is a separate document in a customer's iframe, so its language arrives in its
  // URL like everything else it needs. These are the only two sentences it says itself.
  'player.untitled': 'Video',
  'player.no-node': 'This player was opened without a video to play.',
} as const;

/** Every key in the catalogue. The other locales are typed against exactly this set. */
export type MessageKey = keyof typeof en;

/**
 * The subset naming a whole "we are fetching X" sentence.
 *
 * <p>`Loading` takes one of these rather than a noun, which is what stops the sentence being
 * assembled at the call site in English word order.
 */
export type LoadingKey = Extract<MessageKey, `loading.${string}`>;

/**
 * The families that have a count-sensitive form: `x`, where `x.other` exists.
 *
 * <p>Derived rather than listed, so `plural('review.with-a-person', n)` is only accepted for a key
 * that actually has the variants — the mistake this catches is asking for a plural of a sentence
 * that has only one form, which would otherwise fall back silently and read fine in English.
 */
type WithoutOther<K> = K extends `${infer Family}.other` ? Family : never;
export type PluralKey = WithoutOther<MessageKey>;
