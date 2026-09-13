import { packaging, streaming } from '../shared/api/client.ts';

/**
 * Getting a file from an author's machine into this platform (T-3.2, T-4.1).
 *
 * <h2>The bytes never pass through this application, and never through the gateway</h2>
 *
 * <p>Both flows here are the same three steps: ask a service to reserve something and issue a
 * signed target, send the file <b>directly to that target</b>, then tell the service to look at
 * what arrived. What differs is who the target belongs to — the video delivery provider for a
 * video (ADR-0101), our own object storage for a package — and what the third step does.
 *
 * <p>That shape is not an optimisation. A 200MB course going through the gateway is a request
 * thread held for minutes and a heap spooling somebody else's file, on the same process every
 * learner's API call goes through; and for video it would put large-object traffic on exactly the
 * path ADR-0101 exists to keep empty.
 *
 * <h2>No credential of ours is attached to the upload itself</h2>
 *
 * <p>The `PUT`/`PATCH` below goes out with a plain `fetch`/`XMLHttpRequest` and
 * `credentials: 'omit'`, deliberately. The signed URL <em>is</em> the authorisation — it carries a
 * signature over one bucket, one key and one content length, for a bounded time — and attaching
 * our session cookie on top would send this origin's credential to a host that is not this origin
 * and has no business holding it. The default is 'same-origin' and would not send it, but the
 * default is not a decision anybody wrote down.
 */

/** Where an upload has got to, for a screen to render. Never assembled into a sentence here. */
export type Stage =
  | { at: 'idle' }
  | { at: 'creating' }
  | { at: 'sending'; sent: number; total: number }
  | { at: 'processing' }
  | { at: 'done'; id: string; state: string; title?: string | null }
  | { at: 'refused'; reason: string }
  | { at: 'failed'; reason?: string };

export type Report = (stage: Stage) => void;

/**
 * Sends the file to a signed target, reporting progress.
 *
 * <p><b>`XMLHttpRequest` and not `fetch`, in 2026, for one reason:</b> upload progress. `fetch`
 * still has no upload-progress event in any shipping browser — request streams exist but are
 * HTTP/2-only, require `duplex: 'half'`, and are unsupported in Firefox and Safari. An author
 * sending a 200MB course over office wifi needs to see it moving; a spinner with no number is the
 * thing that makes people press the button again and start a second upload.
 */
function send(
  method: 'PUT' | 'PATCH',
  url: string,
  body: Blob,
  headers: Record<string, string>,
  report: Report,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open(method, url, true);
    // See the header: the signed URL is the authorisation, and this origin's cookies are not that
    // host's business. `false` is the default and is stated because it is a decision.
    request.withCredentials = false;
    for (const [name, value] of Object.entries(headers)) {
      request.setRequestHeader(name, value);
    }
    request.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable) {
        report({ at: 'sending', sent: event.loaded, total: event.total });
      }
    });
    request.addEventListener('load', () => {
      if (request.status >= 200 && request.status < 300) {
        resolve();
        return;
      }
      /*
       * The body of a storage refusal is XML nobody wants to read, and it is also the only place
       * the reason is. The status carries the useful distinction: 403 is almost always a target
       * that has expired, 413 a file bigger than the size that was signed into it.
       */
      reject(new Error(`${request.status} ${request.statusText}`.trim()));
    });
    // `error` fires for a network failure and for a CORS refusal alike, and the browser
    // deliberately tells script nothing about which -- so this message must not claim to know.
    request.addEventListener('error', () => reject(new Error('the upload could not be sent')));
    request.addEventListener('abort', () => reject(new Error('the upload was cancelled')));
    request.send(body);
  });
}

/**
 * Uploads a SCORM, cmi5 or slides archive and has it checked (T-4.1).
 *
 * <p>The third step is where the interesting answer comes from. It returns 200 with a state rather
 * than an error status when the archive is refused, because the CALL worked — the service read the
 * file and reached a verdict — and the verdict is the answer. `refused` here is an author's
 * problem with a sentence they can act on; `failed` is ours and never their fault.
 */
export async function uploadPackage(
  kind: 'scorm' | 'cmi5' | 'html5' | 'slides',
  file: File,
  report: Report,
): Promise<Stage> {
  report({ at: 'creating' });

  const created = await packaging.POST('/api/v1/uploads', {
    body: { kind, filename: file.name, sizeBytes: file.size },
  });
  const target = created.data?.uploadUrl;
  const id = created.data?.id;
  if (!target || !id) {
    return fail(report, created.response?.status);
  }

  try {
    /*
     * `Content-Type` is NOT set, and that is not an oversight.
     *
     * The signature covers the headers that were signed, and the service signed a bucket, a key
     * and a content length. A browser that helpfully adds `Content-Type: application/zip` to a
     * request whose signature did not include it gets a `SignatureDoesNotMatch` from storage --
     * which reads, from here, as "your file was rejected" rather than "we disagreed about a
     * header". XMLHttpRequest only adds one when `send` is given a string, not a Blob.
     */
    await send('PUT', target, file, {}, report);
  } catch (couldNotSend) {
    return fail(report, undefined, couldNotSend);
  }

  report({ at: 'processing' });
  const ingested = await packaging.POST('/api/v1/uploads/{id}/ingest', {
    params: { path: { id } },
  });
  const view = ingested.data;
  if (!view?.state) {
    return fail(report, ingested.response?.status);
  }
  if (view.state === 'READY') {
    const done: Stage = { at: 'done', id, state: view.state, title: view.title ?? null };
    report(done);
    return done;
  }
  if (view.state === 'REJECTED') {
    // The service's own sentence, which names the entry that broke the rule. Passed through
    // unchanged: a generic "invalid package" here would throw away the only useful part.
    const refused: Stage = { at: 'refused', reason: view.error ?? '' };
    report(refused);
    return refused;
  }
  return fail(report, undefined, view.error ? new Error(view.error) : undefined);
}

/**
 * Uploads a video to the delivery provider's own target (T-3.2, ADR-0101).
 *
 * <h2>tus, in about twenty lines, and why that is enough</h2>
 *
 * <p>The target streaming hands back is a tus endpoint. The full protocol is a creation request, a
 * `HEAD` to learn the current offset, and one or more `PATCH`es from that offset — and the
 * creation half has already happened server-side, which is what the URL is. So a first attempt is
 * a single `PATCH` at offset zero.
 *
 * <p>The `HEAD` is done first anyway, on every attempt. It costs one round trip and it is what
 * makes a retry resume rather than restart: an author whose 2GB upload died at 90% on a train
 * should not send the first 1.8GB again. That is the entire reason the platform issues a tus
 * target instead of a plain signed `PUT`.
 */
export async function uploadVideo(
  file: File,
  maxDurationSeconds: number,
  report: Report,
): Promise<Stage> {
  report({ at: 'creating' });

  const created = await streaming.POST('/api/v1/videos', {
    body: { maxDurationSeconds, sizeBytes: file.size },
  });
  const target = created.data?.uploadUrl;
  const id = created.data?.id;
  if (!target || !id) {
    return fail(report, created.response?.status);
  }

  try {
    const offset = await tusOffset(target);
    await send(
      'PATCH',
      target,
      // Only what has not been sent. `slice` on a File is a view, not a copy, so resuming a 2GB
      // upload does not read 2GB into memory to skip most of it.
      offset > 0 ? file.slice(offset) : file,
      {
        'Tus-Resumable': '1.0.0',
        'Upload-Offset': String(offset),
        'Content-Type': 'application/offset+octet-stream',
      },
      report,
    );
  } catch (couldNotSend) {
    return fail(report, undefined, couldNotSend);
  }

  /*
   * NO POLLING FOR "READY" HERE, and that is deliberate rather than unfinished.
   *
   * Encoding happens on the provider's machines and takes as long as it takes; the state reaches
   * us through a webhook and a reconciler (T-3.3), not through this browser. An author who has
   * finished sending the file has finished their part, and can attach the asset to a course
   * immediately -- the id is stable from the moment it was created. Holding this screen open on a
   * spinner until an encode finished would be asking somebody to watch a queue they are not in.
   */
  report({ at: 'processing' });
  const asset = await streaming.GET('/api/v1/videos/{id}', { params: { path: { id } } });
  const done: Stage = { at: 'done', id, state: asset.data?.state ?? 'PROCESSING' };
  report(done);
  return done;
}

/** Where a partly-sent upload got to. Zero when the server has nothing, which is the normal case. */
async function tusOffset(target: string): Promise<number> {
  try {
    const response = await fetch(target, {
      method: 'HEAD',
      credentials: 'omit',
      headers: { 'Tus-Resumable': '1.0.0' },
    });
    const offset = Number(response.headers.get('Upload-Offset'));
    return Number.isFinite(offset) && offset > 0 ? offset : 0;
  } catch {
    // A HEAD that fails is not a reason to abandon the upload -- start from the beginning, which
    // is what would have happened without any of this.
    return 0;
  }
}

function fail(report: Report, status?: number, cause?: unknown): Stage {
  const reason =
    cause instanceof Error && cause.message
      ? cause.message
      : status
        ? String(status)
        : undefined;
  const stage: Stage = reason === undefined ? { at: 'failed' } : { at: 'failed', reason };
  report(stage);
  return stage;
}
