/**
 * A backend, for looking at the design.
 *
 * <p><b>Dev-only, and it never ships.</b> `preview.html` is not in `build.rollupOptions.input`
 * (vite.config.ts), so Vite serves it from the project root during `npm run dev` and the
 * production build has no entry that reaches this file.
 *
 * <p>WHAT IT IS FOR: the screens are judged on how they look in states that are tedious or
 * impossible to arrange against a real stack — something overdue, something awaiting a human
 * marker, a locked gate, a course with nothing open — and arranging them by hand each time is how
 * a design gets reviewed in only the state that was easy to reach. Stubbing `fetch` rather than
 * mocking the client keeps every screen's real loading, failed and empty handling in the path.
 *
 * <p>WHAT IT IS NOT: a fixture for tests. The tests render presentational components directly
 * (`HomeScreen`, `DiscoverScreen`) precisely so they do not depend on a table of fake responses
 * that somebody has to keep true. Nothing here is imported by a test.
 *
 * <p>The course titles are Turkish on purpose. They are customer content rather than interface
 * text, so they do not change with the locale — and they are the only thing on the page that
 * proves the chosen typefaces actually carry ğ, ı, İ, ş and ö rather than falling back mid-word.
 */

/*
 * Dates are computed relative to today rather than written down. A fixture with a hard-coded 2026
 * date stops exercising "overdue" the moment the clock passes it, and starts exercising it for
 * everything — which is the same fixture quietly testing a different screen every month.
 */
function inDays(days: number): string {
  const day = new Date();
  day.setDate(day.getDate() + days);
  return day.toISOString().slice(0, 10);
}

const home = {
  state: 'READY',
  generatedAt: new Date().toISOString(),
  summary: { assigned: 5, inProgress: 1, completed: 1, dueSoon: 1, overdue: 1 },
  nextUp: {
    courseId: 'c-kvkk',
    courseTitle: 'Kişisel Verilerin Korunması 2026',
    nodeId: 'n-kvkk-2',
    title: 'Bölüm 2 · Hukuka uygunluk sebepleri',
    percent: 62,
    resumeSecond: 860,
    dueOn: inDays(4),
    overdue: false,
  },
  items: [
    {
      // A CONTENT ITEM, not a node, and that is the point of this entry. The live stack
      // produced exactly this shape and it appeared on no screen: `courses` holds only
      // course-level obligations, so an assigned content item lives here or nowhere.
      referenceId: 'i-yangin',
      referenceType: 'CONTENT_ITEM',
      title: 'Yangın Güvenliği Tazeleme · Tekrar',
      state: 'AVAILABLE',
      percent: 0,
      dueOn: inDays(-6),
      overdue: true,
      cycleNumber: 2,
      sources: ['a-assignment-id'],
    },
  ],
  courses: [
    {
      courseId: 'c-kvkk',
      title: 'Kişisel Verilerin Korunması 2026',
      percentComplete: 62,
      dueOn: inDays(4),
      overdue: false,
      completed: false,
      cycleNumber: 1,
      sources: ['a-assignment-id'],
      modules: [
        {
          moduleId: 'm-kvkk-1',
          title: 'Temel kavramlar',
          locked: false,
          nodes: [
            {
              nodeId: 'n-kvkk-1',
              title: 'Veri sorumlusu kimdir?',
              type: 'video',
              state: 'COMPLETE',
              percent: 100,
              required: true,
            },
            {
              nodeId: 'n-kvkk-2',
              title: 'Bölüm 2 · Hukuka uygunluk sebepleri',
              type: 'video',
              state: 'IN_PROGRESS',
              percent: 62,
              resumeSecond: 860,
              required: true,
            },
          ],
        },
        {
          moduleId: 'm-kvkk-2',
          title: 'Değerlendirme',
          locked: true,
          // THE SENTENCE A LOCKED-OUT LEARNER READS. The server generates it (T-5.3); a padlock
          // without it is a support ticket, which is why the preview always carries one.
          lockedReason: 'Bölüm 2 videosunu bitirdiğinizde açılır.',
          nodes: [
            {
              nodeId: 'n-kvkk-test',
              title: 'Kişisel verilerin işlenmesi · Sınav',
              type: 'test',
              state: 'LOCKED',
              percent: 0,
              required: true,
              // The NODE's sentence, which the server words differently from its module's above.
              lockedReason: 'Değerlendirme bölümü henüz kullanıma açık değil.',
            },
          ],
        },
      ],
    },
    {
      courseId: 'c-yangin',
      title: 'Yangın Güvenliği Tazeleme',
      percentComplete: 0,
      dueOn: inDays(-6),
      overdue: true,
      completed: false,
      cycleNumber: 2,
      sources: ['a-assignment-id'],
      modules: [
        {
          moduleId: 'm-yangin-1',
          title: 'Tahliye',
          locked: false,
          nodes: [
            {
              nodeId: 'n-yangin-1',
              title: 'Tahliye tatbikatı',
              type: 'video',
              state: 'AVAILABLE',
              percent: 0,
              required: true,
            },
          ],
        },
      ],
    },
    {
      courseId: 'c-isg',
      title: 'İş Sağlığı ve Güvenliği Temelleri',
      percentComplete: 100,
      overdue: false,
      completed: true,
      cycleNumber: 1,
      sources: ['a-assignment-id'],
      modules: [
        {
          moduleId: 'm-isg-1',
          title: 'Genel esaslar',
          locked: false,
          nodes: [
            {
              nodeId: 'n-isg-1',
              title: 'Risk değerlendirmesi',
              type: 'slides',
              state: 'COMPLETE',
              percent: 100,
              required: true,
            },
          ],
        },
      ],
    },
    {
      courseId: 'c-bilgi',
      title: 'Bilgi Güvenliği Farkındalığı',
      percentComplete: 0,
      dueOn: inDays(45),
      overdue: false,
      completed: false,
      cycleNumber: 1,
      sources: ['a-assignment-id'],
      modules: [
        {
          moduleId: 'm-bilgi-1',
          title: 'Kimlik avı',
          locked: false,
          nodes: [
            {
              nodeId: 'n-bilgi-1',
              title: 'Şüpheli e-postayı tanımak',
              type: 'scorm',
              state: 'AVAILABLE',
              percent: 0,
              required: true,
            },
          ],
        },
      ],
    },
    {
      // The awkward one, and the reason it is here: a course where nothing is open. Every node is
      // behind a gate, so there is no node to navigate to and the card has to say so rather than
      // offering a button that leads to a player that refuses.
      courseId: 'c-yonetici',
      title: 'Yönetici Sorumlulukları',
      percentComplete: 0,
      dueOn: inDays(20),
      overdue: false,
      completed: false,
      cycleNumber: 1,
      sources: ['a-assignment-id'],
      modules: [
        {
          moduleId: 'm-yonetici-1',
          title: 'Ön koşullar',
          locked: true,
          lockedReason: 'İş Sağlığı ve Güvenliği Temelleri kursunu bitirdiğinizde açılır.',
          nodes: [
            {
              nodeId: 'n-yonetici-1',
              title: 'Yasal çerçeve',
              type: 'video',
              state: 'LOCKED',
              percent: 0,
              required: true,
              lockedReason: 'İş Sağlığı ve Güvenliği Temelleri kursunu bitirdiğinizde açılır.',
            },
          ],
        },
      ],
    },
  ],
};

/**
 * The questions pinned inside one video, keyed by node.
 *
 * <p>Only `n-kvkk-2` has any, deliberately: the other nodes exercise the "this video plays straight
 * through" state, which is the common one and the one nobody remembers to look at.
 *
 * <p>Note what an `InterstitialView` does NOT carry — no question text, no options. That is the
 * real shape (docs/api-surface.md), and it is why the player lists these rather than opening them.
 */
const interstitials: Record<string, unknown> = {
  'n-kvkk-2': {
    nodeId: 'n-kvkk-2',
    frontierSecond: 1_140,
    // One already answered and two not, so the list shows both halves of its own design.
    answered: ['i-1'],
    markers: [
      { id: 'i-2', nodeId: 'n-kvkk-2', questionId: 'q-2', positionSeconds: 1_140, blocking: true, askAgain: false },
      { id: 'i-1', nodeId: 'n-kvkk-2', questionId: 'q-1', positionSeconds: 372, blocking: false, askAgain: false },
      { id: 'i-3', nodeId: 'n-kvkk-2', questionId: 'q-3', positionSeconds: 1_608, blocking: false, askAgain: true },
    ],
  },
};

/*
 * THE CONSOLE'S READS. Enough for each screen to be looked at in the state it is normally in,
 * which for four of the six is "a company that has been set up and is being run" rather than the
 * empty one a fresh stack gives you.
 *
 * The roles are the four every tenant is seeded with (`SystemRole`, T-2.7) plus one a customer
 * built, because the difference matters on screen: a seeded role cannot be edited and says so.
 */
const roles = [
  {
    id: 'r-admin',
    name: 'Şirket yöneticisi',
    description: 'Her şeyi yönetir.',
    system: true,
    permissions: ['user:manage', 'role:manage', 'group:manage', 'course:manage', 'report:read'],
  },
  {
    id: 'r-author',
    name: 'Yazar',
    description: 'Eğitim hazırlar ve yayınlar.',
    system: true,
    permissions: ['course:manage', 'content:view'],
  },
  {
    id: 'r-learner',
    name: 'Öğrenen',
    description: 'Atanan eğitimleri görür.',
    system: true,
    permissions: ['content:view'],
  },
  {
    id: 'r-group-admin',
    name: 'Grup yöneticisi',
    description: 'Yalnızca kendi grubundaki kişileri yönetir.',
    system: true,
    permissions: ['user:read', 'report:read'],
  },
  {
    // The one a customer made. Editable, and the only one whose remove buttons are live.
    id: 'r-audit',
    name: 'Denetim gözlemcisi',
    description: 'Rapor okur, hiçbir şeyi değiştirmez.',
    system: false,
    permissions: ['report:read'],
  },
];

const courses = [
  { id: 'c-kvkk', title: 'Kişisel Verilerin Korunması 2026', state: 'PUBLISHED' },
  { id: 'c-yangin', title: 'Yangın Güvenliği Tazeleme', state: 'PUBLISHED' },
  { id: 'c-yeni', title: 'Yeni Başlayan Oryantasyonu', state: 'DRAFT' },
];

/*
 * A marking queue with rows in it, which is the state this screen exists for and the one that is
 * tedious to arrange for real -- somebody has to sit a test with an essay in it first.
 */
const gradingQueue = [
  {
    attemptId: 'a-1',
    attemptNumber: 1,
    learnerId: 'u-7',
    testId: 't-kvkk',
    testTitle: 'Kişisel verilerin işlenmesi',
    submittedAt: new Date(Date.now() - 3_600_000 * 26).toISOString(),
    outstanding: 2,
    waitingSeconds: 3_600 * 26,
  },
  {
    attemptId: 'a-2',
    attemptNumber: 2,
    learnerId: 'u-9',
    testId: 't-yangin',
    testTitle: 'Yangın Güvenliği',
    submittedAt: new Date(Date.now() - 3_600_000 * 3).toISOString(),
    outstanding: 1,
    waitingSeconds: 3_600 * 3,
  },
];

const answers: Record<string, unknown> = {
  '/auth/session': { signedIn: true, name: 'Ayşe Demir', signInUrl: '/in' },

  '/api/v1/me': {
    id: 'u-1',
    tenant: 'Acme Lojistik',
    email: 'ayse@acme.test',
    displayName: 'Ayşe Demir',
    status: 'ACTIVE',
    // Left null on purpose: "they have not told us" is the state most accounts are in, and the
    // preview should open in whatever the URL asked for rather than pinning a language.
    language: null,
  },

  '/api/v1/me/home': home,

  // The console. Every one of these is a real endpoint (docs/api-surface.md); what is fake is only
  // the company inside them.
  '/api/v1/roles': roles,
  '/api/v1/courses': courses,
  '/api/v1/grading/queue': gradingQueue,
  '/api/v1/assignments': [],
  '/api/v1/content-items': [],
  '/api/v1/content-items/types': [
    { code: 'video', displayName: 'Video' },
    { code: 'scorm', displayName: 'SCORM package' },
    { code: 'cmi5', displayName: 'cmi5 package' },
    { code: 'slides', displayName: 'Slides or document' },
    { code: 'test', displayName: 'Test' },
  ],
};

/**
 * Answer from the table above, and 404 anything else.
 *
 * <p>The 404 is deliberate rather than lazy: a screen whose endpoint is not in the table renders
 * its real "not found or not available to you" state (T-2.4's disclosure rule), which is a state
 * worth looking at. A stub that returned an empty 200 for everything would instead show every
 * unlisted screen as an empty success, which is the one answer that is never true.
 */
export function serveFixtures() {
  const real = globalThis.fetch;

  globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(
      typeof input === 'string' ? input : input instanceof URL ? input.href : input.url,
      window.location.origin,
    );

    // Anything not under our own API is left alone -- the Google Fonts stylesheet, above all.
    if (!url.pathname.startsWith('/api') && !url.pathname.startsWith('/auth')) {
      return real(input as RequestInfo, init);
    }

    // The one path with a variable in it. A `PlayerView` for a node with no pinned questions is
    // an empty object rather than a 404: the endpoint answers for every node the caller may see.
    const pinned = /^\/api\/v1\/me\/nodes\/([^/]+)\/interstitials$/.exec(url.pathname);
    if (pinned) {
      return Promise.resolve(
        new Response(JSON.stringify(interstitials[pinned[1] ?? ''] ?? {}), {
          headers: { 'content-type': 'application/json' },
        }),
      );
    }

    const body = answers[url.pathname];
    if (body === undefined) {
      return Promise.resolve(
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'Not Found',
            status: 404,
            code: 'NOT_FOUND',
            detail: 'Not found, or not available to you.',
          }),
          { status: 404, headers: { 'content-type': 'application/problem+json' } },
        ),
      );
    }
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        headers: { 'content-type': 'application/json' },
      }),
    );
  }) as typeof fetch;
}
