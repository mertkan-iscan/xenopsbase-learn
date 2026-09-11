import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The SCORM run-time a package actually finds, driven the way a package drives it (T-4.4).
 *
 * <h2>Why this test is here and not in the packaging module</h2>
 *
 * <p>The wrapper is five hundred lines of JavaScript served out of a Java jar, and until this file
 * existed <b>nothing executed a line of it</b>. A Java test cannot: there has been no JavaScript
 * engine in the JDK since Nashorn was removed, and the one thing worth asserting here — that a
 * conformance-checking package gets sensible answers from `GetLastError` — is a question about
 * running code rather than about a string in a resource.
 *
 * <p>So the template is read from where it lives, configured the way {@code Wrapper.java}
 * configures it, and its inline script is run in jsdom. The coupling to a path outside `web/` is
 * the price, and it is the honest one: this application is the other half of that document's
 * protocol already ({@link ../learner/packageBridge.ts}), and a wrapper that changed under it
 * should fail here rather than in front of a learner.
 *
 * <h2>What made this necessary</h2>
 *
 * <p>Probing the live wrapper in a browser did not work, and the reason is the point: the package
 * inside the iframe had already called `LMSInitialize` and `LMSFinish` before the probe ran, so
 * every "before initialize" check was really an "after terminate" check and the results looked
 * like a broken runtime. A test needs a runtime nobody else has touched, which is exactly what
 * this gives it.
 */

/*
 * From the working directory rather than from `import.meta.url`: vitest transforms this module for
 * jsdom and `import.meta.url` is not a `file:` URL there, so resolving against it throws before a
 * single test runs. `process.cwd()` is `web/` for every way this suite is invoked.
 */
const WRAPPER = resolve(
  process.cwd(),
  '../services/packaging/src/main/resources/wrapper/scorm-wrapper.html',
);

const APP_ORIGIN = 'http://localhost:5173';
const CONTENT_ORIGIN = 'http://acme.localhost:8090';
const PACKAGE_ID = '11111111-2222-4333-8444-555555555555';

type Scorm12 = {
  LMSInitialize: (arg: string) => string;
  LMSFinish: (arg: string) => string;
  LMSGetValue: (element: string) => string;
  LMSSetValue: (element: string, value: string) => string;
  LMSCommit: (arg: string) => string;
  LMSGetLastError: () => string;
  LMSGetErrorString: (code?: string) => string;
  LMSGetDiagnostic: (code?: string) => string;
};

type Scorm2004 = {
  Initialize: (arg: string) => string;
  Terminate: (arg: string) => string;
  GetValue: (element: string) => string;
  SetValue: (element: string, value: string) => string;
  Commit: (arg: string) => string;
  GetLastError: () => string;
  GetErrorString: (code?: string) => string;
  GetDiagnostic: (code?: string) => string;
};

type Wrapped = { window: Window & typeof globalThis };

/**
 * Loads the wrapper into this document with a launch configuration, the way the service does.
 *
 * <p>`document.body.innerHTML` deliberately does NOT run scripts, so the inline script is pulled
 * out and evaluated on its own — which is also what makes it possible to run it once per test with
 * a fresh `window` state rather than once per file.
 */
function launch(profile: string | null): Wrapped {
  const template = readFileSync(WRAPPER, 'utf8');
  const configuration = JSON.stringify({
    entryUrl: `${CONTENT_ORIGIN}/packages/acme/${PACKAGE_ID}/files/index.html`,
    profile,
    packageId: PACKAGE_ID,
    appOrigin: APP_ORIGIN,
    contentOrigin: CONTENT_ORIGIN,
  });
  const document_ = template.replace('{{LAUNCH_JSON}}', configuration);

  const body = document_.slice(document_.indexOf('<body>') + '<body>'.length,
    document_.indexOf('</body>'));
  document.body.innerHTML = body;

  // The LAST script element is the runtime; the first is the JSON island the runtime reads.
  const scripts = [...document.querySelectorAll('script')];
  const runtime = scripts.at(-1)?.textContent ?? '';
  new Function(runtime).call(window);
  return { window };
}

function api12(): Scorm12 {
  return (window as unknown as { API: Scorm12 }).API;
}

function api2004(): Scorm2004 {
  return (window as unknown as { API_1484_11: Scorm2004 }).API_1484_11;
}

describe('the wrapper as a SCORM package finds it', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
    delete (window as unknown as Record<string, unknown>).API;
    delete (window as unknown as Record<string, unknown>).API_1484_11;
    delete (window as unknown as Record<string, unknown>).xenopslearn;
  });

  it('exposes both API objects to a SCORM package, whatever its manifest declared', () => {
    launch('scorm-1.2');
    // Both, because a manifest's declaration is not always the truth about the JavaScript inside
    // it, and a package that finds neither stops at its first line with no message anybody sees.
    expect(typeof api12()).toBe('object');
    expect(typeof api2004()).toBe('object');
    expect((window as unknown as Record<string, unknown>).xenopslearn).toBeUndefined();
  });

  it('exposes neither to an HTML5 bundle, which has no standard to be conformant to', () => {
    launch('html5');
    expect((window as unknown as Record<string, unknown>).API).toBeUndefined();
    expect((window as unknown as Record<string, unknown>).API_1484_11).toBeUndefined();
    const html5 = (window as unknown as { xenopslearn: { version: number } }).xenopslearn;
    expect(html5.version).toBe(1);
    expect(Object.isFrozen(html5)).toBe(true);
  });

  it('answers before Initialize the way each vocabulary spells it', () => {
    launch('scorm-1.2');

    api12().LMSGetValue('cmi.core.lesson_status');
    expect(api12().LMSGetLastError()).toBe('301');
    expect(api12().LMSSetValue('cmi.core.score.raw', '5')).toBe('false');
    expect(api12().LMSGetLastError()).toBe('301');

    /*
     * 2004 SPLITS THESE, and a conformance suite checks each one separately. A runtime answering
     * 122 for everything fails the half of the tests that ask what happens after Terminate.
     */
    api2004().GetValue('cmi.completion_status');
    expect(api2004().GetLastError()).toBe('122');
    api2004().SetValue('cmi.location', 'x');
    expect(api2004().GetLastError()).toBe('132');
    api2004().Commit('');
    expect(api2004().GetLastError()).toBe('142');
    api2004().Terminate('');
    expect(api2004().GetLastError()).toBe('112');
  });

  it('answers after Terminate differently from before Initialize', () => {
    launch('scorm-1.2');
    api2004().Initialize('');
    expect(api2004().Terminate('')).toBe('true');

    api2004().GetValue('cmi.completion_status');
    expect(api2004().GetLastError()).toBe('123');
    api2004().SetValue('cmi.location', 'x');
    expect(api2004().GetLastError()).toBe('133');
    api2004().Commit('');
    expect(api2004().GetLastError()).toBe('143');
    api2004().Terminate('');
    expect(api2004().GetLastError()).toBe('113');
    // Initializing a terminated instance is its own code, not "already initialized".
    api2004().Initialize('');
    expect(api2004().GetLastError()).toBe('104');
  });

  it('refuses a second Initialize in the caller"s own vocabulary', () => {
    launch('scorm-2004');
    expect(api12().LMSInitialize('')).toBe('true');

    /*
     * THE ONE THAT MOTIVATED PARAMETERISING THE FACTORY. This package's manifest says 2004 and its
     * JavaScript calls the 1.2 entry points, which is what a rebuilt-from-a-template export does.
     * The error code belongs to the OBJECT it called: 103 means nothing in the vocabulary it is
     * checking against.
     */
    expect(api12().LMSInitialize('')).toBe('false');
    expect(api12().LMSGetLastError()).toBe('101');
    api2004().Initialize('');
    expect(api2004().GetLastError()).toBe('103');
  });

  it('seeds the defaults a package reads before it has written anything', () => {
    launch('scorm-1.2');
    /*
     * OPEN FIRST, and that ordering is a guarantee rather than a detail of this test. The package
     * lives in an iframe that `open()` creates, and `open()` seeds the map before it creates it --
     * so no package can reach the API before the defaults are there. A test that skipped this
     * would be asking the runtime a question no package can ask.
     */
    vi.advanceTimersByTime(1500);
    api12().LMSInitialize('');

    // "" where the standard promises "not attempted" is how a package takes the wrong branch and
    // reports a completion the learner has not earned.
    expect(api12().LMSGetValue('cmi.core.lesson_status')).toBe('not attempted');
    expect(api12().LMSGetValue('cmi.core.entry')).toBe('ab-initio');
    expect(api12().LMSGetValue('cmi.core.credit')).toBe('credit');
    expect(api12().LMSGetValue('cmi.core.lesson_mode')).toBe('normal');
    // The LMS's own answer, filled in even when the application never replied.
    expect(api12().LMSGetValue('cmi.core.total_time')).toBe('0000:00:00.00');
  });

  it('answers an unknown element with "" and no error, which is what packages expect', () => {
    launch('scorm-1.2');
    api12().LMSInitialize('');

    // Answering 401 makes conformance-checking packages abort a course that would otherwise run.
    expect(api12().LMSGetValue('cmi.interactions.99.id')).toBe('');
    expect(api12().LMSGetLastError()).toBe('0');
  });

  it('refuses a write to an element the LMS owns', () => {
    launch('scorm-1.2');
    vi.advanceTimersByTime(1500);
    api12().LMSInitialize('');
    api2004().Initialize('');

    expect(api12().LMSSetValue('cmi.core.total_time', '9999:00:00.00')).toBe('false');
    expect(api12().LMSGetLastError()).toBe('403');
    expect(api12().LMSSetValue('cmi.core.entry', 'resume')).toBe('false');
    expect(api12().LMSGetLastError()).toBe('403');

    expect(api2004().SetValue('cmi.total_time', 'PT9H')).toBe('false');
    expect(api2004().GetLastError()).toBe('404');
    // And the value the LMS gave is still the value the package reads.
    expect(api12().LMSGetValue('cmi.core.total_time')).toBe('0000:00:00.00');
  });

  it('refuses a read of an element the package only writes', () => {
    launch('scorm-1.2');
    api12().LMSInitialize('');
    api2004().Initialize('');

    expect(api12().LMSGetValue('cmi.core.session_time')).toBe('');
    expect(api12().LMSGetLastError()).toBe('404');
    expect(api2004().GetValue('cmi.session_time')).toBe('');
    expect(api2004().GetLastError()).toBe('405');
  });

  it('refuses an over-long element rather than truncating it', () => {
    launch('scorm-1.2');
    api12().LMSInitialize('');
    api2004().Initialize('');

    expect(api12().LMSSetValue('cmi.suspend_data', 'x'.repeat(65536))).toBe('true');
    expect(api12().LMSSetValue('cmi.suspend_data', 'y'.repeat(65537))).toBe('false');
    expect(api12().LMSGetLastError()).toBe('405');
    expect(api2004().SetValue('cmi.suspend_data', 'y'.repeat(65537))).toBe('false');
    expect(api2004().GetLastError()).toBe('407');

    /*
     * NOTHING WAS TRUNCATED, which is the whole reason this is an error at all. Half a suspend
     * blob does not deserialise, so a package handed one back does not resume badly -- it fails to
     * start on the next launch, with nothing naming the cause.
     */
    expect(api12().LMSGetValue('cmi.suspend_data')).toBe('x'.repeat(65536));
  });

  it('gives a support engineer a sentence rather than a number', () => {
    launch('scorm-1.2');

    expect(api12().LMSGetErrorString('403')).toBe('Element is read only');
    expect(api12().LMSGetErrorString('405')).toBe('Incorrect data type');
    // The same number, a different meaning, in the other vocabulary -- which is why there are two
    // tables and the caller's object chooses.
    expect(api2004().GetErrorString('403')).toBe('Data model element value not initialized');
    expect(api2004().GetErrorString('407')).toBe('Data model element value out of range');
    expect(api2004().GetDiagnostic('112')).toBe('Termination before initialization');
    expect(api12().LMSGetErrorString('0')).toBe('No error');
  });
});

describe('what the wrapper tells the application', () => {
  let posted: { type: string; payload?: Record<string, unknown> }[];
  let parent: { postMessage: (message: unknown, origin: string) => void };

  beforeEach(() => {
    vi.useFakeTimers();
    posted = [];
    parent = {
      postMessage: (message: unknown, origin: string) => {
        expect(origin).toBe(APP_ORIGIN);
        posted.push(message as { type: string; payload?: Record<string, unknown> });
      },
    };
    // The wrapper posts nothing when it is not embedded (`window.parent === window`), which is
    // correct and useless here: standing one in makes the channel observable.
    Object.defineProperty(window, 'parent', { value: parent, configurable: true, writable: true });
  });

  afterEach(() => {
    vi.useRealTimers();
    Object.defineProperty(window, 'parent', { value: window, configurable: true, writable: true });
    document.body.innerHTML = '';
    delete (window as unknown as Record<string, unknown>).API;
    delete (window as unknown as Record<string, unknown>).API_1484_11;
    delete (window as unknown as Record<string, unknown>).xenopslearn;
  });

  it('asks for the learner"s state and opens anyway when nobody answers', () => {
    launch('scorm-1.2');
    expect(posted.map((message) => message.type)).toEqual(['ready']);

    // The timeout is what keeps a package launchable when the application has nothing to say.
    // Waiting forever for a message nobody will send turns a missing feature into a blank screen.
    vi.advanceTimersByTime(1500);
    expect(posted.map((message) => message.type)).toContain('opened');
    expect(document.querySelector('iframe')?.getAttribute('src')).toContain('/files/index.html');
  });

  it('folds a storm of commits into one, and never loses the last', () => {
    launch('scorm-1.2');
    vi.advanceTimersByTime(1500);
    api12().LMSInitialize('');
    posted.length = 0;

    // A package committing every second for ten seconds -- which is ordinary, conformant content.
    for (let second = 0; second < 10; second += 1) {
      api12().LMSSetValue('cmi.core.lesson_location', String(second));
      api12().LMSCommit('');
      vi.advanceTimersByTime(1000);
    }
    const duringTheStorm = posted.filter((message) => message.type === 'commit').length;
    expect(duringTheStorm).toBeLessThan(10);

    /*
     * AND THE LAST ONE STILL LANDS. That is what makes folding lossless: every commit carries the
     * whole data model, so the only one that must not be dropped is the final one -- and Terminate
     * forces past the interval rather than relying on a timer that may never fire.
     */
    api12().LMSSetValue('cmi.core.lesson_status', 'completed');
    api12().LMSFinish('');
    const last = [...posted].reverse().find((message) => message.type === 'commit');
    const data = last?.payload?.data as Record<string, string>;
    expect(data['cmi.core.lesson_status']).toBe('completed');
    expect(data['cmi.core.lesson_location']).toBe('9');
  });

  it('fills in a session time only for content that reports none of its own', () => {
    launch('html5');
    vi.advanceTimersByTime(1500);
    vi.advanceTimersByTime(30_000);

    const html5 = (window as unknown as {
      xenopslearn: { complete: () => void };
    }).xenopslearn;
    html5.complete();

    const last = [...posted].reverse().find((message) => message.type === 'commit');
    const data = last?.payload?.data as Record<string, string>;
    // An HTML5 bundle has no notion of a session time, and without this its total would be nought
    // forever however long anybody spent in it.
    expect(data['cmi.core.session_time']).toMatch(/^\d{4}:\d{2}:\d{2}\.\d{2}$/);
    expect(data['cmi.completion_status']).toBe('completed');
  });

  it('stops saving and says so when another launch takes the registration', () => {
    launch('scorm-1.2');
    vi.advanceTimersByTime(1500);
    api12().LMSInitialize('');

    window.dispatchEvent(
      new MessageEvent('message', {
        origin: APP_ORIGIN,
        source: parent as unknown as Window,
        data: {
          source: 'xenopslearn.app',
          type: 'superseded',
          payload: { text: 'Opened in another window.' },
        },
      }),
    );

    expect(document.getElementById('superseded')?.hasAttribute('data-shown')).toBe(true);
    expect(document.getElementById('superseded-text')?.textContent)
      .toBe('Opened in another window.');

    posted.length = 0;
    api12().LMSSetValue('cmi.core.lesson_location', '40');
    api12().LMSCommit('');
    vi.advanceTimersByTime(30_000);
    api12().LMSFinish('');

    // Nothing more is sent. A window that looks like it is working and saves nothing is the
    // silent failure the whole rule exists to end.
    expect(posted.filter((message) => message.type === 'commit')).toHaveLength(0);
  });

  it('ignores a message from the wrong origin or the wrong sender', () => {
    launch('scorm-1.2');

    const impostor = { postMessage: () => {} } as unknown as Window;
    window.dispatchEvent(new MessageEvent('message', {
      origin: 'http://evil.test',
      source: parent as unknown as Window,
      data: { source: 'xenopslearn.app', type: 'superseded', payload: { text: 'gone' } },
    }));
    window.dispatchEvent(new MessageEvent('message', {
      origin: APP_ORIGIN,
      source: impostor,
      data: { source: 'xenopslearn.app', type: 'superseded', payload: { text: 'gone' } },
    }));

    // Neither check is sufficient alone: an origin an attacker controls, and a third frame on the
    // page posting from the right one.
    expect(document.getElementById('superseded')?.hasAttribute('data-shown')).toBe(false);
  });

  it('seeds who the learner is, in both vocabularies', () => {
    launch('scorm-1.2');
    window.dispatchEvent(new MessageEvent('message', {
      origin: APP_ORIGIN,
      source: parent as unknown as Window,
      data: {
        source: 'xenopslearn.app',
        type: 'state',
        payload: {
          data: { 'cmi.core.lesson_status': 'incomplete', 'cmi.core.total_time': '0001:00:00.00' },
          entry: 'resume',
          learner: { id: 'a-durable-id', name: 'Kaya, Berk' },
        },
      },
    }));
    api12().LMSInitialize('');

    expect(api12().LMSGetValue('cmi.core.student_name')).toBe('Kaya, Berk');
    expect(api12().LMSGetValue('cmi.core.student_id')).toBe('a-durable-id');
    // The entry is the APPLICATION's answer -- it counts launches, and a wrapper guessing from
    // "is there any suspend_data" would tell a package it was resuming a course it never opened.
    expect(api12().LMSGetValue('cmi.core.entry')).toBe('resume');
    expect(api12().LMSGetValue('cmi.core.lesson_status')).toBe('incomplete');
    expect(api12().LMSGetValue('cmi.core.total_time')).toBe('0001:00:00.00');
  });
});
