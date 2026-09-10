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
      referenceId: 'n-yangin-1',
      referenceType: 'NODE',
      title: 'Yangın Güvenliği Tazeleme',
      state: 'AVAILABLE',
      percent: 0,
      dueOn: inDays(-6),
      overdue: true,
      cycleNumber: 2,
      sources: ['GROUP'],
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
      sources: ['DIRECT'],
      modules: [
        {
          moduleId: 'm-kvkk-1',
          title: 'Temel kavramlar',
          locked: false,
          nodes: [
            {
              nodeId: 'n-kvkk-1',
              title: 'Veri sorumlusu kimdir?',
              type: 'VIDEO',
              state: 'COMPLETE',
              percent: 100,
              required: true,
            },
            {
              nodeId: 'n-kvkk-2',
              title: 'Bölüm 2 · Hukuka uygunluk sebepleri',
              type: 'VIDEO',
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
              type: 'TEST',
              state: 'LOCKED',
              percent: 0,
              required: true,
              lockedReason: 'Bölüm 2 videosunu bitirdiğinizde açılır.',
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
      sources: ['GROUP'],
      modules: [
        {
          moduleId: 'm-yangin-1',
          title: 'Tahliye',
          locked: false,
          nodes: [
            {
              nodeId: 'n-yangin-1',
              title: 'Tahliye tatbikatı',
              type: 'VIDEO',
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
      sources: ['COMPANY'],
      modules: [
        {
          moduleId: 'm-isg-1',
          title: 'Genel esaslar',
          locked: false,
          nodes: [
            {
              nodeId: 'n-isg-1',
              title: 'Risk değerlendirmesi',
              type: 'SLIDES',
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
      sources: ['GROUP'],
      modules: [
        {
          moduleId: 'm-bilgi-1',
          title: 'Kimlik avı',
          locked: false,
          nodes: [
            {
              nodeId: 'n-bilgi-1',
              title: 'Şüpheli e-postayı tanımak',
              type: 'SCORM',
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
      sources: ['DIRECT'],
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
              type: 'VIDEO',
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
