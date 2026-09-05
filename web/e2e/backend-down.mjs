#!/usr/bin/env node
/**
 * T-3.10: the test that turns ADR-0101 from a claim into a fact.
 *
 * Mints a real playback token against the real Cloudflare Stream account, starts the video
 * playing in a real browser, then kills every one of our own services (identity, catalog,
 * streaming, reporting -- not Postgres/Keycloak/Valkey/MinIO, which are third-party dependencies
 * a learner's browser never talks to directly) and proves the video keeps playing anyway. Brings
 * the services back and proves the progress buffered during the outage flushes.
 *
 * WHY THIS NEEDS THE REAL ACCOUNT, NOT THE FAKE PROVIDER
 *
 * FakeMediaProvider mints a manifest URL on `fake-media.invalid`, on purpose (T-3.1): it exercises
 * every path up to the edge and proves nothing about the edge itself. A test that wants to prove
 * playback survives has to point a real HLS player at a real CDN, which means this script needs
 * `MEDIA_PROVIDER=cloudflare-stream` and a funded Stream account (T-9.14). It is deliberately
 * NOT part of `npm run verify` or the default CI gate for that reason -- it is real spend, and
 * T-9.14 (#100) left "spend visibility before any bulk upload" open. Run it by hand, or from the
 * workflow_dispatch-only CI job (.github/workflows/backend-down.yml), never on every push.
 *
 * WHAT IT NEEDS ALREADY RUNNING
 *
 *   make up              -- Postgres, Keycloak (realm imported), Valkey, MinIO, content-origin
 *   make realm-apply     -- if the realm was just imported fresh
 *   mvn -f services/pom.xml -DskipTests install
 *
 * and these Cloudflare variables in the environment (scripts/cloudflare-check.sh proves them):
 *   CF_STREAM_ACCOUNT_ID, CF_STREAM_API_TOKEN, CF_STREAM_CUSTOMER_SUBDOMAIN,
 *   CF_STREAM_SIGNING_KEY_ID, CF_STREAM_SIGNING_KEY_JWK
 *
 * WHY THE ENTITLEMENT STUB
 *
 * `UnassignedContent` refuses every playback request until catalog is wired to streaming, which
 * is a separate, still-open piece of work T-3.10 does not own. `E2eContentEntitlement`
 * (`@Profile("e2e")`, streaming/src/main/.../playback/E2eContentEntitlement.java) treats the node
 * id asked about AS a video asset id, so this script needs no seeding beyond uploading a video --
 * whatever it uploads is immediately its own, entitled "node".
 *
 * WHY THE ROLE GRANT
 *
 * The local realm's seeded users hold no grants (a repeated finding in this repo): a fresh
 * `content:view` check answers false until something inserts a role_assignment. This script does
 * it the same way manual local testing already does -- granting the tenant's seeded "Learner"
 * role directly in Postgres -- but resolves WHICH app_user to grant it to by asking identity who
 * the freshly-minted token actually resolves to (`GET /api/v1/me`), rather than assuming the
 * Keycloak username matches the seed data's email. They do not always match: this was found the
 * hard way, granting the wrong app_user twice before checking.
 *
 * A shortened token lifetime (PLAYBACK_TOKEN_TTL / PLAYBACK_RENEW_AFTER) is used for streaming, so
 * a video under a minute proves a real renewal attempt happens, fails while we are down, and does
 * not disturb playback -- without needing a multi-minute video and the Stream minutes that would
 * cost.
 */
import { spawn, execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, statSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { chromium } from 'playwright';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..', '..');
const webRoot = join(here, '..');

const KEYCLOAK_URL = process.env.KEYCLOAK_URL ?? 'http://localhost:8081';
const REALM = 'xenopslearn';
const IDENTITY_URL = process.env.IDENTITY_URL ?? 'http://localhost:8082';
const STREAMING_URL = process.env.STREAMING_URL ?? 'http://localhost:8083';
const WEB_URL = process.env.WEB_URL ?? 'http://localhost:5173';
const TENANT_USER = process.env.E2E_USER ?? 'acme-learner';

// java-home.sh is a Git-Bash script and reports a POSIX-style path (`/c/Users/...`) even to a
// native Windows process invoking it -- Node's own `path.join` does not understand that form, so
// it has to be turned into a drive path before anything here can use it.
function toWindowsPath(posixPath) {
  const match = /^\/([a-zA-Z])\/(.*)$/.exec(posixPath);
  if (!match) return posixPath;
  return `${match[1].toUpperCase()}:\\${match[2].replace(/\//g, '\\')}`;
}

const javaHomeRaw = execFileSync('bash', [join(repoRoot, 'scripts', 'java-home.sh')])
  .toString()
  .trim();
const JAVA_HOME = process.platform === 'win32' ? toWindowsPath(javaHomeRaw) : javaHomeRaw;
const JAVA = join(JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');

const CF_VARS = [
  'CF_STREAM_ACCOUNT_ID',
  'CF_STREAM_API_TOKEN',
  'CF_STREAM_CUSTOMER_SUBDOMAIN',
  'CF_STREAM_SIGNING_KEY_ID',
  'CF_STREAM_SIGNING_KEY_JWK',
];
for (const name of CF_VARS) {
  if (!process.env[name]) {
    console.error(`${name} is not set. This test needs the real Cloudflare account (T-9.14) -- `);
    console.error('run scripts/cloudflare-check.sh first to confirm the credentials work.');
    process.exit(2);
  }
}

/** One of the four services this platform runs. Postgres/Keycloak/Valkey/MinIO are not these --
 * a learner's browser never talks to them, so killing them would prove nothing this test cares
 * about, and this script leaves them running throughout. */
const SERVICES = [
  { name: 'identity', port: 8082, jar: 'identity', env: {} },
  { name: 'catalog', port: 8085, jar: 'catalog', env: {} },
  { name: 'reporting', port: 8084, jar: 'reporting', env: {} },
  {
    name: 'streaming',
    port: 8083,
    jar: 'streaming',
    env: {
      SPRING_PROFILES_ACTIVE: 'e2e',
      MEDIA_PROVIDER: 'cloudflare-stream',
      // Short enough that the test crosses a renewal well before it finishes, long enough that
      // restarting four JVMs (measured: 15-20s to pass health) and the recovery wait afterwards
      // never race a real token expiry -- the first version of this test set both too tight and
      // the held token expired mid-recovery, which the player correctly (and confusingly, for a
      // test not expecting it) turned into a terminal refusal.
      PLAYBACK_TOKEN_TTL: 'PT3M',
      PLAYBACK_RENEW_AFTER: 'PT20S',
      // The encode reconciler (T-3.3) only re-polls an asset once it has sat unsettled for
      // `stuck-after` (10 minutes by default) -- reasonable in production, where the webhook
      // does the fast path, but this script has no public endpoint for Cloudflare to call back
      // to. Shortened here rather than waiting out a real deployment's patience.
      STREAMING_ENCODE_STUCK_AFTER: 'PT0S',
      STREAMING_ENCODE_RECONCILE_INTERVAL: 'PT3S',
      CF_STREAM_ACCOUNT_ID: process.env.CF_STREAM_ACCOUNT_ID,
      CF_STREAM_API_TOKEN: process.env.CF_STREAM_API_TOKEN,
      CF_STREAM_CUSTOMER_SUBDOMAIN: process.env.CF_STREAM_CUSTOMER_SUBDOMAIN,
      CF_STREAM_SIGNING_KEY_ID: process.env.CF_STREAM_SIGNING_KEY_ID,
      CF_STREAM_SIGNING_KEY_JWK: process.env.CF_STREAM_SIGNING_KEY_JWK,
    },
  },
];

const children = new Map();
let webServer;
let browser;

function log(...args) {
  console.log('[backend-down]', ...args);
}

/** A forceful, whole-tree kill, not `.kill()`: a shelled-out `npm run dev` spawns a node process
 * that spawns vite, and a plain SIGTERM-equivalent to the launcher leaves the grandchild running,
 * orphaned and still bound to its port -- which is indistinguishable from "the service survived"
 * until the next run's health check mysteriously fails on a port already in use. `java -jar`
 * itself is a single process with no children, but the same call handles both without needing to
 * know which. */
function killTree(pid) {
  try {
    if (process.platform === 'win32') {
      execFileSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      // Negative pid: signal the whole process GROUP, which is what a spawned `npm run dev`
      // (shell:true) actually is on POSIX.
      process.kill(-pid, 'SIGKILL');
    }
  } catch {
    // Already gone.
  }
}

async function waitForHealth(port, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://localhost:${port}/management/health`);
      if (res.ok) return;
    } catch {
      // Not up yet.
    }
    await sleep(2000);
  }
  throw new Error(`port ${port} never became healthy`);
}

async function startServices() {
  for (const svc of SERVICES) {
    const jarPath = join(
      repoRoot,
      'services',
      svc.jar,
      'target',
      `${svc.jar}-0.0.1-SNAPSHOT.jar`,
    );
    statSync(jarPath); // fails loudly if `mvn install` was never run
    const child = spawn(JAVA, ['-jar', jarPath], {
      env: { ...process.env, ...svc.env },
      stdio: 'ignore',
      // Its own process group on POSIX, so `killTree` can target the group by pid -- harmless
      // on Windows, where `taskkill /T` does the tree-walking instead.
      detached: process.platform !== 'win32',
    });
    child.on('error', (err) => {
      throw new Error(`could not start ${svc.name}: ${err.message}`);
    });
    children.set(svc.name, child);
    log(`launched ${svc.name} (pid ${child.pid})`);
  }
  await Promise.all(SERVICES.map((svc) => waitForHealth(svc.port)));
  log('all four services healthy');
}

function stopServices() {
  for (const [name, child] of children) {
    if (child.pid) killTree(child.pid);
    log(`stopped ${name}`);
  }
  children.clear();
}

/** The local dev grant every fresh checkout needs (a repeated finding, not new to this script):
 * the seeded users hold no role_assignment until something inserts one. */
function grantLearnerRole(tenantId, appUserId) {
  const sql = `
    INSERT INTO role_assignment (id, tenant_id, role_id, user_id, scope_type, granted_by, created_at)
    SELECT gen_random_uuid(), '${tenantId}', r.id, '${appUserId}', 'TENANT', '${appUserId}', now()
      FROM app_role r
     WHERE r.tenant_id = '${tenantId}' AND r.name = 'Learner' AND r.system = true
    ON CONFLICT DO NOTHING;
  `;
  execFileSync('docker', [
    'exec', 'xenopslearn-postgres-1', 'psql', '-U', 'identity', '-d', 'identity', '-c', sql,
  ]);
  // The permission cache is keyed by (tenant, subject, authz_version); a raw SQL grant does not
  // bump the version, so the only way this run's grant takes effect immediately is clearing it.
  execFileSync('docker', ['exec', 'xenopslearn-valkey-1', 'valkey-cli', 'FLUSHALL']);
}

/** T-3.8 (#41, still Backlog) is what would let streaming delete its own asset; until then this
 * is the same direct-to-vendor cleanup `scripts/cloudflare-check.sh` already does for its own
 * throwaway assets, so this script does not leave a 45-second clip in the real account on every
 * run of every build. Best-effort: a failed cleanup here must not fail the test it is cleaning up
 * after. */
function cleanupCloudflareAsset(nodeId) {
  if (!nodeId) return;
  try {
    const providerRef = execFileSync('docker', [
      'exec', 'xenopslearn-postgres-1', 'psql', '-U', 'streaming', '-d', 'streaming', '-t', '-A',
      '-c', `select provider_ref from video_asset where id='${nodeId}'`,
    ]).toString().trim();
    if (!providerRef) return;
    // fetch, not execFileSync('curl', ...): a failed exec's error carries its full argv,
    // including the Authorization header -- which would print the API token the moment cleanup
    // itself failed. fetch's rejection carries only the network error.
    fetch(
      `https://api.cloudflare.com/client/v4/accounts/${process.env.CF_STREAM_ACCOUNT_ID}/stream/${providerRef}`,
      { method: 'DELETE', headers: { Authorization: `Bearer ${process.env.CF_STREAM_API_TOKEN}` } },
    )
      .then(() => log(`deleted test asset ${providerRef} from the real Cloudflare account`))
      .catch(() => log(`could not clean up Cloudflare test asset ${providerRef} (leaving it)`));
  } catch {
    log(`could not clean up the Cloudflare test asset for ${nodeId} (leaving it)`);
  }
}

async function keycloakToken(username) {
  const res = await fetch(
    `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'password',
        client_id: 'local-tests',
        client_secret: 'local-development-only-test-secret',
        username,
        password: username,
      }),
    },
  );
  if (!res.ok) throw new Error(`Keycloak token request failed: ${res.status}`);
  const body = await res.json();
  return body.access_token;
}

async function uploadTestVideo(token) {
  const clipPath = join(webRoot, 'e2e', 'fixtures', 'backend-down-clip.mp4');
  const bytes = readFileSync(clipPath);

  const created = await fetch(`${STREAMING_URL}/api/v1/videos`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ maxDurationSeconds: 60, sizeBytes: bytes.length }),
  });
  if (!created.ok) {
    throw new Error(`could not create a video: ${created.status} ${await created.text()}`);
  }
  const video = await created.json();

  const uploaded = await fetch(video.uploadUrl, {
    method: 'PATCH',
    headers: {
      'Tus-Resumable': '1.0.0',
      'Upload-Offset': '0',
      'Content-Type': 'application/offset+octet-stream',
    },
    body: bytes,
  });
  if (uploaded.status !== 204) {
    throw new Error(`tus upload failed: ${uploaded.status}`);
  }

  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const status = await fetch(`${STREAMING_URL}/api/v1/videos/${video.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((r) => r.json());
    if (status.state === 'READY') return video.id;
    if (status.state === 'ERRORED') throw new Error('video encode errored');
    await sleep(3000);
  }
  throw new Error('video never reached READY (the encode reconciler runs every 5 minutes by ' +
    'default -- override streaming.encode.reconcile-interval / stuck-after when running this ' +
    'by hand against a service that was not started with them already shortened)');
}

async function startWebServer() {
  // A single command string rather than an argv array: npm on Windows is npm.cmd, which only
  // runs through a shell, and Node's own deprecation warning about shell-plus-argv is for args
  // that need escaping -- there are none here.
  webServer = spawn('npm run dev', {
    cwd: webRoot,
    env: process.env,
    stdio: 'ignore',
    shell: true,
    detached: process.platform !== 'win32',
  });
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(WEB_URL);
      if (res.ok) return;
    } catch {
      // Not up yet.
    }
    await sleep(1000);
  }
  throw new Error('vite dev server never came up');
}

function stopWebServer() {
  if (webServer?.pid) killTree(webServer.pid);
}

function assert(condition, message) {
  if (!condition) throw new Error(`ASSERTION FAILED: ${message}`);
}

async function main() {
  let nodeId;
  try {
    await startServices();

    const token = await keycloakToken(TENANT_USER);
    const me = await fetch(`${IDENTITY_URL}/api/v1/me`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((r) => r.json());
    log(`Keycloak user '${TENANT_USER}' resolves to app_user ${me.id} (${me.email}) in tenant ${me.tenant}`);
    grantLearnerRole(me.tenant, me.id);

    log('uploading the test clip to the real Cloudflare Stream account...');
    nodeId = await uploadTestVideo(token);
    log(`video ${nodeId} is READY`);

    writeFileSync(join(webRoot, '.env.local'), `VITE_DEV_TOKEN=${token}\n`);
    await startWebServer();

    // Chromium blocks unmuted autoplay outside a user gesture by default; this test cares about
    // whether playback SURVIVES an outage, not about the autoplay policy, so it is disabled here
    // rather than muting the video (which would be testing something slightly different).
    browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
    const page = await browser.newPage();

    // The browser only ever sees ONE origin (localhost:5173, vite.config.ts's dev stand-in for
    // the production gateway) -- it is vite's own proxy, a separate process, that forwards to
    // identity/streaming/reporting, so a request never carries their ports and cannot be told
    // apart from any other same-origin call by URL alone. What this test can observe from inside
    // the page is outcome: a renewal or heartbeat call made toward one of OUR api paths while
    // every one of our services is down has nowhere to land and must fail at the network layer.
    const ownApiOutcomes = [];
    const isOwnApi = (url) => {
      const path = new URL(url).pathname;
      return path.includes('/playback-token') || path.includes('/telemetry/');
    };
    page.on('requestfinished', async (req) => {
      if (!isOwnApi(req.url())) return;
      const response = await req.response().catch(() => null);
      ownApiOutcomes.push({
        path: new URL(req.url()).pathname,
        status: response?.status() ?? null,
        at: Date.now(),
      });
    });
    page.on('requestfailed', (req) => {
      if (!isOwnApi(req.url())) return;
      ownApiOutcomes.push({
        path: new URL(req.url()).pathname,
        failure: req.failure()?.errorText,
        at: Date.now(),
      });
    });

    await page.goto(`${WEB_URL}/player.html?node=${nodeId}`);
    await page.waitForSelector('video', { timeout: 15_000 });
    // Autoplay is muted-only in Chromium; the player defaults to muted, so this does not need a
    // user gesture. If it ever does, this is where a synthetic click would go.
    await page.evaluate(() => document.querySelector('video').play());

    const currentTime = () => page.$eval('video', (v) => v.currentTime);

    await page.waitForFunction(() => document.querySelector('video').currentTime > 1, null, {
      timeout: 15_000,
    });
    const beforeOutage = await currentTime();
    log(`playback under way before outage: currentTime=${beforeOutage.toFixed(1)}s`);

    log('killing identity, catalog, reporting and streaming...');
    const killedAt = Date.now();
    stopServices();
    for (const port of [8082, 8083, 8084, 8085]) {
      assert(
        !(await fetch(`http://localhost:${port}/management/health`).then(() => true).catch(() => false)),
        `port ${port} is still answering after being killed`,
      );
    }

    // Long enough to cross PLAYBACK_RENEW_AFTER (20s) at least once while down, comfortably short
    // of the 3-minute token TTL.
    const OUTAGE_SECONDS = 30;
    log(`watching playback for ${OUTAGE_SECONDS}s with every service down...`);
    let sawErrorState = false;
    for (let elapsed = 0; elapsed < OUTAGE_SECONDS; elapsed += 5) {
      await sleep(5000);
      const now = await currentTime();
      assert(now > beforeOutage, `video did not advance during the outage (stuck at ${now}s)`);
      const errorVisible = await page
        .locator('text=/could not be started|could not reach|not available/i')
        .isVisible()
        .catch(() => false);
      if (errorVisible) sawErrorState = true;
      log(`  +${elapsed + 5}s: currentTime=${now.toFixed(1)}s, error state visible=${errorVisible}`);
    }
    const currentTimeDuringOutage = await currentTime();
    assert(currentTimeDuringOutage > beforeOutage, 'no progress at all during the outage window');
    assert(!sawErrorState,
      'the player surfaced an error while the token it already held was still valid -- ' +
      'a renewal failure must retry quietly, not interrupt a working video');

    const recoveredAt = killedAt + OUTAGE_SECONDS * 1000;
    const requestsDuringOutage = ownApiOutcomes.filter((o) => o.at >= killedAt && o.at < recoveredAt);
    log(`requests toward our own API paths while every service was down: ${JSON.stringify(requestsDuringOutage)}`);
    assert(requestsDuringOutage.length > 0,
      'no renewal or heartbeat request was even attempted during the outage -- the test window ' +
      'did not cross PLAYBACK_RENEW_AFTER, so it proves nothing about a failed renewal');
    for (const outcome of requestsDuringOutage) {
      // Vite's dev proxy is the one thing standing between the browser and a dead identity/
      // streaming/reporting: with nothing listening on the target port, it answers its own
      // failure (never a 2xx) or the browser never gets a response at all. Either way is what
      // "no new playback tokens, no progress recorded" looks like from the browser's side.
      assert((outcome.status ?? 0) < 200 || outcome.status >= 300,
        `a request to ${outcome.path} SUCCEEDED (${outcome.status}) while every service was ` +
        'down -- something answered that should not have been running');
    }

    log('bringing services back up...');
    await startServices();
    await sleep(20_000); // past the 15s renew-after, so a fresh mint has had a chance to land

    const afterRecovery = await currentTime();
    assert(afterRecovery > currentTimeDuringOutage, 'playback did not continue after recovery');

    const progress = await fetch(
      `${STREAMING_URL}/api/v1/videos/${nodeId}`,
      { headers: { Authorization: `Bearer ${await keycloakToken(TENANT_USER)}` } },
    ).then((r) => r.json());
    log(`video state after recovery: ${JSON.stringify(progress)}`);

    // THE LAST ACCEPTANCE CRITERION: buffered progress flushes on return, and the interval
    // accounting afterward is correct. useHeartbeats batches client-side while every post fails
    // (10s flush, retry once, 60-sample cap) -- if none of that ever reached reporting or
    // streaming, the buffering existed only in the client's memory and this test would not know
    // the difference between "survived" and "silently lost everything watched during the outage".
    const heartbeatCount = execFileSync('docker', [
      'exec', 'xenopslearn-postgres-1', 'psql', '-U', 'reporting', '-d', 'reporting', '-t', '-A',
      '-c', `select count(*) from playback_heartbeat where node_id='${nodeId}'`,
    ]).toString().trim();
    log(`playback_heartbeat rows recorded for this session (reporting): ${heartbeatCount}`);
    assert(Number(heartbeatCount) > 0,
      'no heartbeats reached reporting at all -- buffering on the client proves nothing if ' +
      'nothing was ever flushed to a service that was actually reachable again');

    const covered = execFileSync('docker', [
      'exec', 'xenopslearn-postgres-1', 'psql', '-U', 'streaming', '-d', 'streaming', '-t', '-A',
      '-c', `select covered from learner_node_progress where node_id='${nodeId}'`,
    ]).toString().trim();
    log(`merged coverage recorded for this session (streaming): ${covered}`);
    assert(covered.length > 0 && covered !== '{}',
      'streaming recorded no covered interval at all -- the watched range never got credited');

    log('PASS: playback survived every one of our services being stopped.');
  } finally {
    await browser?.close().catch(() => undefined);
    cleanupCloudflareAsset(nodeId);
    stopWebServer();
    stopServices();
  }
}

main().catch((err) => {
  console.error('[backend-down] FAILED:', err);
  process.exitCode = 1;
});
